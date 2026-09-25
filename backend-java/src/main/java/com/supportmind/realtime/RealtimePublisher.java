package com.supportmind.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supportmind.conversation.Conversation;
import com.supportmind.conversation.ConversationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Publica eventos en tiempo real en Redis Pub/Sub. Cada instancia de la API los recibe y los reenvía
 * a sus conexiones SSE: un cliente conectado a la instancia A ve la respuesta que genera la B.
 * <p>
 * Los eventos son una notificación, no la fuente de verdad: si se pierde uno (Redis caído), la
 * interfaz vuelve a leer la conversación por REST.
 */
@Component
public class RealtimePublisher {

    private static final Logger log = LoggerFactory.getLogger(RealtimePublisher.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ConversationRepository conversationRepository;
    private final RealtimeProperties properties;

    public RealtimePublisher(StringRedisTemplate redis, ObjectMapper objectMapper,
                             ConversationRepository conversationRepository, RealtimeProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.conversationRepository = conversationRepository;
        this.properties = properties;
    }

    public void messageCreated(Conversation conversation, Object message) {
        publish("message.created", conversation, message);
    }

    public void messageUpdated(Conversation conversation, UUID messageId, Map<String, Object> metadata) {
        publish("message.updated", conversation, Map.of("messageId", messageId, "metadata", metadata));
    }

    public void aiStarted(Conversation conversation, UUID replyId) {
        publish("ai.started", conversation, Map.of("replyId", replyId));
    }

    public void aiDelta(Conversation conversation, UUID replyId, String text) {
        publish("ai.delta", conversation, Map.of("replyId", replyId, "text", text));
    }

    public void aiCompleted(Conversation conversation, UUID replyId, String status) {
        publish("ai.completed", conversation, Map.of("replyId", replyId, "status", status));
    }

    public void conversationChanged(Conversation conversation) {
        publish("conversation.updated", conversation, summary(conversation));
    }

    /** Variante para quien solo conoce el ID (p. ej. un ticket): relee la conversación ya confirmada. */
    public void conversationChanged(UUID organizationId, UUID conversationId) {
        conversationRepository.findByIdAndOrganizationId(conversationId, organizationId)
                .ifPresent(this::conversationChanged);
    }

    private void publish(String type, Conversation conversation, Object payload) {
        RealtimeEvent event = new RealtimeEvent(type, conversation.getOrganizationId(), conversation.getId(),
                conversation.getCustomerId(), conversation.getAssignedAgentId(), conversation.isInAgentQueue(),
                objectMapper.valueToTree(payload));
        try {
            redis.convertAndSend(properties.channel(), objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException | DataAccessException ex) {
            log.warn("Realtime event {} for conversation {} not published: {}", type, conversation.getId(),
                    ex.getMessage());
        }
    }

    private static Map<String, Object> summary(Conversation c) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", c.getId());
        data.put("status", c.getStatus());
        data.put("aiEnabled", c.isAiEnabled());
        data.put("assignedAgentId", c.getAssignedAgentId());
        data.put("handoffReason", c.getHandoffReason());
        data.put("lastIntent", c.getLastIntent());
        data.put("lastCategory", c.getLastCategory());
        data.put("lastSentiment", c.getLastSentiment());
        data.put("lastAiConfidence", c.getLastAiConfidence());
        data.put("messageCount", c.getMessageCount());
        data.put("lastMessageAt", c.getLastMessageAt());
        data.put("updatedAt", c.getUpdatedAt());
        return data;
    }
}
