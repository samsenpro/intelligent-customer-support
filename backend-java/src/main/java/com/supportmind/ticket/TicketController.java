package com.supportmind.ticket;

import com.supportmind.auth.AuthenticatedUser;
import com.supportmind.common.OptimisticRetry;
import com.supportmind.common.web.PageResponse;
import com.supportmind.common.web.SortableFields;
import com.supportmind.ticket.TicketDtos.CreateTicketRequest;
import com.supportmind.ticket.TicketDtos.TicketDetailResponse;
import com.supportmind.ticket.TicketDtos.TicketResponse;
import com.supportmind.ticket.TicketDtos.UpdateTicketRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tickets")
@Tag(name = "Tickets", description = "Support tickets, created manually or by the AI (handoff and classification)")
@SecurityRequirement(name = "bearerAuth")
public class TicketController {

    private static final Set<String> SORTABLE = Set.of("createdAt", "updatedAt", "priority", "status");

    private final TicketService ticketService;
    private final OptimisticRetry retry;

    public TicketController(TicketService ticketService, OptimisticRetry retry) {
        this.ticketService = ticketService;
        this.retry = retry;
    }

    @GetMapping
    @Operation(summary = "List tickets (customers: only their own)")
    public PageResponse<TicketResponse> list(@AuthenticationPrincipal AuthenticatedUser user,
                                             @RequestParam(required = false) TicketStatus status,
                                             @RequestParam(required = false) TicketPriority priority,
                                             @RequestParam(required = false) TicketCategory category,
                                             @RequestParam(required = false) UUID customerId,
                                             @RequestParam(defaultValue = "false") boolean assignedToMe,
                                             @PageableDefault(size = 20, sort = "createdAt",
                                                     direction = Sort.Direction.DESC) Pageable pageable) {
        return ticketService.list(user, status, priority, category, customerId, assignedToMe,
                SortableFields.validate(pageable, SORTABLE));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Ticket with its change history")
    public TicketDetailResponse get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return ticketService.get(user, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a ticket (a conversation can have at most one open ticket)")
    public TicketResponse create(@AuthenticationPrincipal AuthenticatedUser user,
                                 @Valid @RequestBody CreateTicketRequest request) {
        return ticketService.create(user, request);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @Operation(summary = "Change status, priority, category or assignee (every change is kept in the history)")
    public TicketResponse update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
                                 @Valid @RequestBody UpdateTicketRequest request) {
        return retry.execute(() -> ticketService.update(user, id, request));
    }
}
