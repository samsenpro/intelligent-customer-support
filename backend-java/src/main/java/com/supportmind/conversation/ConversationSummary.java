package com.supportmind.conversation;

import com.supportmind.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Resumen incremental de una conversación larga. Sustituye a los mensajes antiguos en el contexto
 * que se envía al LLM, así los tokens no crecen con la conversación.
 */
@Entity
@Table(name = "conversation_summaries")
public class ConversationSummary extends BaseEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Column(nullable = false, columnDefinition = "text")
    private String summary;

    /** Cuántos mensajes (en orden cronológico) cubre el resumen. */
    @Column(name = "summarized_message_count", nullable = false)
    private int summarizedMessageCount;

    @Column(nullable = false, length = 20)
    private String strategy;

    protected ConversationSummary() {
        // JPA
    }

    public ConversationSummary(UUID organizationId, UUID conversationId) {
        super(UUID.randomUUID());
        this.organizationId = organizationId;
        this.conversationId = conversationId;
    }

    public void update(String summary, int summarizedMessageCount, String strategy) {
        this.summary = summary;
        this.summarizedMessageCount = summarizedMessageCount;
        this.strategy = strategy;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public String getSummary() {
        return summary;
    }

    public int getSummarizedMessageCount() {
        return summarizedMessageCount;
    }

    public String getStrategy() {
        return strategy;
    }
}
