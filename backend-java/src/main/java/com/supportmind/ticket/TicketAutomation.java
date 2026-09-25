package com.supportmind.ticket;

import com.supportmind.conversation.ConversationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Set;
import java.util.UUID;

/**
 * Tickets creados automáticamente por la IA (clasificación de mensajes y derivaciones).
 * <p>
 * Cada creación va en su propia transacción: si dos procesos crean a la vez el ticket de la misma
 * conversación, el índice único deja pasar a uno y el otro reintenta y escala el ticket existente,
 * sin deshacer el resto del trabajo (análisis o derivación ya guardados).
 */
@Component
public class TicketAutomation {

    private static final Logger log = LoggerFactory.getLogger(TicketAutomation.class);

    /** Intenciones que requieren gestión humana aunque la prioridad no sea alta. */
    private static final Set<String> ACTIONABLE_INTENTS = Set.of("REFUND_REQUEST", "PAYMENT_ISSUE", "CANCELLATION",
            "FRAUD_REPORT", "COMPLAINT", "LEGAL_REQUEST", "ACCOUNT_ACCESS");

    private final TicketService ticketService;
    private final ConversationRepository conversationRepository;
    private final TransactionTemplate newTransaction;

    public TicketAutomation(TicketService ticketService, ConversationRepository conversationRepository,
                            PlatformTransactionManager transactionManager) {
        this.ticketService = ticketService;
        this.conversationRepository = conversationRepository;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** ¿El mensaje clasificado necesita un ticket? Intenciones de gestión o prioridad alta/urgente. */
    public static boolean requiresTicket(String intent, TicketPriority priority) {
        return ACTIONABLE_INTENTS.contains(intent) || priority == TicketPriority.HIGH
                || priority == TicketPriority.URGENT;
    }

    public void createOrEscalate(UUID conversationId, String subject, String description, TicketPriority priority,
                                 TicketCategory category, TicketSource source) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                newTransaction.executeWithoutResult(status -> conversationRepository.findById(conversationId)
                        .ifPresent(conversation -> ticketService.createOrEscalate(conversation, truncate(subject, 200),
                                truncate(description, 8000), priority, category, source)));
                return;
            } catch (DataIntegrityViolationException ex) {
                log.info("Concurrent ticket creation for conversation {} (attempt {})", conversationId, attempt);
            }
        }
        log.warn("Automatic ticket for conversation {} could not be created", conversationId);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
