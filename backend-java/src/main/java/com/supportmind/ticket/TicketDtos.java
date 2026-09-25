package com.supportmind.ticket;

import com.supportmind.common.Refs.AgentRef;
import com.supportmind.common.Refs.CustomerRef;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TicketDtos {

    private TicketDtos() {
    }

    public record CreateTicketRequest(
            @Schema(description = "Conversation the ticket comes from (optional)") UUID conversationId,
            @Schema(description = "Required for staff when there is no conversation; ignored for customers")
            UUID customerId,
            @Schema(example = "Refund of order 4521") @NotBlank @Size(max = 200) String subject,
            @NotBlank @Size(max = 8000) String description,
            @Schema(description = "Default MEDIUM") TicketPriority priority,
            @Schema(description = "Default GENERAL") TicketCategory category,
            @Schema(description = "Staff only") UUID assignedAgentId) {
    }

    @Schema(description = "Only the fields present are changed. `unassign: true` removes the assigned agent.")
    public record UpdateTicketRequest(TicketStatus status, TicketPriority priority, TicketCategory category,
                                      UUID assignedAgentId, Boolean unassign) {
    }

    public record TicketResponse(UUID id, UUID conversationId, CustomerRef customer, AgentRef assignedAgent,
                                 String subject, String description, TicketPriority priority, TicketStatus status,
                                 TicketCategory category, TicketSource source, Instant createdAt, Instant updatedAt,
                                 Instant resolvedAt) {
    }

    public record TicketDetailResponse(TicketResponse ticket, List<TicketEventResponse> history) {
    }

    public record TicketEventResponse(UUID id, UUID actorUserId, String field, String oldValue, String newValue,
                                      Instant createdAt) {

        static TicketEventResponse from(TicketEvent event) {
            return new TicketEventResponse(event.getId(), event.getActorUserId(), event.getField(),
                    event.getOldValue(), event.getNewValue(), event.getCreatedAt());
        }
    }
}
