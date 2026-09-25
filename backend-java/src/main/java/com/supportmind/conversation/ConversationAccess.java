package com.supportmind.conversation;

import com.supportmind.agent.Agent;
import com.supportmind.agent.AgentRepository;
import com.supportmind.auth.AuthenticatedUser;
import com.supportmind.auth.Role;
import com.supportmind.customer.Customer;
import com.supportmind.customer.CustomerRepository;
import com.supportmind.exception.ApiException;
import com.supportmind.exception.ErrorCode;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Reglas de acceso a conversaciones (RBAC + multi-tenancy), en un único sitio:
 * <ul>
 *   <li>Siempre dentro de la organización del usuario: otra organización no existe para él.</li>
 *   <li>ADMIN y SUPERVISOR: todas las conversaciones de la organización.</li>
 *   <li>AGENT: las asignadas a él y la cola de conversaciones derivadas sin asignar.</li>
 *   <li>CUSTOMER: solo las suyas.</li>
 * </ul>
 * Una conversación no visible responde 404 (no 403): no se revela que existe.
 */
@Component
public class ConversationAccess {

    private final ConversationRepository conversationRepository;
    private final AgentRepository agentRepository;
    private final CustomerRepository customerRepository;

    public ConversationAccess(ConversationRepository conversationRepository, AgentRepository agentRepository,
                              CustomerRepository customerRepository) {
        this.conversationRepository = conversationRepository;
        this.agentRepository = agentRepository;
        this.customerRepository = customerRepository;
    }

    public Conversation findVisible(AuthenticatedUser user, UUID conversationId) {
        Conversation conversation = conversationRepository.findByIdAndOrganizationId(conversationId,
                user.organizationId()).orElseThrow(ConversationAccess::notFound);
        if (!canSee(user, conversation)) {
            throw notFound();
        }
        return conversation;
    }

    /** Solo el equipo de soporte: los clientes no pueden (p. ej. pedir sugerencias o asignar). */
    public Conversation findVisibleToStaff(AuthenticatedUser user, UUID conversationId) {
        if (!user.isStaff()) {
            throw new ApiException(ErrorCode.ACCESS_DENIED);
        }
        return findVisible(user, conversationId);
    }

    public boolean canSee(AuthenticatedUser user, Conversation conversation) {
        if (!conversation.getOrganizationId().equals(user.organizationId())) {
            return false;
        }
        if (user.role().seesAllConversations()) {
            return true;
        }
        if (user.role() == Role.AGENT) {
            Optional<UUID> agentId = agentId(user);
            return conversation.isInAgentQueue()
                    || agentId.map(id -> id.equals(conversation.getAssignedAgentId())).orElse(false);
        }
        return customerId(user).map(id -> id.equals(conversation.getCustomerId())).orElse(false);
    }

    /** Filtro de listado equivalente a {@link #canSee}, aplicado en la consulta. */
    public Specification<Conversation> visibleTo(AuthenticatedUser user) {
        Specification<Conversation> spec = (root, query, cb) -> cb.equal(root.get("organizationId"),
                user.organizationId());
        if (user.role().seesAllConversations()) {
            return spec;
        }
        if (user.role() == Role.AGENT) {
            UUID agentId = agentId(user).orElse(null);
            return spec.and(assignedTo(agentId).or(inQueue()));
        }
        UUID customerId = customerId(user).orElse(null);
        return spec.and((root, query, cb) -> customerId == null ? cb.disjunction()
                : cb.equal(root.get("customerId"), customerId));
    }

    public static Specification<Conversation> assignedTo(UUID agentId) {
        return (root, query, cb) -> agentId == null ? cb.disjunction() : cb.equal(root.get("assignedAgentId"), agentId);
    }

    public static Specification<Conversation> inQueue() {
        return (root, query, cb) -> cb.and(
                cb.isFalse(root.get("aiEnabled")),
                cb.isNull(root.get("assignedAgentId")),
                root.get("status").in(ConversationStatus.OPEN, ConversationStatus.IN_PROGRESS,
                        ConversationStatus.WAITING_CUSTOMER));
    }

    public boolean agentBelongsTo(UUID agentId, UUID organizationId) {
        return agentRepository.findByIdAndOrganizationId(agentId, organizationId).isPresent();
    }

    public Optional<UUID> agentId(AuthenticatedUser user) {
        return user.isStaff() ? agentRepository.findByUserId(user.id()).map(Agent::getId) : Optional.empty();
    }

    public Optional<UUID> customerId(AuthenticatedUser user) {
        return user.isCustomer() ? customerRepository.findByUserId(user.id()).map(Customer::getId) : Optional.empty();
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Conversation not found");
    }
}
