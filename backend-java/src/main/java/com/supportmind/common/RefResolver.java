package com.supportmind.common;

import com.supportmind.agent.Agent;
import com.supportmind.agent.AgentRepository;
import com.supportmind.common.Refs.AgentRef;
import com.supportmind.common.Refs.CustomerRef;
import com.supportmind.customer.Customer;
import com.supportmind.customer.CustomerRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Carga por lotes los clientes y agentes referenciados en un listado: una consulta por tipo en
 * lugar de una por fila (evita el problema N+1 sin relaciones JPA entre agregados). Los mapas
 * admiten claves nulas: {@code get(null)} devuelve null (p. ej. una conversación sin agente asignado).
 */
@Component
public class RefResolver {

    private final CustomerRepository customerRepository;
    private final AgentRepository agentRepository;

    public RefResolver(CustomerRepository customerRepository, AgentRepository agentRepository) {
        this.customerRepository = customerRepository;
        this.agentRepository = agentRepository;
    }

    public Map<UUID, CustomerRef> customers(Collection<UUID> ids) {
        var distinct = ids.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (distinct.isEmpty()) {
            return new HashMap<>();
        }
        return customerRepository.findByIdIn(distinct).stream()
                .collect(Collectors.toMap(Customer::getId, c -> new CustomerRef(c.getId(), c.getFullName(), c.getEmail()),
                        (a, b) -> a, HashMap::new));
    }

    public Map<UUID, AgentRef> agents(Collection<UUID> ids) {
        var distinct = ids.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (distinct.isEmpty()) {
            return new HashMap<>();
        }
        return agentRepository.findByIdIn(distinct).stream()
                .collect(Collectors.toMap(Agent::getId, a -> new AgentRef(a.getId(), a.getDisplayName()), (a, b) -> a,
                        HashMap::new));
    }

    public CustomerRef customer(UUID id) {
        return id == null ? null : customers(List.of(id)).get(id);
    }

    public AgentRef agent(UUID id) {
        return id == null ? null : agents(List.of(id)).get(id);
    }
}
