package com.supportmind.agent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentRepository extends JpaRepository<Agent, UUID> {

    Optional<Agent> findByUserId(UUID userId);

    Optional<Agent> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<Agent> findByOrganizationIdOrderByDisplayName(UUID organizationId);

    List<Agent> findByIdIn(Collection<UUID> ids);

    List<Agent> findByUserIdIn(Collection<UUID> userIds);
}
