package com.supportmind.ai;

import com.supportmind.audit.AuditEvent;
import com.supportmind.audit.AuditService;
import com.supportmind.common.AfterCommit;
import com.supportmind.common.BusinessMetrics;
import com.supportmind.conversation.Conversation;
import com.supportmind.conversation.HandoffReason;
import com.supportmind.message.Message;
import com.supportmind.message.MessageService;
import com.supportmind.message.SenderType;
import com.supportmind.ticket.TicketAutomation;
import com.supportmind.ticket.TicketCategory;
import com.supportmind.ticket.TicketPriority;
import com.supportmind.ticket.TicketSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Derivación de la IA a un humano: AI -> HANDOFF -> Ticket -> cola de agentes.
 * <p>
 * El cliente recibe un aviso del sistema, la conversación deja de ser atendida por la IA y entra en
 * la cola de agentes, y se crea (o escala) un ticket. El agente recibe el motivo, la confianza y las
 * respuestas previas de la IA (ver {@code GET /conversations/{id}/handoff}).
 */
@Service
public class HandoffService {

    public static final String SYSTEM_EVENT = "systemEvent";

    private final MessageService messageService;
    private final TicketAutomation ticketAutomation;
    private final AuditService auditService;
    private final BusinessMetrics metrics;
    private final Clock clock;

    public HandoffService(MessageService messageService, TicketAutomation ticketAutomation, AuditService auditService,
                          BusinessMetrics metrics, Clock clock) {
        this.messageService = messageService;
        this.ticketAutomation = ticketAutomation;
        this.auditService = auditService;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * @param customerMessage último mensaje del cliente (va al ticket)
     * @param aiConfidence    confianza de la IA, o null si ni siquiera respondió
     * @param analysis        clasificación del mensaje, para la prioridad y categoría del ticket
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Message handOff(Conversation conversation, HandoffReason reason, String language, String customerMessage,
                           Double aiConfidence, MessageAnalysis analysis) {
        boolean unavailable = reason == HandoffReason.AI_UNAVAILABLE;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(SYSTEM_EVENT, unavailable ? "AI_UNAVAILABLE" : "AI_HANDOFF_REQUESTED");
        metadata.put("handoffReason", reason.name());
        if (aiConfidence != null) {
            metadata.put("aiConfidence", aiConfidence);
        }
        Message message = messageService.record(conversation, SenderType.SYSTEM, null,
                unavailable ? AssistantTexts.aiUnavailable(language) : AssistantTexts.handoff(language), metadata, null);
        conversation.handOff(reason, clock.instant());
        auditService.record(AuditEvent.AI_HANDOFF, conversation.getOrganizationId(), null, "Conversation",
                conversation.getId(), aiConfidence == null ? Map.of("reason", reason.name())
                        : Map.of("reason", reason.name(), "aiConfidence", aiConfidence));

        TicketPriority priority = ticketPriority(reason, analysis);
        TicketCategory category = analysis != null ? analysis.category() : TicketCategory.GENERAL;
        String intent = analysis != null ? analysis.intent() : "HANDOFF";
        AfterCommit.run(() -> {
            metrics.humanHandoff(reason.name());
            ticketAutomation.createOrEscalate(conversation.getId(),
                    "[" + intent + "] " + oneLine(customerMessage),
                    AssistantTexts.handoffTicketDescription(language, reason, customerMessage),
                    priority, category, TicketSource.AI_HANDOFF);
        });
        return message;
    }

    private static TicketPriority ticketPriority(HandoffReason reason, MessageAnalysis analysis) {
        if (reason == HandoffReason.URGENT_TICKET) {
            return TicketPriority.URGENT;
        }
        TicketPriority base = analysis != null ? analysis.priority() : TicketPriority.MEDIUM;
        // Un cliente que pide un humano o una categoría sensible no deben quedarse en prioridad baja
        if ((reason == HandoffReason.SENSITIVE_CATEGORY || reason == HandoffReason.CUSTOMER_REQUESTED_HUMAN)
                && !base.isHigherThan(TicketPriority.MEDIUM)) {
            return TicketPriority.HIGH;
        }
        return base == TicketPriority.LOW ? TicketPriority.MEDIUM : base;
    }

    private static String oneLine(String text) {
        String line = text.replaceAll("\\s+", " ").strip();
        return line.length() <= 120 ? line : line.substring(0, 119) + "…";
    }
}
