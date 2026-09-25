package com.supportmind.agent;

import com.supportmind.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Perfil de atención de un miembro del equipo (ADMIN, SUPERVISOR o AGENT): es a quien se asignan
 * conversaciones y tickets.
 */
@Entity
@Table(name = "agents")
public class Agent extends BaseEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AgentStatus status = AgentStatus.OFFLINE;

    @Column(name = "max_active_conversations", nullable = false)
    private int maxActiveConversations = 10;

    protected Agent() {
        // JPA
    }

    public Agent(UUID organizationId, UUID userId, String displayName) {
        super(UUID.randomUUID());
        this.organizationId = organizationId;
        this.userId = userId;
        this.displayName = displayName;
    }

    public void update(AgentStatus status, Integer maxActiveConversations) {
        if (status != null) {
            this.status = status;
        }
        if (maxActiveConversations != null) {
            this.maxActiveConversations = maxActiveConversations;
        }
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public AgentStatus getStatus() {
        return status;
    }

    public int getMaxActiveConversations() {
        return maxActiveConversations;
    }
}
