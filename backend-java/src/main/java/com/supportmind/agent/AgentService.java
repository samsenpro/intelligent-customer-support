package com.supportmind.agent;

import com.supportmind.agent.AgentDtos.AgentResponse;
import com.supportmind.agent.AgentDtos.UpdateAgentRequest;
import com.supportmind.auth.AuthenticatedUser;
import com.supportmind.auth.User;
import com.supportmind.conversation.ConversationRepository;
import com.supportmind.exception.ApiException;
import com.supportmind.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AgentService {

    private final AgentRepository agentRepository;
    private final ConversationRepository conversationRepository;

    public AgentService(AgentRepository agentRepository, ConversationRepository conversationRepository) {
        this.agentRepository = agentRepository;
        this.conversationRepository = conversationRepository;
    }

    /** Todo miembro del equipo (ADMIN, SUPERVISOR, AGENT) tiene un perfil de agente. */
    @Transactional
    public Agent createProfile(User user) {
        return agentRepository.save(new Agent(user.getOrganizationId(), user.getId(), user.getFullName()));
    }

    @Transactional(readOnly = true)
    public List<AgentResponse> list(AuthenticatedUser user) {
        List<Agent> agents = agentRepository.findByOrganizationIdOrderByDisplayName(user.organizationId());
        Map<UUID, Long> load = conversationRepository.countActiveByAgent(user.organizationId());
        return agents.stream().map(agent -> AgentResponse.from(agent, load.getOrDefault(agent.getId(), 0L))).toList();
    }

    @Transactional(readOnly = true)
    public AgentResponse me(AuthenticatedUser user) {
        Agent agent = agentRepository.findByUserId(user.id())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "The user has no agent profile"));
        return AgentResponse.from(agent, conversationRepository.countActiveByAgent(user.organizationId())
                .getOrDefault(agent.getId(), 0L));
    }

    /**
     * Un agente cambia su propio estado; ADMIN y SUPERVISOR pueden cambiar el de cualquier agente de
     * su organización y su capacidad máxima.
     */
    @Transactional
    public AgentResponse update(AuthenticatedUser user, UUID agentId, UpdateAgentRequest request) {
        Agent agent = agentRepository.findByIdAndOrganizationId(agentId, user.organizationId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Agent not found"));
        boolean own = agent.getUserId().equals(user.id());
        if (!own && !user.role().seesAllConversations()) {
            throw new ApiException(ErrorCode.ACCESS_DENIED);
        }
        if (request.maxActiveConversations() != null && !user.role().seesAllConversations()) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Only supervisors can change the agent capacity");
        }
        agent.update(request.status(), request.maxActiveConversations());
        return AgentResponse.from(agent, conversationRepository.countActiveByAgent(user.organizationId())
                .getOrDefault(agent.getId(), 0L));
    }
}
