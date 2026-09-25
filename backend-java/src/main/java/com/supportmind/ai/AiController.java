package com.supportmind.ai;

import com.supportmind.ai.AiDtos.AcceptSuggestionRequest;
import com.supportmind.ai.AiDtos.AiReplyAccepted;
import com.supportmind.ai.AiDtos.SuggestionResponse;
import com.supportmind.ai.jobs.AiJob;
import com.supportmind.ai.jobs.AiJobQueue;
import com.supportmind.ai.jobs.AiJobType;
import com.supportmind.auth.AuthenticatedUser;
import com.supportmind.common.OptimisticRetry;
import com.supportmind.common.ratelimit.RateLimited;
import com.supportmind.common.web.CorrelationId;
import com.supportmind.conversation.Conversation;
import com.supportmind.conversation.ConversationAccess;
import com.supportmind.exception.ApiException;
import com.supportmind.exception.ErrorCode;
import com.supportmind.message.MessageDtos.MessageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/conversations/{id}/ai")
@Tag(name = "AI", description = "AI replies (RAG) and response suggestions for agents")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
public class AiController {

    private final ConversationAccess access;
    private final AiProcessingState processingState;
    private final AiJobQueue jobs;
    private final SuggestionService suggestionService;
    private final OptimisticRetry retry;

    public AiController(ConversationAccess access, AiProcessingState processingState, AiJobQueue jobs,
                        SuggestionService suggestionService, OptimisticRetry retry) {
        this.access = access;
        this.processingState = processingState;
        this.jobs = jobs;
        this.suggestionService = suggestionService;
        this.retry = retry;
    }

    @PostMapping("/reply")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RateLimited(policy = "ai")
    @Operation(summary = "Ask the AI to answer the last customer message",
            description = "Asynchronous: the answer is streamed through the conversation events (SSE) as ai.delta "
                    + "and ends with message.created and ai.completed.")
    @ApiResponse(responseCode = "202", description = "Reply queued")
    @ApiResponse(responseCode = "409", description = "The AI is already answering this conversation")
    public AiReplyAccepted reply(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        Conversation conversation = access.findVisibleToStaff(user, id);
        conversation.ensureOpenForMessages();
        if (processingState.isProcessing(id)) {
            throw new ApiException(ErrorCode.AI_REPLY_IN_PROGRESS);
        }
        jobs.publish(AiJob.of(AiJobType.AI_REPLY, user.organizationId(), id, MDC.get(CorrelationId.MDC_KEY)));
        return new AiReplyAccepted(id, "QUEUED");
    }

    @PostMapping("/suggest")
    @ResponseStatus(HttpStatus.CREATED)
    @RateLimited(policy = "ai")
    @Operation(summary = "Suggest a reply for the agent, with confidence and knowledge base sources")
    @ApiResponse(responseCode = "503", description = "AI temporarily unavailable")
    public SuggestionResponse suggest(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return suggestionService.suggest(user, id);
    }

    @GetMapping("/suggestions")
    @Operation(summary = "Latest suggestions of the conversation")
    public List<SuggestionResponse> suggestions(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return suggestionService.list(user, id);
    }

    @PostMapping("/suggestions/{suggestionId}/accept")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Send the suggestion as the agent's message (as is or edited)")
    public MessageResponse accept(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
                                  @PathVariable UUID suggestionId,
                                  @Valid @RequestBody(required = false) AcceptSuggestionRequest request) {
        String edited = request != null ? request.content() : null;
        return retry.execute(() -> suggestionService.accept(user, id, suggestionId, edited));
    }

    @PostMapping("/suggestions/{suggestionId}/reject")
    @Operation(summary = "Reject the suggestion")
    public SuggestionResponse reject(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
                                     @PathVariable UUID suggestionId) {
        return suggestionService.reject(user, id, suggestionId);
    }
}
