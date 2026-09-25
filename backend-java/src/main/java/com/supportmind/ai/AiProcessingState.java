package com.supportmind.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Estado temporal de la IA por conversación en Redis ({@code ai:processing:{id}}): garantiza que solo
 * una respuesta se genera a la vez para cada conversación, aunque haya varias instancias y workers.
 * <p>
 * Si llega otro mensaje mientras la IA responde, queda una marca ({@code ai:pending:{id}}) y, al
 * terminar, la IA responde una sola vez más con el contexto completo (no una vez por mensaje).
 */
@Component
public class AiProcessingState {

    private static final Logger log = LoggerFactory.getLogger(AiProcessingState.class);

    private final StringRedisTemplate redis;
    private final AiProperties properties;

    public AiProcessingState(StringRedisTemplate redis, AiProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public boolean tryStart(UUID conversationId) {
        // El TTL libera la marca aunque la instancia muera a mitad de una respuesta
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(processingKey(conversationId), "1",
                properties.processingTtl()));
    }

    public void markPending(UUID conversationId) {
        redis.opsForValue().set(pendingKey(conversationId), "1", properties.processingTtl());
    }

    /** Consume la marca de pendiente: true si llegó otro mensaje mientras la IA respondía. */
    public boolean takePending(UUID conversationId) {
        return redis.opsForValue().getAndDelete(pendingKey(conversationId)) != null;
    }

    public void finish(UUID conversationId) {
        try {
            redis.delete(processingKey(conversationId));
        } catch (DataAccessException ex) {
            log.warn("Could not clear the AI processing state of {}: {}", conversationId, ex.getMessage());
        }
    }

    public boolean isProcessing(UUID conversationId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(processingKey(conversationId)));
        } catch (DataAccessException ex) {
            return false;
        }
    }

    private static String processingKey(UUID conversationId) {
        return "ai:processing:" + conversationId;
    }

    private static String pendingKey(UUID conversationId) {
        return "ai:pending:" + conversationId;
    }
}
