package com.supportmind.ai;

import com.supportmind.ai.AiDtos.SuggestionResponse;
import com.supportmind.ai.ConversationMemoryService.ConversationMemory;
import com.supportmind.ai.ConversationMemoryService.MemoryEntry;
import com.supportmind.ai.client.AiContract.SuggestRequest;
import com.supportmind.ai.client.AiContract.Suggestion;
import com.supportmind.ai.client.AiServiceClient;
import com.supportmind.ai.client.AiServiceException;
import com.supportmind.audit.AuditEvent;
import com.supportmind.audit.AuditService;
import com.supportmind.auth.AuthenticatedUser;
import com.supportmind.conversation.Conversation;
import com.supportmind.conversation.ConversationAccess;
import com.supportmind.exception.ApiException;
import com.supportmind.exception.ErrorCode;
import com.supportmind.message.Message;
import com.supportmind.message.MessageDtos.MessageResponse;
import com.supportmind.message.MessageMapper;
import com.supportmind.message.MessageRepository;
import com.supportmind.message.MessageService;
import com.supportmind.organization.OrganizationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Suggest response" para agentes humanos: Python analiza la conversación, el último mensaje del
 * cliente y la base de conocimiento, y devuelve un borrador con su confianza y sus fuentes.
 * <p>
 * El agente lo acepta (tal cual o editado) o lo rechaza; la sugerencia nunca se envía sola.
 */
@Service
public class SuggestionService {

    private final ConversationAccess access;
    private final OrganizationRepository organizationRepository;
    private final ConversationMemoryService memoryService;
    private final AiServiceClient aiClient;
    private final AiSuggestionRepository suggestionRepository;
    private final MessageService messageService;
    private final MessageRepository messageRepository;
    private final MessageMapper messageMapper;
    private final AuditService auditService;
    private final AiProperties properties;
    private final TransactionTemplate transaction;

    public SuggestionService(ConversationAccess access, OrganizationRepository organizationRepository,
                             ConversationMemoryService memoryService, AiServiceClient aiClient,
                             AiSuggestionRepository suggestionRepository, MessageService messageService,
                             MessageRepository messageRepository, MessageMapper messageMapper,
                             AuditService auditService, AiProperties properties,
                             PlatformTransactionManager transactionManager) {
        this.access = access;
        this.organizationRepository = organizationRepository;
        this.memoryService = memoryService;
        this.aiClient = aiClient;
        this.suggestionRepository = suggestionRepository;
        this.messageService = messageService;
        this.messageRepository = messageRepository;
        this.messageMapper = messageMapper;
        this.auditService = auditService;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** La llamada al servicio de IA va fuera de transacción: no retiene una conexión de BD mientras espera. */
    public SuggestionResponse suggest(AuthenticatedUser user, UUID conversationId) {
        Conversation conversation = transaction.execute(status -> access.findVisibleToStaff(user, conversationId));
        String organizationName = organizationRepository.findById(user.organizationId()).orElseThrow().getName();
        ConversationMemory memory = memoryService.load(conversationId);
        MemoryEntry customerMessage = memory.lastCustomerMessage();
        if (customerMessage == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The conversation has no customer message to answer");
        }
        Suggestion suggestion;
        try {
            suggestion = aiClient.suggest(new SuggestRequest(conversation.getOrganizationId(), organizationName,
                    conversationId, customerMessage.content(), memory.history(), memory.summary(), properties.topK()));
        } catch (AiServiceException ex) {
            throw new ApiException(ErrorCode.AI_TEMPORARILY_UNAVAILABLE);
        }
        AiSuggestion saved = transaction.execute(status -> suggestionRepository.save(new AiSuggestion(
                conversation.getOrganizationId(), conversationId, user.id(), suggestion.suggestedResponse(),
                suggestion.confidence(), AiReplyService.sources(suggestion.sources()), suggestion.model())));
        return SuggestionResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<SuggestionResponse> list(AuthenticatedUser user, UUID conversationId) {
        Conversation conversation = access.findVisibleToStaff(user, conversationId);
        return suggestionRepository.findTop20ByConversationIdOrderByCreatedAtDesc(conversation.getId()).stream()
                .map(SuggestionResponse::from).toList();
    }

    /** Envía la sugerencia como mensaje del agente (con sus cambios, si los hay). */
    @Transactional
    public MessageResponse accept(AuthenticatedUser user, UUID conversationId, UUID suggestionId, String edited) {
        AiSuggestion suggestion = find(user, conversationId, suggestionId);
        String content = edited != null && !edited.isBlank() ? edited.strip() : suggestion.getContent();
        boolean wasEdited = !content.equals(suggestion.getContent());
        Message message = messageService.send(user, conversationId, content);
        message.putMetadata("suggestion", Map.of("suggestionId", suggestionId.toString(), "edited", wasEdited));
        messageRepository.save(message);
        suggestion.accept(message.getId(), wasEdited);
        auditService.record(AuditEvent.AI_SUGGESTION_ACCEPTED, user.organizationId(), user.id(), "AiSuggestion",
                suggestionId, Map.of("edited", wasEdited, "messageId", message.getId().toString()));
        return messageMapper.toResponse(message);
    }

    @Transactional
    public SuggestionResponse reject(AuthenticatedUser user, UUID conversationId, UUID suggestionId) {
        AiSuggestion suggestion = find(user, conversationId, suggestionId);
        suggestion.reject();
        suggestionRepository.saveAndFlush(suggestion);
        auditService.record(AuditEvent.AI_SUGGESTION_REJECTED, user.organizationId(), user.id(), "AiSuggestion",
                suggestionId, Map.of());
        return SuggestionResponse.from(suggestion);
    }

    private AiSuggestion find(AuthenticatedUser user, UUID conversationId, UUID suggestionId) {
        Conversation conversation = access.findVisibleToStaff(user, conversationId);
        return suggestionRepository.findByIdAndConversationId(suggestionId, conversation.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Suggestion not found"));
    }
}
