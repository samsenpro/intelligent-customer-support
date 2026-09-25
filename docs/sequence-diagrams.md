# Diagramas de secuencia

## Mensaje del cliente y respuesta de la IA en streaming

La petición del cliente termina en cuanto el mensaje está guardado; el análisis y la respuesta de la IA
ocurren después, en un worker, y llegan al navegador por SSE.

```mermaid
sequenceDiagram
    autonumber
    actor C as Cliente
    participant W as Angular (nginx)
    participant A as API Java
    participant R as Redis
    participant P as PostgreSQL
    participant AI as Python AI
    participant L as LLM

    W->>A: GET /conversations/{id}/events (SSE, JWT)
    C->>W: Escribe un mensaje
    W->>A: POST /conversations/{id}/messages (X-Idempotency-Key, X-Correlation-Id)
    A->>R: Rate limit · SET NX idempotency
    A->>P: INSERT message · UPDATE conversation · INSERT audit_log
    A-->>W: 201 Created
    A->>R: PUBLISH message.created · XADD ai:jobs ANALYZE_MESSAGE (tras el commit)
    R-->>A: Worker (grupo de consumidores)
    A->>AI: POST /ai/classify · /ai/sentiment
    A->>P: metadata.analysis · ticket automático si corresponde
    A->>R: SET NX ai:processing:{id}
    A->>R: Memoria: conversation:context:{id} (últimos N mensajes)
    A->>AI: POST /ai/chat (stream, org, historial, resumen, intent hint)
    AI->>AI: Guardrails · embedding · búsqueda vectorial · ranking · prompt
    AI->>L: /chat/completions (stream)
    loop Cada fragmento
        L-->>AI: delta
        AI-->>A: event: delta
        A->>R: PUBLISH ai.delta
        R-->>W: ai.delta (SSE)
    end
    AI-->>A: event: done (validado: confianza, fuentes)
    A->>A: HandoffPolicy (confianza, sin contexto, categoría sensible...)
    A->>P: INSERT message (AI, fuentes) · ai_responses · audit_log
    A->>R: PUBLISH message.created · ai.completed · DEL ai:processing:{id}
    R-->>W: Mensaje definitivo de la IA con sus fuentes
```

## Derivación a un humano

```mermaid
sequenceDiagram
    autonumber
    participant A as API Java
    participant AI as Python AI
    participant P as PostgreSQL
    participant R as Redis
    actor G as Agente

    alt Antes de llamar al LLM
        A->>A: HUMAN_REQUEST / categoría sensible / urgencia
    else Después de la respuesta
        A->>AI: POST /ai/chat
        AI-->>A: confianza baja / NO_RELEVANT_CONTEXT repetido
    end
    A->>P: Mensaje SYSTEM (AI_HANDOFF_REQUESTED) · conversation.aiEnabled=false · handoffReason
    A->>P: Ticket AI_HANDOFF (o escala el abierto) · audit AI_HANDOFF
    A->>R: PUBLISH conversation.updated (inQueue=true)
    R-->>G: La conversación aparece en la cola (SSE del inbox)
    G->>A: GET /conversations/{id}/handoff (motivo, confianza, respuestas previas de la IA)
    G->>A: POST /conversations/{id}/assign
    G->>A: POST /conversations/{id}/messages (AGENT)
```

## Servicio de IA caído

```mermaid
sequenceDiagram
    autonumber
    participant A as API Java
    participant AI as Python AI
    actor C as Cliente

    A->>AI: POST /ai/chat (intento 1)
    AI--xA: 503 / timeout
    A->>AI: Retry (backoff exponencial, intento 2)
    AI--xA: 503 / timeout
    A->>AI: Retry (intento 3)
    AI--xA: 503 / timeout
    Note over A: CircuitBreaker registra los fallos;<br/>abierto, las llamadas fallan al instante
    A->>A: Fallback: mensaje SYSTEM "AI temporarily unavailable"
    A->>A: Derivación AI_UNAVAILABLE + ticket
    A-->>C: Un agente humano continúa la conversación
```

## Indexación de la base de conocimiento

```mermaid
sequenceDiagram
    autonumber
    actor AD as Admin
    participant A as API Java
    participant R as Redis
    participant AI as Python AI
    participant V as pgvector

    AD->>A: POST /knowledge (PUBLISHED)
    A->>A: indexStatus=PENDING
    A->>R: XADD INDEX_DOCUMENT (tras el commit)
    R-->>A: Worker
    A->>AI: POST /knowledge/embed
    AI->>AI: Chunking · embeddings
    AI->>V: DELETE + INSERT fragmentos (una transacción)
    AI-->>A: chunk_count, embedding_model
    A->>A: ¿Cambió el documento? -> reindexar · si no, INDEXED
```
