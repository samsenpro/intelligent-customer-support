package com.supportmind.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * Registro de cada respuesta de la IA a un cliente (también las derivaciones y los fallos). Es la
 * base de las métricas de negocio: tasa de resolución, confianza media y tasa de escalamiento.
 */
@Entity
@Table(name = "ai_responses")
public class AiResponse {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Column(name = "message_id", updatable = false)
    private UUID messageId;

    @Column(nullable = false, length = 30, updatable = false)
    private String status;

    @Column(nullable = false, precision = 4, scale = 3, updatable = false)
    private BigDecimal confidence;

    @Column(length = 30, updatable = false)
    private String intent;

    @Column(length = 100, updatable = false)
    private String model;

    @Column(name = "prompt_version", length = 60, updatable = false)
    private String promptVersion;

    @Column(name = "handoff_reason", length = 40, updatable = false)
    private String handoffReason;

    @Column(name = "latency_ms", nullable = false, updatable = false)
    private int latencyMs;

    @Column(nullable = false, updatable = false)
    private boolean degraded;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AiResponse() {
        // JPA
    }

    public AiResponse(UUID organizationId, UUID conversationId, UUID messageId, String status, double confidence,
                      String intent, String model, String promptVersion, String handoffReason, long latencyMs,
                      boolean degraded) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.conversationId = conversationId;
        this.messageId = messageId;
        this.status = status;
        this.confidence = BigDecimal.valueOf(Math.max(0, Math.min(1, confidence))).setScale(3, RoundingMode.HALF_UP);
        this.intent = intent;
        this.model = model;
        this.promptVersion = promptVersion;
        this.handoffReason = handoffReason;
        this.latencyMs = (int) Math.min(Integer.MAX_VALUE, Math.max(0, latencyMs));
        this.degraded = degraded;
    }

    public UUID getId() {
        return id;
    }

    public String getStatus() {
        return status;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public String getHandoffReason() {
        return handoffReason;
    }
}
