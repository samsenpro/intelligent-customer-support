package com.supportmind.conversation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID>,
        JpaSpecificationExecutor<Conversation> {

    Optional<Conversation> findByIdAndOrganizationId(UUID id, UUID organizationId);

    @Query("""
            SELECT c.assignedAgentId, COUNT(c) FROM Conversation c
            WHERE c.organizationId = :organizationId AND c.assignedAgentId IS NOT NULL
              AND c.status NOT IN (com.supportmind.conversation.ConversationStatus.RESOLVED,
                                   com.supportmind.conversation.ConversationStatus.CLOSED)
            GROUP BY c.assignedAgentId""")
    List<Object[]> activeCountsByAgent(@Param("organizationId") UUID organizationId);

    /** Conversaciones activas asignadas a cada agente de la organización. */
    default Map<UUID, Long> countActiveByAgent(UUID organizationId) {
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : activeCountsByAgent(organizationId)) {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        return counts;
    }
}
