package com.supportmind.ticket;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Cambio de un campo del ticket (historial). Inmutable. */
@Entity
@Table(name = "ticket_events")
public class TicketEvent {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "ticket_id", nullable = false, updatable = false)
    private UUID ticketId;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(nullable = false, length = 30, updatable = false)
    private String field;

    @Column(name = "old_value", length = 200, updatable = false)
    private String oldValue;

    @Column(name = "new_value", length = 200, updatable = false)
    private String newValue;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TicketEvent() {
        // JPA
    }

    public TicketEvent(UUID organizationId, UUID ticketId, UUID actorUserId, String field, String oldValue,
                       String newValue) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.ticketId = ticketId;
        this.actorUserId = actorUserId;
        this.field = field;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }

    public UUID getId() {
        return id;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public String getField() {
        return field;
    }

    public String getOldValue() {
        return oldValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
