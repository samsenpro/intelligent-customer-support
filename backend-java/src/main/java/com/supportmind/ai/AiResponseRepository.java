package com.supportmind.ai;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AiResponseRepository extends JpaRepository<AiResponse, UUID> {

    List<AiResponse> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);
}
