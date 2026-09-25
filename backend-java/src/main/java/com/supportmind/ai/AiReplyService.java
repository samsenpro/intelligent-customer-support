package com.supportmind.ai;

import com.supportmind.ai.ConversationMemoryService.ConversationMemory;
import com.supportmind.ai.ConversationMemoryService.MemoryEntry;
import com.supportmind.ai.client.AiContract.ChatRequest;
import com.supportmind.ai.client.AiContract.ChatResult;
import com.supportmind.ai.client.AiContract.ChatSettings;
import com.supportmind.ai.client.AiContract.IntentHint;
import com.supportmind.ai.client.AiContract.Source;
import com.supportmind.ai.client.AiServiceClient;
import com.supportmind.ai.client.AiServiceException;
import com.supportmind.audit.AuditEvent;
import com.supportmind.audit.AuditService;
import com.supportmind.common.AfterCommit;
import com.supportmind.common.BusinessMetrics;
import com.supportmind.common.OptimisticRetry;
import com.supportmind.conversation.Conversation;
import com.supportmind.conversation.ConversationRepository;
import com.supportmind.conversation.ConversationStatus;
import com.supportmind.conversation.HandoffReason;
import com.supportmind.message.Message;
import com.supportmind.message.MessageRepository;
import com.supportmind.message.MessageService;
import com.supportmind.message.SenderType;
import com.supportmind.organization.AiSettings;
import com.supportmind.organization.Organization;
import com.supportmind.organization.OrganizationRepository;
import com.supportmind.realtime.RealtimePublisher;
import com.supportmind.ticket.Ticket;
import com.supportmind.ticket.TicketPriority;
import com.supportmind.ticket.TicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Respuesta de la IA a un cliente:
 * <pre>
 * Cliente -> Spring Boot -> Python (intención, embedding, búsqueda vectorial, contexto, LLM) -> Spring Boot -> Cliente
 * </pre>
 * <ol>
 *   <li>Carga el contexto: memoria de la conversación (resumen + últimos N mensajes) y configuración.</li>
 *   <li>Aplica los criterios de escalamiento que no necesitan al LLM (humano pedido, categoría sensible, urgencia).</li>
 *   <li>Pide la respuesta al servicio de IA en streaming y reenvía cada fragmento por SSE.</li>
 *   <li>Decide si la respuesta se envía o se deriva a un humano (confianza, sin contexto, fallos repetidos).</li>
 *   <li>Si el servicio de IA no responde (reintentos agotados, circuito abierto): "AI temporarily
 *       unavailable" y la conversación pasa a un agente humano.</li>
 * </ol>
 * La IA nunca responde en nombre de un agente: solo en conversaciones que atiende ella
 * ({@code aiEnabled}) o cuando un agente se lo pide explícitamente.
 */
@Service
public class AiReplyService {

    private static final Logger log = LoggerFactory.getLogger(AiReplyService.class);

    private final ConversationRepository conversationRepository;
    private final OrganizationRepository organizationRepository;
    private final MessageRepository messageRepository;
    private final TicketRepository ticketRepository;
    private final ConversationMemoryService memoryService;
    private final AiProcessingState processingState;
    private final AiServiceClient aiClient;
    private final HandoffPolicy policy;
    private final HandoffService handoffService;
    private final MessageService messageService;
    private final AiResponseRepository aiResponseRepository;
    private final SummaryService summaryService;
    private final RealtimePublisher realtime;
    private final AuditService auditService;
    private final BusinessMetrics metrics;
    private final AiProperties properties;
    private final OptimisticRetry retry;
    private final TransactionTemplate transaction;

