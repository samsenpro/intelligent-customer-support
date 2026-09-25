package com.supportmind.knowledge;

/** Solo los documentos PUBLISHED están en el vector store y la IA puede usarlos para responder. */
public enum KnowledgeStatus {
    DRAFT,
    PUBLISHED,
    ARCHIVED
}
