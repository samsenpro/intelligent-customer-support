package com.supportmind.ai;

import com.supportmind.common.BaseEntity;
import com.supportmind.exception.ApiException;
import com.supportmind.exception.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Borrador de respuesta que la IA propone a un agente. Nunca se envía solo: el agente lo acepta
 * (tal cual o editado) o lo rechaza.
 */
@Entity
@Table(name = "ai_suggestions")
public class AiSuggestion extends BaseEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String content;

    @Column(nullable = false, precision = 4, scale = 3, updatable = false)
    private BigDecimal confidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private List<Map<String, Object>> sources;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SuggestionStatus status = SuggestionStatus.PENDING;

    @Column(length = 100, updatable = false)
    private String model;

    @Column(name = "final_message_id")
    private UUID finalMessageId;

    @Version
    private long version;

    protected AiSuggestion() {
        // JPA
    }

    public AiSuggestion(UUID organizationId, UUID conversationId, UUID requestedBy, String content, double confidence,
                        List<Map<String, Object>> sources, String model) {
        super(UUID.randomUUID());
        this.organizationId = organizationId;
        this.conversationId = conversationId;
        this.requestedBy = requestedBy;
        this.content = content;
        this.confidence = BigDecimal.valueOf(confidence).setScale(3, RoundingMode.HALF_UP);
        this.sources = List.copyOf(sources);
        this.model = model;
    }

    public void accept(UUID messageId, boolean edited) {
        ensurePending();
        this.status = edited ? SuggestionStatus.EDITED : SuggestionStatus.ACCEPTED;
        this.finalMessageId = messageId;
    }

    public void reject() {
        ensurePending();
        this.status = SuggestionStatus.REJECTED;
    }

    private void ensurePending() {
        if (status != SuggestionStatus.PENDING) {
            throw new ApiException(ErrorCode.SUGGESTION_ALREADY_REVIEWED);
        }
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public UUID getRequestedBy() {
        return requestedBy;
    }

    public String getContent() {
        return content;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public List<Map<String, Object>> getSources() {
        return sources;
    }

    public SuggestionStatus getStatus() {
        return status;
    }

    public String getModel() {
        return model;
    }

    public UUID getFinalMessageId() {
        return finalMessageId;
    }
}
