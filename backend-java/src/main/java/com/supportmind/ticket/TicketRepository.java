package com.supportmind.ticket;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TicketRepository extends JpaRepository<Ticket, UUID>, JpaSpecificationExecutor<Ticket> {

    Optional<Ticket> findByIdAndOrganizationId(UUID id, UUID organizationId);

    @Query("""
            SELECT t FROM Ticket t
            WHERE t.conversationId = :conversationId
              AND t.status NOT IN (com.supportmind.ticket.TicketStatus.RESOLVED, com.supportmind.ticket.TicketStatus.CLOSED)""")
    Optional<Ticket> findOpenByConversationId(@Param("conversationId") UUID conversationId);

    List<Ticket> findByConversationIdOrderByCreatedAtDesc(UUID conversationId);
}
