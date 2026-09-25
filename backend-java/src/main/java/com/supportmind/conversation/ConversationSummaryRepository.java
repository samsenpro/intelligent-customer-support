package com.supportmind.conversation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ConversationSummaryRepository extends JpaRepository<ConversationSummary, UUID> {

    Optional<ConversationSummary> findByConversationId(UUID conversationId);
}
