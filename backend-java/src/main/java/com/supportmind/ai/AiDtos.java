package com.supportmind.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AiDtos {

    private AiDtos() {
    }

    public record AiReplyAccepted(UUID conversationId, String status) {
    }

    public record SuggestionResponse(UUID id, UUID conversationId, String suggestedResponse, BigDecimal confidence,
                                     List<Map<String, Object>> sources, SuggestionStatus status, String model,
                                     UUID finalMessageId, Instant createdAt) {

        static SuggestionResponse from(AiSuggestion suggestion) {
            return new SuggestionResponse(suggestion.getId(), suggestion.getConversationId(), suggestion.getContent(),
                    suggestion.getConfidence(), suggestion.getSources(), suggestion.getStatus(), suggestion.getModel(),
                    suggestion.getFinalMessageId(), suggestion.getCreatedAt());
        }
    }

    public record AcceptSuggestionRequest(
            @Schema(description = "Edited text. Omitted: the suggestion is sent as is")
            @Size(max = 4000) String content) {
    }
}
