package com.supportmind.realtime;

import com.supportmind.auth.AuthenticatedUser;
import com.supportmind.conversation.ConversationAccess;
import com.supportmind.realtime.SseHub.Subscriber;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@RestController
@Tag(name = "Realtime", description = "Server-Sent Events: new messages, AI answers streamed token by token, "
        + "conversation updates")
@SecurityRequirement(name = "bearerAuth")
public class RealtimeController {

    private final SseHub hub;
    private final ConversationAccess access;

    public RealtimeController(SseHub hub, ConversationAccess access) {
        this.hub = hub;
        this.access = access;
    }

    @GetMapping(path = "/api/v1/conversations/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Events of one conversation (messages, AI streaming with ai.delta, status changes)")
    public SseEmitter conversationEvents(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        access.findVisible(user, id);
        return hub.subscribe(subscriber(user), id);
    }

    @GetMapping(path = "/api/v1/inbox/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Events of every conversation visible to the user (inbox updates, handoffs)")
    public SseEmitter inboxEvents(@AuthenticationPrincipal AuthenticatedUser user) {
        return hub.subscribe(subscriber(user), null);
    }

    private Subscriber subscriber(AuthenticatedUser user) {
        return new Subscriber(user.id(), user.organizationId(), user.role(), access.agentId(user).orElse(null),
                access.customerId(user).orElse(null));
    }
}
