package com.supportmind.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supportmind.ai.client.AiContract.HistoryItem;
import com.supportmind.conversation.ConversationSummary;
import com.supportmind.conversation.ConversationSummaryRepository;
import com.supportmind.message.Message;
import com.supportmind.message.MessageRepository;
import com.supportmind.message.SenderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Memoria de conversación que recibe el LLM: el resumen de lo anterior más los últimos N mensajes.
 * <p>
 * Los mensajes recientes se guardan en Redis ({@code conversation:context:{id}}, una lista acotada
 * con TTL) para no leer la base de datos en cada respuesta. Redis es una caché: si la clave no
 * existe o Redis falla, la memoria se reconstruye desde PostgreSQL.
 */
@Service
public class ConversationMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ConversationMemoryService.class);
    private static final String KEY_PREFIX = "conversation:context:";

    /** Añade al final solo si la memoria ya está en caché (si no, se reconstruirá entera al leerla). */
    private static final RedisScript<Long> APPEND = RedisScript.of("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
              redis.call('RPUSH', KEYS[1], ARGV[1])
              redis.call('LTRIM', KEYS[1], -tonumber(ARGV[2]), -1)
              redis.call('PEXPIRE', KEYS[1], ARGV[3])
              return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final MessageRepository messageRepository;
    private final ConversationSummaryRepository summaryRepository;
    private final ObjectMapper objectMapper;
    private final AiProperties properties;

    public ConversationMemoryService(StringRedisTemplate redis, MessageRepository messageRepository,
                                     ConversationSummaryRepository summaryRepository, ObjectMapper objectMapper,
                                     AiProperties properties) {
        this.redis = redis;
        this.messageRepository = messageRepository;
        this.summaryRepository = summaryRepository;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** Memoria actual de la conversación (los mensajes del sistema no forman parte de ella). */
    public ConversationMemory load(UUID conversationId) {
        String summary = summaryRepository.findByConversationId(conversationId)
                .map(ConversationSummary::getSummary).orElse(null);
        List<MemoryEntry> entries = cached(conversationId);
        if (entries == null) {
            entries = rebuild(conversationId);
        }
        return new ConversationMemory(summary, entries);
    }

    public void append(Message message) {
        if (message.getSenderType() == SenderType.SYSTEM) {
            return;
        }
        try {
            String entry = objectMapper.writeValueAsString(MemoryEntry.of(message));
            redis.execute(APPEND, List.of(key(message.getConversationId())), entry,
                    Integer.toString(properties.historyMessages()),
                    Long.toString(properties.contextTtl().toMillis()));
        } catch (JsonProcessingException | DataAccessException ex) {
            // Sin la entrada en caché, la siguiente lectura reconstruye la memoria desde la base de datos
            invalidate(message.getConversationId());
        }
    }

    public void invalidate(UUID conversationId) {
        try {
            redis.delete(key(conversationId));
        } catch (DataAccessException ex) {
            log.warn("Could not invalidate the memory of conversation {}: {}", conversationId, ex.getMessage());
        }
    }

    private List<MemoryEntry> cached(UUID conversationId) {
        try {
            List<String> values = redis.opsForList().range(key(conversationId), 0, -1);
            if (values == null || values.isEmpty()) {
                return null;
            }
            List<MemoryEntry> entries = new ArrayList<>(values.size());
            for (String value : values) {
                entries.add(objectMapper.readValue(value, MemoryEntry.class));
            }
            return deduplicate(entries);
        } catch (JsonProcessingException | DataAccessException ex) {
            log.debug("Conversation memory cache miss for {}: {}", conversationId, ex.getMessage());
            return null;
        }
    }

    private List<MemoryEntry> rebuild(UUID conversationId) {
        List<Message> latest = new ArrayList<>(messageRepository.findLatest(conversationId,
                properties.historyMessages() * 2));
        Collections.reverse(latest);
        List<MemoryEntry> entries = latest.stream().filter(m -> m.getSenderType() != SenderType.SYSTEM)
                .map(MemoryEntry::of).toList();
        entries = entries.subList(Math.max(0, entries.size() - properties.historyMessages()), entries.size());
        try {
            String key = key(conversationId);
            redis.delete(key);
            if (!entries.isEmpty()) {
                List<String> values = new ArrayList<>(entries.size());
                for (MemoryEntry entry : entries) {
                    values.add(objectMapper.writeValueAsString(entry));
                }
                redis.opsForList().rightPushAll(key, values);
                redis.expire(key, properties.contextTtl());
            }
        } catch (JsonProcessingException | DataAccessException ex) {
            log.debug("Conversation memory not cached for {}: {}", conversationId, ex.getMessage());
        }
        return entries;
    }

    /** Una carrera entre reconstrucción y añadido puede repetir un mensaje: se conserva la primera vez. */
    private List<MemoryEntry> deduplicate(List<MemoryEntry> entries) {
        Set<UUID> seen = new LinkedHashSet<>();
        List<MemoryEntry> unique = new ArrayList<>(entries.size());
        for (MemoryEntry entry : entries) {
            if (seen.add(entry.id())) {
                unique.add(entry);
            }
        }
        return unique.subList(Math.max(0, unique.size() - properties.historyMessages()), unique.size());
    }

    private static String key(UUID conversationId) {
        return KEY_PREFIX + conversationId;
    }

    /** Mensaje en la memoria: solo lo que necesita el LLM. */
    public record MemoryEntry(UUID id, SenderType role, String content) {

        static MemoryEntry of(Message message) {
            return new MemoryEntry(message.getId(), message.getSenderType(), message.getContent());
        }
    }

    public record ConversationMemory(String summary, List<MemoryEntry> messages) {

        public List<HistoryItem> history() {
            return messages.stream().map(m -> new HistoryItem(m.role().name(), m.content())).toList();
        }

        /** Último mensaje del cliente en la memoria, si lo hay. */
        public MemoryEntry lastCustomerMessage() {
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i).role() == SenderType.CUSTOMER) {
                    return messages.get(i);
                }
            }
            return null;
        }

        public boolean lastIsFromCustomer() {
            return !messages.isEmpty() && messages.getLast().role() == SenderType.CUSTOMER;
        }
    }
}
