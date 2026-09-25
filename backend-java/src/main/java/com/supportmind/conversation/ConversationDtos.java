package com.supportmind.conversation;

import com.supportmind.common.Refs.AgentRef;
import com.supportmind.common.Refs.CustomerRef;
import com.supportmind.message.MessageDtos.MessageResponse;
import com.supportmind.message.SenderType;
import com.supportmind.ticket.TicketDtos.TicketResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ConversationDtos {

    private ConversationDtos() {
    }

    public enum View {
        /** Todas las conversaciones visibles para el usuario. */
        ALL,
        /** Las asignadas al usuario. */
        MINE,
        /** Derivadas a humanos y sin asignar (cola de agentes). */
        QUEUE
    }

    public record CreateConversationRequest(
            @Schema(description = "Required for staff; customers always open conversations for themselves")
            UUID customerId,
            @Schema(description = "Default WEB") Channel channel,
            @Schema(example = "Problem with my order") @Size(max = 200) String subject,
            @Schema(description = "Optional first message of the customer") @Size(max = 4000) String message,
            @Schema(description = "Whether the AI answers automatically. Default: the organization setting")
            Boolean aiEnabled) {
    }

    @Schema(description = "Staff: any status and aiEnabled (return the conversation to the AI). "
            + "Customers: only status RESOLVED.")
    public record UpdateConversationRequest(ConversationStatus status, Boolean aiEnabled) {
    }

    public record AssignConversationRequest(
            @Schema(description = "Agent to assign. Omitted: the authenticated user takes the conversation")
            UUID agentId) {
    }

    public record ConversationResponse(UUID id, CustomerRef customer, AgentRef assignedAgent,
                                       ConversationStatus status, Channel channel, String subject,
                                       boolean aiEnabled, HandoffReason handoffReason, Instant handoffAt,
                                       String lastIntent, String lastCategory, String lastSentiment,
                                       BigDecimal lastAiConfidence, int messageCount, LastMessage lastMessage,
                                       Instant lastMessageAt, Instant createdAt, Instant updatedAt) {
    }

    public record LastMessage(SenderType senderType, String preview, Instant createdAt) {
    }

    public record ConversationDetailResponse(ConversationResponse conversation, List<MessageResponse> messages,
                                             String summary, TicketResponse openTicket, HandoffResponse handoff,
                                             boolean aiProcessing) {
    }

    /** Lo que recibe el agente cuando la IA deriva: motivo, confianza y lo que la IA ya respondió. */
    public record HandoffResponse(HandoffReason reason, Instant handoffAt, BigDecimal aiConfidence,
                                  CustomerRef customer, List<MessageResponse> previousAiResponses) {
    }
}
