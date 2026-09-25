package com.supportmind.ai.jobs;

public enum AiJobType {
    /** Clasificar y analizar el sentimiento de un mensaje del cliente y, si procede, responder con la IA. */
    ANALYZE_MESSAGE,
    /** Respuesta de la IA pedida explícitamente por un agente. */
    AI_REPLY,
    /** Chunking + embeddings de un documento de la base de conocimiento. */
    INDEX_DOCUMENT,
    /** Quitar un documento del vector store (archivado o despublicado). */
    REMOVE_DOCUMENT,
    /** Resumen incremental de una conversación larga. */
    SUMMARIZE_CONVERSATION
}
