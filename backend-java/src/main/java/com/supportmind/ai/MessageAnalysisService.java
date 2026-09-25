package com.supportmind.ai;

import com.supportmind.ai.client.AiContract.Classification;
import com.supportmind.ai.client.AiContract.Sentiment;
import com.supportmind.ai.client.AiServiceClient;
import com.supportmind.ai.client.AiServiceException;
import com.supportmind.common.AfterCommit;
import com.supportmind.common.OptimisticRetry;
import com.supportmind.conversation.Conversation;
import com.supportmind.conversation.ConversationRepository;
import com.supportmind.message.Message;
import com.supportmind.message.MessageRepository;
import com.supportmind.organization.OrganizationRepository;
import com.supportmind.realtime.RealtimePublisher;
import com.supportmind.ticket.TicketAutomation;
import com.supportmind.ticket.TicketSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Procesamiento asíncrono de cada mensaje del cliente:
 * <pre>
 * Mensaje -> Python (intención -> categoría -> prioridad; sentimiento) -> metadatos del mensaje
 *         -> ticket automático si corresponde -> respuesta de la IA si la conversación la atiende ella
 * </pre>
 */
@Service
public class MessageAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(MessageAnalysisService.class);

    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final OrganizationRepository organizationRepository;
    private final AiServiceClient aiClient;
    private final TicketAutomation ticketAutomation;
    private final AiReplyService replyService;
    private final RealtimePublisher realtime;
    private final OptimisticRetry retry;
    private final TransactionTemplate transaction;

    public MessageAnalysisService(MessageRepository messageRepository, ConversationRepository conversationRepository,
                                  OrganizationRepository organizationRepository, AiServiceClient aiClient,
                                  TicketAutomation ticketAutomation, AiReplyService replyService,
                                  RealtimePublisher realtime, OptimisticRetry retry,
                                  PlatformTransactionManager transactionManager) {
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
        this.organizationRepository = organizationRepository;
        this.aiClient = aiClient;
        this.ticketAutomation = ticketAutomation;
        this.replyService = replyService;
        this.realtime = realtime;
        this.retry = retry;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public void process(UUID messageId) {
        Message message = messageRepository.findById(messageId).orElse(null);
        if (message == null) {
            return;
        }
        // Idempotente: un job repetido no vuelve a analizar el mensaje
        MessageAnalysis analysis = MessageAnalysis.fromMetadata(message.getMetadata());
        if (analysis == null) {
            analysis = analyze(message);
            if (analysis != null) {
                save(messageId, analysis);
                createTicketIfNeeded(message, analysis);
            }
        }
        replyService.replyAutomatically(message.getConversationId());
    }

    private MessageAnalysis analyze(Message message) {
        try {
            Classification classification = aiClient.classify(message.getContent());
            Sentiment sentiment = null;
            try {
                sentiment = aiClient.sentiment(message.getContent());
            } catch (AiServiceException ex) {
                // El sentimiento es auxiliar: sin él la clasificación sigue siendo útil
                log.info("Sentiment of message {} not available: {}", message.getId(), ex.errorCode());
            }
            return MessageAnalysis.of(classification, sentiment);
        } catch (AiServiceException ex) {
            log.warn("Message {} not analyzed: {}", message.getId(), ex.errorCode());
            return null;
        }
    }

    private void save(UUID messageId, MessageAnalysis analysis) {
        retry.run(() -> transaction.executeWithoutResult(status -> {
            Message message = messageRepository.findById(messageId).orElseThrow();
            message.putMetadata(MessageAnalysis.METADATA_KEY, analysis.toMap());
            messageRepository.save(message);
            Conversation conversation = conversationRepository.findById(message.getConversationId()).orElseThrow();
            conversation.recordAnalysis(analysis.intent(), analysis.category().name(), analysis.sentiment());
            conversationRepository.save(conversation);
            AfterCommit.run(() -> {
                realtime.messageUpdated(conversation, messageId, message.getMetadata());
                realtime.conversationChanged(conversation);
            });
        }));
    }

    private void createTicketIfNeeded(Message message, MessageAnalysis analysis) {
        boolean enabled = conversationRepository.findById(message.getConversationId())
                .flatMap(c -> organizationRepository.findById(c.getOrganizationId()))
                .map(o -> o.getAiSettings().isAutoTicketEnabled()).orElse(false);
        if (enabled && TicketAutomation.requiresTicket(analysis.intent(), analysis.priority())) {
            String line = message.getContent().replaceAll("\\s+", " ").strip();
            ticketAutomation.createOrEscalate(message.getConversationId(),
                    "[" + analysis.intent() + "] " + (line.length() <= 120 ? line : line.substring(0, 119) + "…"),
                    message.getContent(), analysis.priority(), analysis.category(), TicketSource.AUTO_CLASSIFICATION);
        }
    }
}
