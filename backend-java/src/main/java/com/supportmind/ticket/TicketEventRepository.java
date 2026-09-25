package com.supportmind.ticket;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TicketEventRepository extends JpaRepository<TicketEvent, UUID> {

    List<TicketEvent> findByTicketIdOrderByCreatedAtAsc(UUID ticketId);
}
