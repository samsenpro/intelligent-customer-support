package com.supportmind.ticket;

import com.supportmind.common.BaseEntity;
import com.supportmind.exception.ApiException;
import com.supportmind.exception.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "tickets")
public class Ticket extends BaseEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "conversation_id", updatable = false)
    private UUID conversationId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "assigned_agent_id")
    private UUID assignedAgentId;

    @Column(nullable = false, length = 200)
    private String subject;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TicketPriority priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TicketStatus status = TicketStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TicketCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private TicketSource source;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Version
    private long version;

    protected Ticket() {
        // JPA
    }

    public Ticket(UUID organizationId, UUID conversationId, UUID customerId, String subject, String description,
                  TicketPriority priority, TicketCategory category, TicketSource source, UUID assignedAgentId) {
        super(UUID.randomUUID());
        this.organizationId = organizationId;
        this.conversationId = conversationId;
        this.customerId = customerId;
        this.subject = subject.strip();
        this.description = description.strip();
        this.priority = priority;
        this.category = category;
        this.source = source;
        this.assignedAgentId = assignedAgentId;
    }

    /**
     * Cambia el estado respetando las transiciones permitidas. Devuelve los cambios aplicados
     * (campo -> [anterior, nuevo]) para el historial.
     */
    public Map<String, String[]> changeStatus(TicketStatus target, Instant at) {
        if (target == status) {
            return Map.of();
        }
        if (!status.canTransitionTo(target)) {
            throw new ApiException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "A ticket cannot change from " + status + " to " + target);
        }
        String previous = status.name();
        status = target;
        if (target == TicketStatus.RESOLVED || (target == TicketStatus.CLOSED && resolvedAt == null)) {
            resolvedAt = at;
        } else if (target != TicketStatus.CLOSED) {
            // Reabierto: deja de contar como resuelto
            resolvedAt = null;
        }
        return Map.of("status", new String[]{previous, target.name()});
    }

    public Map<String, String[]> changePriority(TicketPriority target) {
        if (target == priority) {
            return Map.of();
        }
        String previous = priority.name();
        priority = target;
        return Map.of("priority", new String[]{previous, target.name()});
    }

    public Map<String, String[]> changeCategory(TicketCategory target) {
        if (target == category) {
            return Map.of();
        }
        String previous = category.name();
        category = target;
        return Map.of("category", new String[]{previous, target.name()});
    }

    public Map<String, String[]> assign(UUID agentId) {
        if (agentId == null ? assignedAgentId == null : agentId.equals(assignedAgentId)) {
            return Map.of();
        }
        String previous = assignedAgentId == null ? null : assignedAgentId.toString();
        assignedAgentId = agentId;
        return Map.of("assignedAgentId", new String[]{previous, agentId == null ? null : agentId.toString()});
    }

    public void ensureModifiable() {
        if (status == TicketStatus.CLOSED) {
            throw new ApiException(ErrorCode.INVALID_STATUS_TRANSITION, "The ticket is closed");
        }
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getAssignedAgentId() {
        return assignedAgentId;
    }

    public String getSubject() {
        return subject;
    }

    public String getDescription() {
        return description;
    }

    public TicketPriority getPriority() {
        return priority;
    }

    public TicketStatus getStatus() {
        return status;
    }

    public TicketCategory getCategory() {
        return category;
    }

    public TicketSource getSource() {
        return source;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