    public AiReplyService(ConversationRepository conversationRepository, OrganizationRepository organizationRepository,
                          MessageRepository messageRepository, TicketRepository ticketRepository,
                          ConversationMemoryService memoryService, AiProcessingState processingState,
                          AiServiceClient aiClient, HandoffPolicy policy, HandoffService handoffService,
                          MessageService messageService, AiResponseRepository aiResponseRepository,
                          SummaryService summaryService, RealtimePublisher realtime, AuditService auditService,
                          BusinessMetrics metrics, AiProperties properties, OptimisticRetry retry,
                          PlatformTransactionManager transactionManager) {
        this.conversationRepository = conversationRepository;
        this.organizationRepository = organizationRepository;
        this.messageRepository = messageRepository;
        this.ticketRepository = ticketRepository;
        this.memoryService = memoryService;
        this.processingState = processingState;
        this.aiClient = aiClient;
        this.policy = policy;
        this.handoffService = handoffService;
        this.messageService = messageService;
        this.aiResponseRepository = aiResponseRepository;
        this.summaryService = summaryService;
        this.realtime = realtime;
        this.auditService = auditService;
        this.metrics = metrics;
        this.properties = properties;
        this.retry = retry;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Respuesta automática a un mensaje del cliente (conversaciones atendidas por la IA). */
    public void replyAutomatically(UUID conversationId) {
        run(conversationId, false);
    }

    /** Respuesta pedida por un agente, aunque la conversación la atienda un humano. */
    public void replyOnDemand(UUID conversationId) {
        run(conversationId, true);
    }

    private void run(UUID conversationId, boolean onDemand) {
        if (!processingState.tryStart(conversationId)) {
            // Otra respuesta en curso: al terminar responderá una vez más con este mensaje incluido
            processingState.markPending(conversationId);
            return;
        }
        try {
            boolean manual = onDemand;
            do {
                replyOnce(conversationId, manual);
                manual = false;
            } while (processingState.takePending(conversationId));
        } finally {
            processingState.finish(conversationId);
        }
    }

    private void replyOnce(UUID conversationId, boolean onDemand) {
        ReplyContext context = transaction.execute(status -> loadContext(conversationId, onDemand));
        if (context == null) {
            return;
        }
        String language = AssistantTexts.language(context.customerMessage().content());

        Optional<HandoffReason> early = policy.beforeReply(context.settings(), context.analysis(),
                context.openTicketPriority());
        if (early.isPresent()) {
            log.info("Conversation {} handed off before calling the LLM: {}", conversationId, early.get());
            persistHandoff(context, early.get(), language, null, null, UUID.randomUUID());
            return;
        }

        UUID replyId = UUID.randomUUID();
        realtime.aiStarted(context.conversation(), replyId);
        ChatResult result;
        try {
            result = aiClient.chat(chatRequest(context), delta -> realtime.aiDelta(context.conversation(), replyId, delta));
        } catch (AiServiceException ex) {
            log.warn("AI reply for conversation {} failed ({}): human handoff", conversationId, ex.errorCode());
            persistHandoff(context, HandoffReason.AI_UNAVAILABLE, language, null, null, replyId);
            return;
        }
        Optional<HandoffReason> handoff = policy.afterReply(context.settings(),
                context.conversation().getFailedAiAnswers(), result);
        if (handoff.isPresent()) {
            persistHandoff(context, handoff.get(), result.language() != null ? result.language() : language,
                    result.confidence(), result, replyId);
        } else {
            persistAnswer(context, result, replyId);
        }
    }

    private ReplyContext loadContext(UUID conversationId, boolean onDemand) {
        Conversation conversation = conversationRepository.findById(conversationId).orElse(null);
        if (conversation == null || conversation.getStatus() == ConversationStatus.CLOSED) {
            return null;
        }
        Organization organization = organizationRepository.findById(conversation.getOrganizationId()).orElseThrow();
        AiSettings settings = organization.getAiSettings();
        if (!onDemand && (!conversation.isAiEnabled() || !settings.isAutoReplyEnabled())) {
            return null;
        }
        ConversationMemory memory = memoryService.load(conversationId);
        MemoryEntry customerMessage = memory.lastCustomerMessage();
        // Sin mensaje del cliente pendiente de respuesta no hay nada que contestar (evita responder dos veces)
        if (customerMessage == null || (!onDemand && !memory.lastIsFromCustomer())) {
            return null;
        }
        MessageAnalysis analysis = messageRepository.findById(customerMessage.id())
                .map(message -> MessageAnalysis.fromMetadata(message.getMetadata())).orElse(null);
        TicketPriority openTicketPriority = ticketRepository.findOpenByConversationId(conversationId)
                .map(Ticket::getPriority).orElse(null);
        return new ReplyContext(conversation, organization.getName(), settings, memory, customerMessage, analysis,
                openTicketPriority, onDemand);
    }

    private ChatRequest chatRequest(ReplyContext context) {
        MessageAnalysis analysis = context.analysis();
        IntentHint hint = analysis == null ? null : new IntentHint(analysis.intent(), analysis.category().name(),
                analysis.priority().name(), analysis.confidence(), analysis.strategy());
        AiSettings settings = context.settings();
        return new ChatRequest(context.conversation().getOrganizationId(), context.organizationName(),
                context.conversation().getId(), context.customerMessage().content(), context.memory().history(),
                context.memory().summary(),
                new ChatSettings(settings.getConfidenceThreshold().doubleValue(), settings.getNoContextAction().name(),
                        properties.topK()),
                hint, true);
    }

    private void persistAnswer(ReplyContext context, ChatResult result, UUID replyId) {
        // Reintento: el cliente o un agente pueden modificar la conversación mientras la IA responde
        boolean saved = Boolean.TRUE.equals(retry.execute(() -> transaction.execute(status -> {
            Conversation conversation = conversationRepository.findById(context.conversation().getId()).orElseThrow();
            if (!context.onDemand() && !conversation.isAiEnabled()) {
                // Un agente tomó la conversación mientras la IA respondía: su respuesta ya no se envía
                return false;
            }
            Message message = messageService.record(conversation, SenderType.AI, null, result.answer(),
                    Map.of("ai", aiMetadata(result, replyId)), null);
            conversation.recordAiAnswer(BigDecimal.valueOf(result.confidence()),
                    "NO_RELEVANT_CONTEXT".equals(result.status()));
            aiResponseRepository.save(new AiResponse(conversation.getOrganizationId(), conversation.getId(),
                    message.getId(), result.status(), result.confidence(), intentOf(result), result.model(),
                    result.promptVersion(), null, result.latencyMs(), result.degraded()));
            auditService.record(AuditEvent.AI_RESPONSE_GENERATED, conversation.getOrganizationId(), null, "Message",
                    message.getId(), Map.of("status", result.status(), "confidence", result.confidence(),
                            "sources", result.sources() == null ? 0 : result.sources().size()));
            AfterCommit.run(() -> summaryService.maybeSchedule(conversation));
            return true;
        })));
        realtime.aiCompleted(context.conversation(), replyId, saved ? result.status() : "DISCARDED");
        if (saved) {
            metrics.aiResponse(result.status());
        }
    }

    private void persistHandoff(ReplyContext context, HandoffReason reason, String language, Double confidence,
                                ChatResult result, UUID replyId) {
        String status = reason == HandoffReason.AI_UNAVAILABLE ? "UNAVAILABLE" : "AI_HANDOFF_REQUESTED";
        retry.run(() -> transaction.executeWithoutResult(tx -> {
            Conversation conversation = conversationRepository.findById(context.conversation().getId()).orElseThrow();
            if (!context.onDemand() && !conversation.isAiEnabled()) {
                return;
            }
            Message message = handoffService.handOff(conversation, reason, language,
                    context.customerMessage().content(), confidence, context.analysis());
            if (confidence != null) {
                conversation.recordAiAnswer(BigDecimal.valueOf(confidence), true);
            }
            aiResponseRepository.save(new AiResponse(conversation.getOrganizationId(), conversation.getId(),
                    message.getId(), status, confidence != null ? confidence : 0, result != null ? intentOf(result)
                    : context.analysis() != null ? context.analysis().intent() : null,
                    result != null ? result.model() : null, result != null ? result.promptVersion() : null,
                    reason.name(), result != null ? result.latencyMs() : 0, result != null && result.degraded()));
        }));
        realtime.aiCompleted(context.conversation(), replyId, status);
        metrics.aiResponse(status);
    }

    private static Map<String, Object> aiMetadata(ChatResult result, UUID replyId) {
        Map<String, Object> ai = new LinkedHashMap<>();
        ai.put("replyId", replyId.toString());
        ai.put("status", result.status());
        ai.put("confidence", result.confidence());
        ai.put("sources", sources(result.sources()));
        ai.put("model", result.model());
        ai.put("promptVersion", result.promptVersion());
        ai.put("degraded", result.degraded());
        ai.put("latencyMs", result.latencyMs());
        ai.put("language", result.language());
        if (result.intent() != null) {
            ai.put("intent", result.intent().intent());
        }
        if (result.validation() != null) {
            ai.put("validationIssues", result.validation().issues());
        }
        return ai;
    }

    static List<Map<String, Object>> sources(List<Source> sources) {
        if (sources == null) {
            return List.of();
        }
        return sources.stream().map(s -> Map.<String, Object>of("documentId", s.documentId().toString(),
                "title", s.title(), "documentType", s.documentType(), "score", s.score())).toList();
    }

    private static String intentOf(ChatResult result) {
        return result.intent() != null ? result.intent().intent() : null;
    }

    private record ReplyContext(Conversation conversation, String organizationName, AiSettings settings,
                                ConversationMemory memory, MemoryEntry customerMessage, MessageAnalysis analysis,
                                TicketPriority openTicketPriority, boolean onDemand) {
    }
}
