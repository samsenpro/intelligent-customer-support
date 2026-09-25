package com.supportmind.ai;

import com.supportmind.ai.client.AiContract.HistoryItem;
import com.supportmind.ai.client.AiContract.SummarizeRequest;
import com.supportmind.ai.client.AiContract.Summary;
import com.supportmind.ai.client.AiServiceClient;
import com.supportmind.ai.client.AiServiceException;
import com.supportmind.ai.jobs.AiJob;
import com.supportmind.ai.jobs.AiJobQueue;
import com.supportmind.ai.jobs.AiJobType;
import com.supportmind.common.web.CorrelationId;
import com.supportmind.conversation.Conversation;
import com.supportmind.conversation.ConversationRepository;
import com.supportmind.conversation.ConversationSummary;
import com.supportmind.conversation.ConversationSummaryRepository;
import com.supportmind.message.Message;
import com.supportmind.message.MessageRepository;
import com.supportmind.organization.OrganizationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Resumen automático de conversaciones largas (Conversation -> Python -> LLM -> summary).
 * <p>
 * A partir de N mensajes (configurable por organización) la conversación se resume de forma
 * incremental: el resumen anterior más los mensajes nuevos. El resumen sustituye a los mensajes
 * antiguos en la memoria que recibe el LLM, así los tokens por respuesta no crecen sin límite.
 */
@Service
public class SummaryService {

    private static final Logger log = LoggerFactory.getLogger(SummaryService.class);
    private static final int MAX_MESSAGES_PER_SUMMARY = 200;

    private final ConversationRepository conversationRepository;
    private final ConversationSummaryRepository summaryRepository;
    private final OrganizationRepository organizationRepository;
    private final MessageRepository messageRepository;
    private final ConversationMemoryService memoryService;
    private final AiServiceClient aiClient;
    private final AiJobQueue jobs;
    private final StringRedisTemplate redis;
    private final AiProperties properties;
    private final TransactionTemplate transaction;

    public SummaryService(ConversationRepository conversationRepository,
                          ConversationSummaryRepository summaryRepository,
                          OrganizationRepository organizationRepository, MessageRepository messageRepository,
                          ConversationMemoryService memoryService, AiServiceClient aiClient, AiJobQueue jobs,
                          StringRedisTemplate redis, AiProperties properties,
                          PlatformTransactionManager transactionManager) {
        this.conversationRepository = conversationRepository;
        this.summaryRepository = summaryRepository;
        this.organizationRepository = organizationRepository;
        this.messageRepository = messageRepository;
        this.memoryService = memoryService;
        this.aiClient = aiClient;
        this.jobs = jobs;
        this.redis = redis;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Encola el resumen si la conversación es larga y hay suficientes mensajes nuevos desde el último. */
    public void maybeSchedule(Conversation conversation) {
        int threshold = organizationRepository.findById(conversation.getOrganizationId())
                .map(o -> o.getAiSettings().getSummaryAfterMessages()).orElse(Integer.MAX_VALUE);
        if (conversation.getMessageCount() < threshold) {
            return;
        }
        int summarized = summaryRepository.findByConversationId(conversation.getId())
                .map(ConversationSummary::getSummarizedMessageCount).orElse(0);
        if (conversation.getMessageCount() - summarized < properties.resummarizeEvery()) {
            return;
        }
        try {
            // Una sola petición de resumen en vuelo por conversación
            Boolean first = redis.opsForValue().setIfAbsent(scheduledKey(conversation.getId()), "1",
                    Duration.ofMinutes(10));
            if (Boolean.TRUE.equals(first)) {
                jobs.publish(AiJob.of(AiJobType.SUMMARIZE_CONVERSATION, conversation.getOrganizationId(),
                        conversation.getId(), MDC.get(CorrelationId.MDC_KEY)));
            }
        } catch (DataAccessException ex) {
            log.warn("Summary of conversation {} not scheduled: {}", conversation.getId(), ex.getMessage());
        }
    }

    public void summarize(UUID conversationId) {
        try {
            Conversation conversation = conversationRepository.findById(conversationId).orElse(null);
            if (conversation == null) {
                return;
            }
            ConversationSummary current = summaryRepository.findByConversationId(conversationId).orElse(null);
            int offset = current != null ? current.getSummarizedMessageCount() : 0;
            List<Message> messages = messageRepository.findFrom(conversationId, offset, MAX_MESSAGES_PER_SUMMARY);
            if (messages.size() < 2) {
                return;
            }
            Summary result = aiClient.summarize(new SummarizeRequest(conversation.getOrganizationId(), conversationId,
                    current != null ? current.getSummary() : null,
                    messages.stream().map(m -> new HistoryItem(m.getSenderType().name(), m.getContent())).toList()));
            transaction.executeWithoutResult(status -> {
                ConversationSummary summary = summaryRepository.findByConversationId(conversationId)
                        .orElseGet(() -> new ConversationSummary(conversation.getOrganizationId(), conversationId));
                summary.update(result.summary(), offset + messages.size(), result.strategy());
                summaryRepository.save(summary);
            });
            memoryService.invalidate(conversationId);
            log.info("Conversation {} summarized ({} messages, {})", conversationId, offset + messages.size(),
                    result.strategy());
        } catch (AiServiceException ex) {
            log.warn("Conversation {} not summarized: {}", conversationId, ex.getMessage());
        } finally {
            redis.delete(scheduledKey(conversationId));
        }
    }

    private static String scheduledKey(UUID conversationId) {
        return "ai:summary:scheduled:" + conversationId;
    }
}
