package com.supportmind.agent;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.UUID;

public final class AgentDtos {

    private AgentDtos() {
    }

    public record AgentResponse(UUID id, UUID userId, String displayName, AgentStatus status,
                                int maxActiveConversations, long activeConversations) {

        static AgentResponse from(Agent agent, long activeConversations) {
            return new AgentResponse(agent.getId(), agent.getUserId(), agent.getDisplayName(), agent.getStatus(),
                    agent.getMaxActiveConversations(), activeConversations);
        }
    }

    public record UpdateAgentRequest(AgentStatus status, @Min(1) @Max(100) Integer maxActiveConversations) {
    }
}
