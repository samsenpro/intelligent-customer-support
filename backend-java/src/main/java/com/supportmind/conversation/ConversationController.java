package com.supportmind.conversation;

import com.supportmind.auth.AuthenticatedUser;
import com.supportmind.common.OptimisticRetry;
import com.supportmind.common.idempotency.IdempotencyService;
import com.supportmind.common.ratelimit.RateLimited;
import com.supportmind.common.web.PageResponse;
import com.supportmind.common.web.SortableFields;
import com.supportmind.conversation.ConversationDtos.AssignConversationRequest;
import com.supportmind.conversation.ConversationDtos.ConversationDetailResponse;
import com.supportmind.conversation.ConversationDtos.ConversationResponse;
import com.supportmind.conversation.ConversationDtos.CreateConversationRequest;
import com.supportmind.conversation.ConversationDtos.HandoffResponse;
import com.supportmind.conversation.ConversationDtos.UpdateConversationRequest;
import com.supportmind.conversation.ConversationDtos.View;
import com.supportmind.message.MessageDtos.MessageResponse;
import com.supportmind.message.MessageDtos.SendMessageRequest;
import com.supportmind.message.MessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/conversations")
@Tag(name = "Conversations", description = "Conversations between customers, the AI and human agents")
@SecurityRequirement(name = "bearerAuth")
public class ConversationController {

    private static final Set<String> SORTABLE = Set.of("lastMessageAt", "createdAt", "updatedAt", "status");

    private final ConversationService conversationService;
    private final MessageService messageService;
    private final IdempotencyService idempotency;
    private final OptimisticRetry retry;

    public ConversationController(ConversationService conversationService, MessageService messageService,
                                  IdempotencyService idempotency, OptimisticRetry retry) {
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.idempotency = idempotency;
        this.retry = retry;
    }

    @GetMapping
    @Operation(summary = "List visible conversations",
            description = "ADMIN/SUPERVISOR: all. AGENT: assigned to them and the handoff queue. CUSTOMER: their own.")
    public PageResponse<ConversationResponse> list(@AuthenticationPrincipal AuthenticatedUser user,
                                                   @RequestParam(defaultValue = "ALL") View view,
                                                   @RequestParam(required = false) ConversationStatus status,
                                                   @RequestParam(required = false) Channel channel,
                                                   @RequestParam(required = false) UUID customerId,
                                                   @PageableDefault(size = 20, sort = "lastMessageAt",
                                                           direction = Sort.Direction.DESC) Pageable pageable) {
        return conversationService.list(user, view, status, channel, customerId,
                SortableFields.validate(pageable, SORTABLE));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RateLimited(policy = "messages")
    @Operation(summary = "Open a conversation, optionally with its first message")
    public ConversationResponse create(@AuthenticationPrincipal AuthenticatedUser user,
                                       @Valid @RequestBody CreateConversationRequest request) {
        return conversationService.create(user, request);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Conversation with its latest messages, summary, open ticket and handoff information")
    public ConversationDetailResponse get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return conversationService.detail(user, id);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Change the status or return the conversation to the AI")
    public ConversationResponse update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
                                       @Valid @RequestBody UpdateConversationRequest request) {
        return retry.execute(() -> conversationService.update(user, id, request));
    }

    @PostMapping("/{id}/assign")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @Operation(summary = "Take the conversation (agents) or assign it to an agent (supervisors)")
    public ConversationResponse assign(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
                                       @Valid @RequestBody(required = false) AssignConversationRequest request) {
        AssignConversationRequest body = request != null ? request : new AssignConversationRequest(null);
        return retry.execute(() -> conversationService.assign(user, id, body));
    }

    @GetMapping("/{id}/handoff")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @Operation(summary = "Handoff context for the agent: reason, AI confidence and previous AI responses")
    public HandoffResponse handoff(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return conversationService.handoff(user, id);
    }

    @GetMapping("/{id}/messages")
    @Operation(summary = "Messages of the conversation, oldest first")
    public PageResponse<MessageResponse> messages(@AuthenticationPrincipal AuthenticatedUser user,
                                                  @PathVariable UUID id,
                                                  @PageableDefault(size = 50, sort = "createdAt",
                                                          direction = Sort.Direction.ASC) Pageable pageable) {
        return messageService.list(user, id, SortableFields.validate(pageable, Set.of("createdAt")));
    }

    @PostMapping("/{id}/messages")
    @RateLimited(policy = "messages")
    @Operation(summary = "Send a message",
            description = "Customer messages are analyzed by the AI (intent, sentiment) and, if the conversation is "
                    + "handled by the AI, answered automatically with streaming over SSE. A message from an agent takes "
                    + "over the conversation.")
    @ApiResponse(responseCode = "201", description = "Message created")
    @ApiResponse(responseCode = "200", description = "Replay of a request with the same X-Idempotency-Key")
    public ResponseEntity<MessageResponse> send(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
            @Valid @RequestBody SendMessageRequest request,
            @Parameter(description = "Makes retries safe: the same key never creates the message twice")
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey) {
        var outcome = idempotency.execute(user.id(), "message", idempotencyKey, fingerprint(id, request.content()),
                () -> retry.execute(() -> messageService.send(user, id, request.content()).getId()));
        MessageResponse message = messageService.get(user, id, outcome.resourceId());
        return ResponseEntity.status(outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(message);
    }

    private static String fingerprint(UUID conversationId, String content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((conversationId + ":" + content).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
