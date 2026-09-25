package com.supportmind.conversation;

import com.supportmind.common.BaseEntity;
import com.supportmind.exception.ApiException;
import com.supportmind.exception.ErrorCode;
import com.supportmind.message.SenderType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * Conversación entre un cliente y la organización, atendida por la IA ({@code aiEnabled}) o por un
 * agente humano. El estado evoluciona con los mensajes: una respuesta deja la conversación esperando
 * al cliente, y un mensaje del cliente la reactiva.
 */
@Entity
@Table(name = "conversations")
public class Conversation extends BaseEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "assigned_agent_id")
    private UUID assignedAgentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConversationStatus status = ConversationStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private Channel channel;

    @Column(length = 200)
    private String subject;

    @Column(name = "ai_enabled", nullable = false)
    private boolean aiEnabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "handoff_reason", length = 40)
    private HandoffReason handoffReason;

    @Column(name = "handoff_at")
    private Instant handoffAt;

    @Column(name = "failed_ai_answers", nullable = false)
    private int failedAiAnswers;

    @Column(name = "last_intent", length = 30)
    private String lastIntent;

    @Column(name = "last_category", length = 20)
    private String lastCategory;

    @Column(name = "last_sentiment", length = 10)
    private String lastSentiment;

    @Column(name = "last_ai_confidence", precision = 4, scale = 3)
    private BigDecimal lastAiConfidence;

    @Column(name = "message_count", nullable = false)
    private int messageCount;

    @Column(name = "first_customer_message_at")
    private Instant firstCustomerMessageAt;

    @Column(name = "first_response_at")
    private Instant firstResponseAt;

    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Version
    private long version;

    protected Conversation() {
        // JPA
    }

    public Conversation(UUID organizationId, UUID customerId, Channel channel, String subject, boolean aiEnabled) {
        super(UUID.randomUUID());
        this.organizationId = organizationId;
        this.customerId = customerId;
        this.channel = channel;
        this.subject = subject == null || subject.isBlank() ? null : subject.strip();
        this.aiEnabled = aiEnabled;
    }

    /** Actualiza contadores, tiempos de respuesta y estado al registrar un mensaje. */
    public void recordMessage(SenderType sender, Instant at) {
        ensureOpenForMessages();
        messageCount++;
        lastMessageAt = at;
        switch (sender) {
            case CUSTOMER -> {
                if (firstCustomerMessageAt == null) {
                    firstCustomerMessageAt = at;
                }
                if (status == ConversationStatus.WAITING_CUSTOMER || status == ConversationStatus.RESOLVED) {
                    // El cliente vuelve a escribir: la conversación se reactiva (y deja de estar resuelta)
                    status = assignedAgentId != null ? ConversationStatus.IN_PROGRESS : ConversationStatus.OPEN;
                    resolvedAt = null;
                }
            }
            case AGENT, AI -> {
                if (firstCustomerMessageAt != null && firstResponseAt == null) {
                    firstResponseAt = at;
                }
                status = ConversationStatus.WAITING_CUSTOMER;
            }
            case SYSTEM -> {
                // Los avisos del sistema no cambian el estado
            }
        }
    }

    public void ensureOpenForMessages() {
        if (status == ConversationStatus.CLOSED) {
            throw new ApiException(ErrorCode.CONVERSATION_CLOSED);
        }
    }

    /** Un agente toma la conversación: desde ese momento la IA deja de responder sola. */
    public void assign(UUID agentId) {
        ensureOpenForMessages();
        this.assignedAgentId = agentId;
        this.aiEnabled = false;
        if (status == ConversationStatus.OPEN) {
            status = ConversationStatus.IN_PROGRESS;
        }
    }

    public void unassign() {
        this.assignedAgentId = null;
        if (status == ConversationStatus.IN_PROGRESS) {
            status = ConversationStatus.OPEN;
        }
    }

    /** La IA deriva a un humano: la conversación entra en la cola de agentes (si nadie la tiene asignada). */
    public void handOff(HandoffReason reason, Instant at) {
        this.aiEnabled = false;
        this.handoffReason = reason;
        this.handoffAt = at;
        if (assignedAgentId == null && status.isActive()) {
            status = ConversationStatus.OPEN;
        }
    }

    /** Devuelve la conversación a la IA (decisión explícita de un agente). */
    public void returnToAi() {
        ensureOpenForMessages();
        this.aiEnabled = true;
        this.failedAiAnswers = 0;
    }

    public void changeStatus(ConversationStatus target, Instant at) {
        if (status == ConversationStatus.CLOSED && target != ConversationStatus.CLOSED) {
            throw new ApiException(ErrorCode.INVALID_STATUS_TRANSITION, "A closed conversation cannot be reopened");
        }
        if (target == ConversationStatus.RESOLVED || target == ConversationStatus.CLOSED) {
            if (resolvedAt == null) {
                resolvedAt = at;
            }
        } else {
            resolvedAt = null;
        }
        status = target;
    }

    public void recordAnalysis(String intent, String category, String sentiment) {
        this.lastIntent = intent;
        this.lastCategory = category;
        this.lastSentiment = sentiment;
    }

    /** Resultado de una respuesta de la IA: las fallidas seguidas cuentan para derivar a un humano. */
    public void recordAiAnswer(BigDecimal confidence, boolean failed) {
        this.lastAiConfidence = confidence == null ? null : confidence.setScale(3, RoundingMode.HALF_UP);
        this.failedAiAnswers = failed ? failedAiAnswers + 1 : 0;
    }

    public boolean isInAgentQueue() {
        return !aiEnabled && assignedAgentId == null && status.isActive();
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getAssignedAgentId() {
        return assignedAgentId;
    }

    public ConversationStatus getStatus() {
        return status;
    }

    public Channel getChannel() {
        return channel;
    }

    public String getSubject() {
        return subject;
    }

    public boolean isAiEnabled() {
        return aiEnabled;
    }

    public HandoffReason getHandoffReason() {
        return handoffReason;
    }

    public Instant getHandoffAt() {
        return handoffAt;
    }

    public int getFailedAiAnswers() {
        return failedAiAnswers;
    }

    public String getLastIntent() {
        return lastIntent;
    }

    public String getLastCategory() {
        return lastCategory;
    }

    public String getLastSentiment() {
        return lastSentiment;
    }

    public BigDecimal getLastAiConfidence() {
        return lastAiConfidence;
    }

    public int getMessageCount() {
        return messageCount;
    }

    public Instant getFirstCustomerMessageAt() {
        return firstCustomerMessageAt;
    }

    public Instant getFirstResponseAt() {
        return firstResponseAt;
    }

    public Instant getLastMessageAt() {
        return lastMessageAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
