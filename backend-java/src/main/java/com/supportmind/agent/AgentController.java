package com.supportmind.agent;

import com.supportmind.agent.AgentDtos.AgentResponse;
import com.supportmind.agent.AgentDtos.UpdateAgentRequest;
import com.supportmind.auth.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agents")
@Tag(name = "Agents", description = "Support team members and their availability")
@SecurityRequirement(name = "bearerAuth")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(summary = "List the agents of the organization with their current load")
    public List<AgentResponse> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return agentService.list(user);
    }

    @GetMapping("/me")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @Operation(summary = "Agent profile of the authenticated user")
    public AgentResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
        return agentService.me(user);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @Operation(summary = "Change the availability (own profile) or the capacity (supervisors) of an agent")
    public AgentResponse update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
                                @Valid @RequestBody UpdateAgentRequest request) {
        return agentService.update(user, id, request);
    }
}
