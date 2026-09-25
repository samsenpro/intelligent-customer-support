package com.supportmind.knowledge;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class KnowledgeDtos {

    private KnowledgeDtos() {
    }

    public record KnowledgeRequest(
            @Schema(example = "Refund Policy") @NotBlank @Size(max = 200) String title,
            @Schema(description = "Plain text or Markdown") @NotBlank @Size(max = 200_000) String content,
            @NotNull KnowledgeType type,
            @Schema(description = "Only PUBLISHED documents are used by the AI. Default DRAFT") KnowledgeStatus status) {
    }

    public record KnowledgeSummaryResponse(UUID id, String title, KnowledgeType type, KnowledgeStatus status,
                                           IndexStatus indexStatus, int chunkCount, int contentLength,
                                           Instant indexedAt, Instant createdAt, Instant updatedAt) {

        static KnowledgeSummaryResponse from(KnowledgeDocument d) {
            return new KnowledgeSummaryResponse(d.getId(), d.getTitle(), d.getType(), d.getStatus(), d.getIndexStatus(),
                    d.getChunkCount(), d.getContent().length(), d.getIndexedAt(), d.getCreatedAt(), d.getUpdatedAt());
        }
    }

    public record KnowledgeResponse(UUID id, String title, String content, KnowledgeType type, KnowledgeStatus status,
                                    IndexStatus indexStatus, int chunkCount, String embeddingModel, Instant indexedAt,
                                    String indexError, UUID createdBy, Instant createdAt, Instant updatedAt) {

        static KnowledgeResponse from(KnowledgeDocument d) {
            return new KnowledgeResponse(d.getId(), d.getTitle(), d.getContent(), d.getType(), d.getStatus(),
                    d.getIndexStatus(), d.getChunkCount(), d.getEmbeddingModel(), d.getIndexedAt(), d.getIndexError(),
                    d.getCreatedBy(), d.getCreatedAt(), d.getUpdatedAt());
        }
    }

    public record SearchResponse(String query, String embeddingModel, List<SearchHitResponse> results) {
    }

    public record SearchHitResponse(UUID documentId, String title, String documentType, int chunkIndex,
                                    String content, double score) {
    }
}
