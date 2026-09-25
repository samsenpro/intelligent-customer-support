package com.supportmind.message;

import com.supportmind.ai.ConversationMemoryService;
import com.supportmind.ai.jobs.AiJob;
import com.supportmind.ai.jobs.AiJobQueue;
import com.supportmind.ai.jobs.AiJobType;
import com.supportmind.audit.AuditEvent;
import com.supportmind.audit.AuditService;
import com.supportmind.auth.AuthenticatedUser;
import com.supportmind.common.AfterCommit;
import com.supportmind.common.web.CorrelationId;
import com.supportmind.common.web.PageResponse;
import com.supportmind.conversation.Conversation;
import com.supportmind.conversation.ConversationAccess;
import com.supportmind.conversation.ConversationRepository;
import com.supportmind.conversation.HandoffReason;
import com.supportmind.conversation.channel.ChannelRouter;
import com.supportmind.exception.ApiException;
import com.supportmind.exception.ErrorCode;
import com.supportmind.message.MessageDtos.MessageResponse;
import com.supportmind.realtime.RealtimePublisher;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

@Service
public class MessageService {

    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationAccess access;
    private final MessageMapper mapper;
    private final ConversationMemoryService memory;
    private final RealtimePublisher realtime;
    private final ChannelRouter channels;
    private final AiJobQueue jobs;
    private final AuditService auditService;
    private final Clock clock;

    public MessageService(MessageRepository messageRepository, ConversationRepository conversationRepository,
                          ConversationAccess access, MessageMapper mapper, ConversationMemoryService memory,
                          RealtimePublisher realtime, ChannelRouter channels, AiJobQueue jobs,
                          AuditService auditService, Clock clock) {
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
        this.access = access;
        this.mapper = mapper;
        this.memory = memory;
        this.realtime = realtime;
        this.channels = channels;
        this.jobs = jobs;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * Mensaje de un usuario. Un cliente escribe como CUSTOMER y dispara el análisis (y la respuesta)
     * de la IA; un miembro del equipo escribe como AGENT y, al hacerlo, toma la conversación: la IA
     * deja de responder sola.
     */
    @Transactional
    public Message send(AuthenticatedUser user, UUID conversationId, String content) {
        Conversation conversation = access.findVisible(user, conversationId);
        conversation.ensureOpenForMessages();
        if (user.isCustomer()) {
            UUID customerId = conversation.getCustomerId();
            return record(conversation, SenderType.CUSTOMER, customerId, content, Map.of(), user.id());
        }
        takeOverIfNeeded(user, conversation);
        return record(conversation, SenderType.AGENT, user.id(), content, Map.of(), user.id());
    }

    /**
     * Registra un mensaje de cualquier tipo en la transacción actual: actualiza la conversación, la
     * audita y, tras el commit, lo publica en tiempo real, lo añade a la memoria, lo entrega por el
     * canal y (si es del cliente) encola el análisis de la IA.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Message record(Conversation conversation, SenderType senderType, UUID senderId, String content,
                          Map<String, Object> metadata, UUID actorUserId) {
        Message message = messageRepository.save(new Message(conversation.getOrganizationId(), conversation.getId(),
                senderType, senderId, content, metadata));
        conversation.recordMessage(senderType, clock.instant());
        conversationRepository.saveAndFlush(conversation);
        auditService.record(AuditEvent.MESSAGE_SENT, conversation.getOrganizationId(), actorUserId, "Message",
                message.getId(), Map.of("conversationId", conversation.getId().toString(),
                        "senderType", senderType.name()));

        String correlationId = MDC.get(CorrelationId.MDC_KEY);
        AfterCommit.run(() -> {
            memory.append(message);
            realtime.messageCreated(conversation, mapper.toResponse(message));
            realtime.conversationChanged(conversation);
            if (senderType == SenderType.AGENT || senderType == SenderType.AI) {
                channels.deliver(conversation, message);
            }
            if (senderType == SenderType.CUSTOMER) {
                jobs.publish(AiJob.of(AiJobType.ANALYZE_MESSAGE, conversation.getOrganizationId(), message.getId(),
                        correlationId));
            }
        });
        return message;
    }

    @Transactional(readOnly = true)
    public MessageResponse get(AuthenticatedUser user, UUID conversationId, UUID messageId) {
        Conversation conversation = access.findVisible(user, conversationId);
        return messageRepository.findByIdAndConversationId(messageId, conversation.getId())
                .map(mapper::toResponse)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Message not found"));
    }

    @Transactional(readOnly = true)
    public PageResponse<MessageResponse> list(AuthenticatedUser user, UUID conversationId, Pageable pageable) {
        Conversation conversation = access.findVisible(user, conversationId);
        Page<Message> page = messageRepository.findByConversationId(conversation.getId(), pageable);
        return new PageResponse<>(mapper.toResponses(page.getContent()), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    /** Un miembro del equipo que responde una conversación sin asignar la toma. */
    private void takeOverIfNeeded(AuthenticatedUser user, Conversation conversation) {
        if (conversation.getAssignedAgentId() != null) {
            if (conversation.isAiEnabled()) {
                conversation.handOff(HandoffReason.AGENT_TOOK_OVER, clock.instant());
            }
            return;
        }
        UUID agentId = access.agentId(user)
                .orElseThrow(() -> new ApiException(ErrorCode.ACCESS_DENIED, "The user has no agent profile"));
        boolean wasAiHandled = conversation.isAiEnabled();
        conversation.assign(agentId);
        if (wasAiHandled) {
            conversation.handOff(HandoffReason.AGENT_TOOK_OVER, clock.instant());
        }
    }
}
