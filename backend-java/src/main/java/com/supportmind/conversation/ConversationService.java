package com.supportmind.conversation;

import com.supportmind.ai.AiProcessingState;
import com.supportmind.audit.AuditEvent;
import com.supportmind.audit.AuditService;
import com.supportmind.auth.AuthenticatedUser;
import com.supportmind.common.AfterCommit;
import com.supportmind.common.BusinessMetrics;
import com.supportmind.common.RefResolver;
import com.supportmind.common.web.PageResponse;
import com.supportmind.conversation.ConversationDtos.AssignConversationRequest;
import com.supportmind.conversation.ConversationDtos.ConversationDetailResponse;
import com.supportmind.conversation.ConversationDtos.ConversationResponse;
import com.supportmind.conversation.ConversationDtos.CreateConversationRequest;
import com.supportmind.conversation.ConversationDtos.HandoffResponse;
import com.supportmind.conversation.ConversationDtos.LastMessage;
import com.supportmind.conversation.ConversationDtos.UpdateConversationRequest;
import com.supportmind.conversation.ConversationDtos.View;
import com.supportmind.customer.Customer;
import com.supportmind.customer.CustomerRepository;
import com.supportmind.exception.ApiException;
import com.supportmind.exception.ErrorCode;
import com.supportmind.message.Message;
import com.supportmind.message.MessageDtos.MessageResponse;
import com.supportmind.message.MessageMapper;
import com.supportmind.message.MessageRepository;
import com.supportmind.message.MessageService;
import com.supportmind.message.SenderType;
import com.supportmind.organization.Organization;
import com.supportmind.organization.OrganizationRepository;
import com.supportmind.realtime.RealtimePublisher;
import com.supportmind.ticket.TicketService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ConversationService {

    private static final int DETAIL_MESSAGES = 100;
    private static final int PREVIEW_CHARS = 140;

    private final ConversationRepository conversationRepository;
    private final ConversationSummaryRepository summaryRepository;
    private final ConversationAccess access;
    private final CustomerRepository customerRepository;
    private final OrganizationRepository organizationRepository;
    private final MessageRepository messageRepository;
    private final MessageService messageService;
    private final MessageMapper messageMapper;
    private final TicketService ticketService;
    private final AiProcessingState aiProcessing;
    private final RefResolver refs;
    private final RealtimePublisher realtime;
    private final AuditService auditService;
    private final BusinessMetrics metrics;
    private final Clock clock;

    public ConversationService(ConversationRepository conversationRepository,
                               ConversationSummaryRepository summaryRepository, ConversationAccess access,
                               CustomerRepository customerRepository, OrganizationRepository organizationRepository,
                               MessageRepository messageRepository, MessageService messageService,
                               MessageMapper messageMapper, TicketService ticketService,
                               AiProcessingState aiProcessing, RefResolver refs, RealtimePublisher realtime,
                               AuditService auditService, BusinessMetrics metrics, Clock clock) {
        this.conversationRepository = conversationRepository;
        this.summaryRepository = summaryRepository;
        this.access = access;
        this.customerRepository = customerRepository;
        this.organizationRepository = organizationRepository;
        this.messageRepository = messageRepository;
        this.messageService = messageService;
        this.messageMapper = messageMapper;
        this.ticketService = ticketService;
        this.aiProcessing = aiProcessing;
        this.refs = refs;
        this.realtime = realtime;
        this.auditService = auditService;
        this.metrics = metrics;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- consultas

    @Transactional(readOnly = true)
    public PageResponse<ConversationResponse> list(AuthenticatedUser user, View view, ConversationStatus status,
                                                   Channel channel, UUID customerId, Pageable pageable) {
        Specification<Conversation> spec = access.visibleTo(user);
        if (view == View.MINE) {
            spec = spec.and(ConversationAccess.assignedTo(access.agentId(user).orElse(null)));
        } else if (view == View.QUEUE) {
            spec = spec.and(ConversationAccess.inQueue());
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (channel != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("channel"), channel));
        }
        if (customerId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("customerId"), customerId));
        }
        Page<Conversation> page = conversationRepository.findAll(spec, pageable);
        return new PageResponse<>(toResponses(page.getContent()), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    @Transactional(readOnly = true)
    public ConversationDetailResponse detail(AuthenticatedUser user, UUID id) {
        Conversation conversation = access.findVisible(user, id);
        List<Message> latest = new ArrayList<>(messageRepository.findLatest(conversation.getId(), DETAIL_MESSAGES));
        Collections.reverse(latest);
        String summary = user.isStaff() ? summaryRepository.findByConversationId(conversation.getId())
                .map(ConversationSummary::getSummary).orElse(null) : null;
        return new ConversationDetailResponse(
                toResponses(List.of(conversation)).getFirst(),
                messageMapper.toResponses(latest),
                summary,
                ticketService.openTicketOf(conversation.getId()).orElse(null),
                user.isStaff() && conversation.getHandoffReason() != null ? handoffOf(conversation) : null,
                aiProcessing.isProcessing(conversation.getId()));
    }

    @Transactional(readOnly = true)
    public HandoffResponse handoff(AuthenticatedUser user, UUID id) {
        Conversation conversation = access.findVisibleToStaff(user, id);
        if (conversation.getHandoffReason() == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "The conversation was not handed off");
        }
        return handoffOf(conversation);
    }

    private HandoffResponse handoffOf(Conversation conversation) {
        List<MessageResponse> aiResponses = messageMapper.toResponses(messageRepository
                .findByConversationIdAndSenderTypeOrderByCreatedAtAsc(conversation.getId(), SenderType.AI));
        return new HandoffResponse(conversation.getHandoffReason(), conversation.getHandoffAt(),
                conversation.getLastAiConfidence(), refs.customer(conversation.getCustomerId()), aiResponses);
    }

    // ---------------------------------------------------------------- comandos

    @Transactional
    public ConversationResponse create(AuthenticatedUser user, CreateConversationRequest request) {
        Organization organization = organizationRepository.findById(user.organizationId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Organization not found"));
        UUID customerId;
        if (user.isCustomer()) {
            customerId = access.customerId(user)
                    .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Customer profile not found"));
        } else {
            if (request.customerId() == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "customerId is required");
            }
            customerId = customerRepository.findByIdAndOrganizationId(request.customerId(), user.organizationId())
                    .map(Customer::getId)
                    .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Customer not found"));
        }
        boolean aiEnabled = request.aiEnabled() != null && !user.isCustomer()
                ? request.aiEnabled() : organization.getAiSettings().isAutoReplyEnabled();
        Channel channel = request.channel() != null ? request.channel() : Channel.WEB;
        Conversation conversation = conversationRepository.saveAndFlush(
                new Conversation(user.organizationId(), customerId, channel, request.subject(), aiEnabled));
        auditService.record(AuditEvent.CONVERSATION_CREATED, user.organizationId(), user.id(), "Conversation",
                conversation.getId(), Map.of("channel", channel.name(), "aiEnabled", aiEnabled));
        AfterCommit.run(() -> metrics.conversationCreated(channel.name()));

        if (request.message() != null && !request.message().isBlank()) {
            if (user.isCustomer()) {
                messageService.record(conversation, SenderType.CUSTOMER, customerId, request.message(), Map.of(),
                        user.id());
            } else {
                messageService.send(user, conversation.getId(), request.message());
            }
        } else {
            AfterCommit.run(() -> realtime.conversationChanged(conversation));
        }
        return toResponses(List.of(conversation)).getFirst();
    }

    @Transactional
    public ConversationResponse update(AuthenticatedUser user, UUID id, UpdateConversationRequest request) {
        Conversation conversation = access.findVisible(user, id);
        if (user.isCustomer() && (request.aiEnabled() != null
                || (request.status() != null && request.status() != ConversationStatus.RESOLVED))) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Customers can only mark their conversation as resolved");
        }
        if (Boolean.TRUE.equals(request.aiEnabled()) && !conversation.isAiEnabled()) {
            conversation.returnToAi();
        } else if (Boolean.FALSE.equals(request.aiEnabled()) && conversation.isAiEnabled()) {
            conversation.handOff(HandoffReason.AGENT_TOOK_OVER, clock.instant());
        }
        if (request.status() != null && request.status() != conversation.getStatus()) {
            ConversationStatus previous = conversation.getStatus();
            conversation.changeStatus(request.status(), clock.instant());
            auditService.record(AuditEvent.CONVERSATION_STATUS_CHANGED, user.organizationId(), user.id(),
                    "Conversation", conversation.getId(), Map.of("from", previous.name(), "to", request.status().name()));
        }
        conversationRepository.saveAndFlush(conversation);
        AfterCommit.run(() -> realtime.conversationChanged(conversation));
        return toResponses(List.of(conversation)).getFirst();
    }

    /** Un agente toma una conversación de la cola; un supervisor puede asignarla a cualquier agente. */
    @Transactional
    public ConversationResponse assign(AuthenticatedUser user, UUID id, AssignConversationRequest request) {
        Conversation conversation = access.findVisibleToStaff(user, id);
        UUID ownAgentId = access.agentId(user)
                .orElseThrow(() -> new ApiException(ErrorCode.ACCESS_DENIED, "The user has no agent profile"));
        UUID target = request.agentId() != null ? request.agentId() : ownAgentId;
        if (!target.equals(ownAgentId) && !user.role().seesAllConversations()) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Agents can only take conversations for themselves");
        }
        if (!access.agentBelongsTo(target, user.organizationId())) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Agent not found");
        }
        boolean wasAiHandled = conversation.isAiEnabled();
        conversation.assign(target);
        if (wasAiHandled) {
            conversation.handOff(HandoffReason.AGENT_TOOK_OVER, clock.instant());
        }
        conversationRepository.saveAndFlush(conversation);
        auditService.record(AuditEvent.CONVERSATION_ASSIGNED, user.organizationId(), user.id(), "Conversation",
                conversation.getId(), Map.of("agentId", target.toString()));
        AfterCommit.run(() -> realtime.conversationChanged(conversation));
        return toResponses(List.of(conversation)).getFirst();
    }

    // ---------------------------------------------------------------- mapeo

    private List<ConversationResponse> toResponses(List<Conversation> conversations) {
        if (conversations.isEmpty()) {
            return List.of();
        }
        var customers = refs.customers(conversations.stream().map(Conversation::getCustomerId).toList());
        var agents = refs.agents(conversations.stream().map(Conversation::getAssignedAgentId).toList());
        Map<UUID, Message> last = messageRepository.findLastOfEach(conversations.stream().map(Conversation::getId)
                .toList()).stream().collect(Collectors.toMap(Message::getConversationId, Function.identity()));
        return conversations.stream().map(c -> {
            Message message = last.get(c.getId());
            LastMessage preview = message == null ? null : new LastMessage(message.getSenderType(),
                    preview(message.getContent()), message.getCreatedAt());
            return new ConversationResponse(c.getId(), customers.get(c.getCustomerId()),
                    agents.get(c.getAssignedAgentId()), c.getStatus(), c.getChannel(), c.getSubject(),
                    c.isAiEnabled(), c.getHandoffReason(), c.getHandoffAt(), c.getLastIntent(), c.getLastCategory(),
                    c.getLastSentiment(), c.getLastAiConfidence(), c.getMessageCount(), preview, c.getLastMessageAt(),
                    c.getCreatedAt(), c.getUpdatedAt());
        }).toList();
    }

    private static String preview(String content) {
        return content.length() <= PREVIEW_CHARS ? content : content.substring(0, PREVIEW_CHARS) + "…";
    }
}
