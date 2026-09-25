package com.supportmind.knowledge;

import com.supportmind.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * Documento de la base de conocimiento de una organización (políticas, FAQ, manuales...). El texto
 * original vive aquí; sus fragmentos y embeddings, en el vector store del servicio de IA.
 */
@Entity
@Table(name = "knowledge_documents")
public class KnowledgeDocument extends BaseEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private KnowledgeType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private KnowledgeStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "index_status", nullable = false, length = 20)
    private IndexStatus indexStatus = IndexStatus.NOT_INDEXED;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    @Column(name = "embedding_model", length = 100)
    private String embeddingModel;

    @Column(name = "indexed_at")
    private Instant indexedAt;

    @Column(name = "index_error", length = 300)
    private String indexError;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Version
    private long version;

    protected KnowledgeDocument() {
        // JPA
    }

    public KnowledgeDocument(UUID organizationId, String title, String content, KnowledgeType type,
                             KnowledgeStatus status, UUID createdBy) {
        super(UUID.randomUUID());
        this.organizationId = organizationId;
        this.createdBy = createdBy;
        update(title, content, type, status);
    }

    public void update(String title, String content, KnowledgeType type, KnowledgeStatus status) {
        this.title = title.strip();
        this.content = content.strip();
        this.type = type;
        this.status = status;
    }

    /** ¿Tiene (o puede tener) fragmentos en el vector store? */
    public boolean hasIndexedContent() {
        return indexStatus != IndexStatus.NOT_INDEXED;
    }

    public void markPendingIndex() {
        this.indexStatus = IndexStatus.PENDING;
        this.indexError = null;
    }

    public void markIndexed(int chunkCount, String embeddingModel, Instant at) {
        this.indexStatus = IndexStatus.INDEXED;
        this.chunkCount = chunkCount;
        this.embeddingModel = embeddingModel;
        this.indexedAt = at;
        this.indexError = null;
    }

    public void markIndexFailed(String error) {
        this.indexStatus = IndexStatus.FAILED;
        this.indexError = error.length() <= 300 ? error : error.substring(0, 300);
    }

    public void markNotIndexed() {
        this.indexStatus = IndexStatus.NOT_INDEXED;
        this.chunkCount = 0;
        this.indexedAt = null;
        this.indexError = null;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public KnowledgeType getType() {
        return type;
    }

    public KnowledgeStatus getStatus() {
        return status;
    }

    public IndexStatus getIndexStatus() {
        return indexStatus;
    }

    public int getChunkCount() {
        return chunkCount;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public Instant getIndexedAt() {
        return indexedAt;
    }

    public String getIndexError() {
        return indexError;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public long getVersion() {
        return version;
    }
}
