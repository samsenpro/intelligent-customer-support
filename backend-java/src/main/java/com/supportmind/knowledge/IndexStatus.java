package com.supportmind.knowledge;

/** Estado del documento en el vector store (chunks + embeddings). */
public enum IndexStatus {
    NOT_INDEXED,
    PENDING,
    INDEXED,
    FAILED
}
