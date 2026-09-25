package com.supportmind.message;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    Optional<Message> findByIdAndConversationId(UUID id, UUID conversationId);

    Page<Message> findByConversationId(UUID conversationId, Pageable pageable);

    /** Los últimos {@code limit} mensajes, del más reciente al más antiguo. */
    @Query(value = "SELECT * FROM messages WHERE conversation_id = :conversationId "
            + "ORDER BY created_at DESC, id DESC LIMIT :limit", nativeQuery = true)
    List<Message> findLatest(@Param("conversationId") UUID conversationId, @Param("limit") int limit);

    /** Mensajes a partir de una posición (orden cronológico), para el resumen incremental. */
    @Query(value = "SELECT * FROM messages WHERE conversation_id = :conversationId "
            + "ORDER BY created_at, id OFFSET :offset LIMIT :limit", nativeQuery = true)
    List<Message> findFrom(@Param("conversationId") UUID conversationId, @Param("offset") int offset,
                           @Param("limit") int limit);

    List<Message> findByConversationIdAndSenderTypeOrderByCreatedAtAsc(UUID conversationId, SenderType senderType);

    /** Último mensaje de cada conversación (vista previa del inbox) en una sola consulta. */
    @Query(value = """
            SELECT DISTINCT ON (conversation_id) * FROM messages
            WHERE conversation_id IN (:conversationIds)
            ORDER BY conversation_id, created_at DESC, id DESC""", nativeQuery = true)
    List<Message> findLastOfEach(@Param("conversationIds") Collection<UUID> conversationIds);
}
