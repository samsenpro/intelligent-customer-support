package com.supportmind.message;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mensaje de una conversación. El contenido es inmutable; los metadatos se completan después
 * (análisis de la IA: intención, categoría, sentimiento; fuentes y confianza de una respuesta RAG).
 */
@Entity
@Table(name = "messages")
public class Message {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_type", nullable = false, length = 10, updatable = false)
    private SenderType senderType;

    @Column(name = "sender_id", updatable = false)
    private UUID senderId;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> metadata = new LinkedHashMap<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Message() {
        // JPA
    }

    public Message(UUID organizationId, UUID conversationId, SenderType senderType, UUID senderId, String content,
                   Map<String, Object> metadata) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.conversationId = conversationId;
        this.senderType = senderType;
        this.senderId = senderId;
        this.content = content.strip();
        this.metadata = new LinkedHashMap<>(metadata);
    }

    /** Añade o reemplaza una sección de metadatos (p. ej. "analysis"). */
    public void putMetadata(String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(metadata);
        copy.put(key, value);
        // Se asigna un mapa nuevo para que Hibernate detecte el cambio en la columna JSON
        this.metadata = copy;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public SenderType getSenderType() {
        return senderType;
    }

    public UUID getSenderId() {
        return senderId;
    }

    public String getContent() {
        return content;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
