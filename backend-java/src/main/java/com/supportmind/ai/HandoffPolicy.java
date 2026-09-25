package com.supportmind.ai;

import com.supportmind.ai.client.AiContract.ChatResult;
import com.supportmind.conversation.HandoffReason;
import com.supportmind.organization.AiSettings;
import com.supportmind.organization.NoContextAction;
import com.supportmind.ticket.TicketPriority;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Decide cuándo la IA deja la conversación a un humano ({@code AI_HANDOFF_REQUESTED}). Los criterios
 * los configura cada organización:
 * <ul>
 *   <li>el cliente pide explícitamente un humano;</li>
 *   <li>la categoría es sensible (p. ej. SECURITY o LEGAL);</li>
 *   <li>el mensaje o el ticket abierto es urgente;</li>
 *   <li>la confianza de la respuesta queda por debajo del umbral;</li>
 *   <li>la IA no pudo responder varias veces seguidas, o no hay contexto y la organización prefiere derivar.</li>
 * </ul>
 * Es lógica pura (sin estado ni dependencias) para poder probar cada criterio de forma aislada.
 */
@Component
public class HandoffPolicy {

    static final String HUMAN_REQUEST = "HUMAN_REQUEST";

    /** Criterios que no dependen de la respuesta: se evalúan antes de llamar al LLM (ahorra la llamada). */
    public Optional<HandoffReason> beforeReply(AiSettings settings, MessageAnalysis analysis,
                                               TicketPriority openTicketPriority) {
        if (analysis != null) {
            if (HUMAN_REQUEST.equals(analysis.intent())) {
                return Optional.of(HandoffReason.CUSTOMER_REQUESTED_HUMAN);
            }
            if (settings.getSensitiveCategories().contains(analysis.category().name())) {
                return Optional.of(HandoffReason.SENSITIVE_CATEGORY);
            }
        }
        if (settings.isHandoffOnUrgent() && (openTicketPriority == TicketPriority.URGENT
                || (analysis != null && analysis.priority() == TicketPriority.URGENT))) {
            return Optional.of(HandoffReason.URGENT_TICKET);
        }
        return Optional.empty();
    }

    /** Criterios sobre la respuesta generada. */
    public Optional<HandoffReason> afterReply(AiSettings settings, int failedAiAnswers, ChatResult result) {
        if ("BLOCKED".equals(result.status())) {
            return Optional.empty();
        }
        if (result.intent() != null) {
            if (HUMAN_REQUEST.equals(result.intent().intent())) {
                return Optional.of(HandoffReason.CUSTOMER_REQUESTED_HUMAN);
            }
            if (settings.getSensitiveCategories().contains(result.intent().category())) {
                return Optional.of(HandoffReason.SENSITIVE_CATEGORY);
            }
            if (settings.isHandoffOnUrgent() && "URGENT".equals(result.intent().priority())) {
                return Optional.of(HandoffReason.URGENT_TICKET);
            }
        }
        if ("NO_RELEVANT_CONTEXT".equals(result.status())) {
            if (settings.getNoContextAction() == NoContextAction.HANDOFF) {
                return Optional.of(HandoffReason.NO_RELEVANT_CONTEXT);
            }
            return failedAiAnswers + 1 >= settings.getMaxFailedAiAnswers()
                    ? Optional.of(HandoffReason.REPEATED_FAILED_ANSWERS) : Optional.empty();
        }
        if ("AI_HANDOFF_REQUESTED".equals(result.status())
                || result.confidence() < settings.getConfidenceThreshold().doubleValue()) {
            return Optional.of(HandoffReason.LOW_CONFIDENCE);
        }
        return Optional.empty();
    }
}
