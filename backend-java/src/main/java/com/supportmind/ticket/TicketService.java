package com.supportmind.ticket;

import com.supportmind.agent.AgentRepository;
import com.supportmind.audit.AuditEvent;
import com.supportmind.audit.AuditService;
import com.supportmind.auth.AuthenticatedUser;
import com.supportmind.common.AfterCommit;
import com.supportmind.common.BusinessMetrics;
import com.supportmind.common.RefResolver;
import com.supportmind.common.web.PageResponse;
import com.supportmind.conversation.Conversation;
import com.supportmind.conversation.ConversationAccess;
import com.supportmind.customer.Customer;
import com.supportmind.customer.CustomerRepository;
import com.supportmind.exception.ApiException;
import com.supportmind.exception.ErrorCode;
import com.supportmind.realtime.RealtimePublisher;
import com.supportmind.ticket.TicketDtos.CreateTicketRequest;
import com.supportmind.ticket.TicketDtos.TicketDetailResponse;
import com.supportmind.ticket.TicketDtos.TicketEventResponse;
import com.supportmind.ticket.TicketDtos.TicketResponse;
import com.supportmind.ticket.TicketDtos.UpdateTicketRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketService.class);

    private final TicketRepository ticketRepository;
    private final TicketEventRepository eventRepository;
    private final CustomerRepository customerRepository;
    private final AgentRepository agentRepository;
    private final ConversationAccess conversationAccess;
    private final RefResolver refs;
    private final AuditService auditService;
    private final BusinessMetrics metrics;
    private final RealtimePublisher realtime;
    private final Clock clock;

    public TicketService(TicketRepository ticketRepository, TicketEventRepository eventRepository,
                         CustomerRepository customerRepository, AgentRepository agentRepository,
                         ConversationAccess conversationAccess, RefResolver refs, AuditService auditService,
                         BusinessMetrics metrics, RealtimePublisher realtime, Clock clock) {
        this.ticketRepository = ticketRepository;
        this.eventRepository = eventRepository;
        this.customerRepository = customerRepository;
        this.agentRepository = agentRepository;
        this.conversationAccess = conversationAccess;
        this.refs = refs;
        this.auditService = auditService;
        this.metrics = metrics;
        this.realtime = realtime;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- consultas

    /** Los clientes solo ven sus tickets; el equipo ve todos los de su organización. */
    @Transactional(readOnly = true)
    public PageResponse<TicketResponse> list(AuthenticatedUser user, TicketStatus status, TicketPriority priority,
                                             TicketCategory category, UUID customerId, boolean assignedToMe,
                                             Pageable pageable) {
        Specification<Ticket> spec = inOrganization(user.organizationId());
        if (user.isCustomer()) {
            spec = spec.and(field("customerId", ownCustomerId(user)));
        } else if (customerId != null) {
            spec = spec.and(field("customerId", customerId));
        }
        if (assignedToMe && user.isStaff()) {
            UUID agentId = agentRepository.findByUserId(user.id()).map(a -> a.getId()).orElse(null);
            spec = spec.and(field("assignedAgentId", agentId));
        }
        if (status != null) {
            spec = spec.and(field("status", status));
        }
        if (priority != null) {
            spec = spec.and(field("priority", priority));
        }
        if (category != null) {
            spec = spec.and(field("category", category));
        }
        Page<Ticket> page = ticketRepository.findAll(spec, pageable);
        return new PageResponse<>(toResponses(page.getContent()), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    @Transactional(readOnly = true)
    public TicketDetailResponse get(AuthenticatedUser user, UUID id) {
        Ticket ticket = findVisible(user, id);
        List<TicketEventResponse> history = eventRepository.findByTicketIdOrderByCreatedAtAsc(ticket.getId()).stream()
                .map(TicketEventResponse::from).toList();
        return new TicketDetailResponse(toResponses(List.of(ticket)).getFirst(), history);
    }

    @Transactional(readOnly = true)
    public Optional<TicketResponse> openTicketOf(UUID conversationId) {
        return ticketRepository.findOpenByConversationId(conversationId).map(t -> toResponses(List.of(t)).getFirst());
    }

    // ---------------------------------------------------------------- creación

    @Transactional
    public TicketResponse create(AuthenticatedUser user, CreateTicketRequest request) {
        UUID customerId;
        UUID conversationId = null;
        if (request.conversationId() != null) {
            Conversation conversation = conversationAccess.findVisible(user, request.conversationId());
            conversationId = conversation.getId();
            customerId = conversation.getCustomerId();
        } else if (user.isCustomer()) {
            customerId = ownCustomerId(user);
        } else {
            if (request.customerId() == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "customerId is required without a conversation");
            }
            customerId = customerRepository.findByIdAndOrganizationId(request.customerId(), user.organizationId())
                    .map(Customer::getId)
                    .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Customer not found"));
        }
        UUID assignee = null;
        if (request.assignedAgentId() != null) {
            if (user.isCustomer()) {
                throw new ApiException(ErrorCode.ACCESS_DENIED, "Customers cannot assign tickets");
            }
            assignee = requireAgent(user.organizationId(), request.assignedAgentId());
        }
        Ticket ticket = new Ticket(user.organizationId(), conversationId, customerId, request.subject(),
                request.description(),
                request.priority() != null ? request.priority() : TicketPriority.MEDIUM,
                request.category() != null ? request.category() : TicketCategory.GENERAL,
                TicketSource.MANUAL, assignee);
        try {
            ticketRepository.saveAndFlush(ticket);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(ErrorCode.OPEN_TICKET_ALREADY_EXISTS);
        }
        created(ticket, user.id());
        return toResponses(List.of(ticket)).getFirst();
    }

    /**
     * Ticket creado por la IA (derivación o clasificación). Si la conversación ya tiene un ticket
     * abierto no se duplica: se eleva su prioridad si la nueva es mayor.
     */
    @Transactional
    public Ticket createOrEscalate(Conversation conversation, String subject, String description,
                                   TicketPriority priority, TicketCategory category, TicketSource source) {
        Optional<Ticket> open = ticketRepository.findOpenByConversationId(conversation.getId());
        if (open.isPresent()) {
            Ticket existing = open.get();
            if (priority.isHigherThan(existing.getPriority())) {
                recordChanges(existing, null, existing.changePriority(priority));
                log.info("Ticket {} escalated to {} by {}", existing.getId(), priority, source);
            }
            return existing;
        }
        Ticket ticket = new Ticket(conversation.getOrganizationId(), conversation.getId(),
                conversation.getCustomerId(), subject, description, priority, category, source,
                conversation.getAssignedAgentId());
        ticketRepository.saveAndFlush(ticket);
        created(ticket, null);
        return ticket;
    }

    // ---------------------------------------------------------------- actualización

    @Transactional
    public TicketResponse update(AuthenticatedUser user, UUID id, UpdateTicketRequest request) {
        Ticket ticket = findVisible(user, id);
        ticket.ensureModifiable();
        Map<String, String[]> changes = new LinkedHashMap<>();
        if (request.priority() != null) {
            changes.putAll(ticket.changePriority(request.priority()));
        }
        if (request.category() != null) {
            changes.putAll(ticket.changeCategory(request.category()));
        }
        if (Boolean.TRUE.equals(request.unassign())) {
            changes.putAll(ticket.assign(null));
        } else if (request.assignedAgentId() != null) {
            changes.putAll(ticket.assign(requireAgent(user.organizationId(), request.assignedAgentId())));
        }
        if (request.status() != null) {
            changes.putAll(ticket.changeStatus(request.status(), clock.instant()));
        }
        if (!changes.isEmpty()) {
            ticketRepository.saveAndFlush(ticket);
            recordChanges(ticket, user.id(), changes);
        }
        return toResponses(List.of(ticket)).getFirst();
    }

    private void recordChanges(Ticket ticket, UUID actorId, Map<String, String[]> changes) {
        if (changes.isEmpty()) {
            return;
        }
        changes.forEach((field, values) -> eventRepository.save(
                new TicketEvent(ticket.getOrganizationId(), ticket.getId(), actorId, field, values[0], values[1])));
        Map<String, Object> metadata = new LinkedHashMap<>();
        changes.forEach((field, values) -> metadata.put(field, values[1] == null ? "" : values[1]));
        auditService.record(AuditEvent.TICKET_UPDATED, ticket.getOrganizationId(), actorId, "Ticket", ticket.getId(),
                metadata);
        publishConversationUpdate(ticket);
    }

    private void created(Ticket ticket, UUID actorId) {
        eventRepository.save(new TicketEvent(ticket.getOrganizationId(), ticket.getId(), actorId, "status", null,
                ticket.getStatus().name()));
        auditService.record(AuditEvent.TICKET_CREATED, ticket.getOrganizationId(), actorId, "Ticket", ticket.getId(),
                Map.of("source", ticket.getSource().name(), "priority", ticket.getPriority().name(),
                        "category", ticket.getCategory().name()));
        AfterCommit.run(() -> metrics.ticketCreated(ticket.getSource().name(), ticket.getPriority().name()));
        publishConversationUpdate(ticket);
    }

    private void publishConversationUpdate(Ticket ticket) {
        if (ticket.getConversationId() != null) {
            UUID conversationId = ticket.getConversationId();
            AfterCommit.run(() -> realtime.conversationChanged(ticket.getOrganizationId(), conversationId));
        }
    }

    // ---------------------------------------------------------------- auxiliares

    private Ticket findVisible(AuthenticatedUser user, UUID id) {
        Ticket ticket = ticketRepository.findByIdAndOrganizationId(id, user.organizationId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Ticket not found"));
        if (user.isCustomer() && !ticket.getCustomerId().equals(ownCustomerId(user))) {
            // 404 y no 403: un cliente no debe poder saber que existe un ticket ajeno
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Ticket not found");
        }
        return ticket;
    }

    private UUID ownCustomerId(AuthenticatedUser user) {
        return customerRepository.findByUserId(user.id()).map(Customer::getId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Customer profile not found"));
    }

    private UUID requireAgent(UUID organizationId, UUID agentId) {
        return agentRepository.findByIdAndOrganizationId(agentId, organizationId).map(a -> a.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Agent not found"));
    }

    private List<TicketResponse> toResponses(List<Ticket> tickets) {
        var customers = refs.customers(tickets.stream().map(Ticket::getCustomerId).toList());
        var agents = refs.agents(tickets.stream().map(Ticket::getAssignedAgentId).toList());
        List<TicketResponse> responses = new ArrayList<>(tickets.size());
        for (Ticket t : tickets) {
            responses.add(new TicketResponse(t.getId(), t.getConversationId(), customers.get(t.getCustomerId()),
                    agents.get(t.getAssignedAgentId()), t.getSubject(), t.getDescription(), t.getPriority(),
                    t.getStatus(), t.getCategory(), t.getSource(), t.getCreatedAt(), t.getUpdatedAt(),
                    t.getResolvedAt()));
        }
        return responses;
    }

    private static Specification<Ticket> inOrganization(UUID organizationId) {
        return field("organizationId", organizationId);
    }

    private static Specification<Ticket> field(String name, Object value) {
        return (root, query, cb) -> value == null ? cb.isNull(root.get(name)) : cb.equal(root.get(name), value);
    }
}
