# Referencia de la API

La documentación interactiva completa (OpenAPI) está en `http://localhost:8097/swagger-ui.html` con el
stack levantado. Todas las rutas usan el prefijo `/api/v1/` y, salvo registro/login/refresh, requieren
`Authorization: Bearer <access token>`.

## Convenciones

- **Errores**: RFC 7807 (`application/problem+json`) con un `code` estable y el `correlationId`.
  Ejemplos de códigos: `VALIDATION_FAILED`, `INVALID_CREDENTIALS`, `ACCESS_DENIED`, `RESOURCE_NOT_FOUND`,
  `CONVERSATION_CLOSED`, `OPEN_TICKET_ALREADY_EXISTS`, `AI_REPLY_IN_PROGRESS`, `RATE_LIMIT_EXCEEDED`,
  `AI_TEMPORARILY_UNAVAILABLE`.
- **Correlation ID**: se acepta y devuelve `X-Correlation-Id`; viaja de Java a Python y al LLM.
- **Idempotencia**: `POST /conversations/{id}/messages` acepta `X-Idempotency-Key`. Un reintento con la
  misma clave devuelve el mismo mensaje (`200`) sin crearlo dos veces.
- **Rate limiting** (Redis, ventana fija): login/registro por IP, mensajes, operaciones de IA y búsqueda
  semántica por usuario. Respuesta `429` con `Retry-After` y cabeceras `X-RateLimit-*`.
- **Paginación**: `page`, `size` (máx. 100) y `sort` sobre una lista blanca de campos.
- **Multi-tenancy**: un recurso de otra organización responde `404`.

## Endpoints (Java)

| Método | Ruta | Roles | Descripción |
|---|---|---|---|
| POST | `/auth/register` | público | Nueva organización (ADMIN) con `organizationName`, o cliente con `organizationSlug` |
| POST | `/auth/login` | público | Login con email y contraseña |
| POST | `/auth/refresh` | público | Rota el refresh token |
| GET | `/auth/me` | todos | Perfil del usuario (incluye `agentId` o `customerId`) |
| GET / PUT | `/organizations/me`, `/organizations/me/ai-settings` | staff / ADMIN | Organización y configuración de la IA |
| GET / POST | `/organizations/me/users` | ADMIN | Miembros del equipo |
| GET | `/agents`, `/agents/me` · PATCH `/agents/{id}` | staff | Agentes, carga y disponibilidad |
| GET / POST | `/customers` · GET `/customers/{id}` | staff | Clientes |
| GET / POST | `/conversations` | todos | Conversaciones visibles (`view=ALL\|MINE\|QUEUE`); abrir con primer mensaje |
| GET / PATCH | `/conversations/{id}` | todos | Detalle (mensajes, resumen, ticket, derivación); estado o devolver a la IA |
| POST | `/conversations/{id}/assign` | staff | Tomar la conversación o asignarla |
| GET | `/conversations/{id}/handoff` | staff | Motivo, confianza y respuestas previas de la IA |
| GET / POST | `/conversations/{id}/messages` | todos | Mensajes; enviar mensaje |
| GET | `/conversations/{id}/events` | todos | **SSE** de la conversación |
| GET | `/inbox/events` | todos | **SSE** de las conversaciones visibles |
| POST | `/conversations/{id}/ai/reply` | staff | Pide a la IA que responda (asíncrono, `202`) |
| POST | `/conversations/{id}/ai/suggest` | staff | Sugerencia de respuesta con confianza y fuentes |
| GET | `/conversations/{id}/ai/suggestions` | staff | Sugerencias de la conversación |
| POST | `/conversations/{id}/ai/suggestions/{sid}/accept` | staff | Enviar (opcionalmente editada) |
| POST | `/conversations/{id}/ai/suggestions/{sid}/reject` | staff | Rechazar |
| GET / POST | `/tickets` · GET `/tickets/{id}` | todos | Tickets (clientes: solo los suyos) con historial |
| PATCH | `/tickets/{id}` | staff | Estado, prioridad, categoría, asignación |
| GET / POST | `/knowledge` · GET / PUT `/knowledge/{id}` | staff / ADMIN | Base de conocimiento |
| POST | `/knowledge/{id}/reindex` | ADMIN | Reindexar un documento publicado |
| GET | `/knowledge/search?q=` | staff | Búsqueda semántica |
| GET | `/analytics` (+ `/overview`, `/messages-per-day`, `/tickets-by-priority`, `/tickets-by-category`) | ADMIN, SUPERVISOR | Métricas de negocio |
| GET | `/audit-logs` | ADMIN | Auditoría |

## Eventos en tiempo real (SSE)

| Evento | Datos |
|---|---|
| `ready` | La conexión está abierta |
| `message.created` | El mensaje completo (cliente, agente, IA o sistema) |
| `message.updated` | `{messageId, metadata}` (p. ej. el análisis de intención y sentimiento) |
| `ai.started` | `{replyId}`: la IA empieza a responder |
| `ai.delta` | `{replyId, text}`: fragmento de la respuesta (solo en el stream de la conversación) |
| `ai.completed` | `{replyId, status}`: `ANSWERED`, `NO_RELEVANT_CONTEXT`, `AI_HANDOFF_REQUESTED`, `UNAVAILABLE`, `DISCARDED` |
| `conversation.updated` | Estado, asignación, derivación, último análisis |

El servidor envía un comentario de latido cada 20 s. El frontend usa `fetch` (no `EventSource`) para
enviar el JWT en la cabecera; el token nunca va en la URL.

## Servicio de IA (Python, interno)

Solo accesible desde la red de Docker con `X-Internal-Api-Key`. Documentación en `/docs` dentro del
contenedor.

| Método | Ruta | Descripción |
|---|---|---|
| POST | `/api/v1/ai/chat` | Pipeline RAG completo; con `stream: true`, SSE (`meta`, `delta`, `done`, `error`) |
| POST | `/api/v1/ai/classify` | Intención, categoría, prioridad, confianza y estrategia |
| POST | `/api/v1/ai/sentiment` | POSITIVE / NEUTRAL / NEGATIVE con confianza |
| POST | `/api/v1/ai/summarize` | Resumen incremental |
| POST | `/api/v1/ai/suggest` | Borrador para un agente con fuentes |
| POST | `/api/v1/knowledge/embed` | Chunking + embeddings + indexación |
| DELETE | `/api/v1/knowledge/{document_id}?organization_id=` | Retira un documento del vector store |
| POST | `/api/v1/knowledge/search` | Búsqueda semántica en una organización |
| GET | `/api/v1/health` | Estado del LLM, embeddings, vector store y prompts |

Ejemplo de clasificación:

```json
{
  "intent": "REFUND_REQUEST",
  "category": "BILLING",
  "priority": "HIGH",
  "confidence": 0.93,
  "strategy": "rules",
  "urgent_language": false
}
```

Ejemplo de sugerencia (tal como la devuelve Java):

```json
{
  "id": "5d7c...",
  "suggestedResponse": "Hola, puedes solicitar el reembolso dentro de los 30 días siguientes a la entrega...",
  "confidence": 0.91,
  "sources": [{ "documentId": "a1b2...", "title": "Política de reembolsos", "documentType": "POLICY", "score": 0.71 }],
  "status": "PENDING"
}
```
