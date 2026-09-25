package com.supportmind.message;

import com.supportmind.agent.Agent;
import com.supportmind.agent.AgentRepository;
import com.supportmind.common.RefResolver;
import com.supportmind.common.Refs.CustomerRef;
import com.supportmind.message.MessageDtos.MessageResponse;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Convierte mensajes en respuestas con el nombre de quien los escribió, resolviendo clientes y
 * agentes por lotes. {@code senderId} es el ID del cliente (CUSTOMER) o del usuario (AGENT).
 */
@Component
public class MessageMapper {

    private final RefResolver refs;
    private final AgentRepository agentRepository;

    public MessageMapper(RefResolver refs, AgentRepository agentRepository) {
        this.refs = refs;
        this.agentRepository = agentRepository;
    }

    public MessageResponse toResponse(Message message) {
        return toResponses(List.of(message)).getFirst();
    }

    public List<MessageResponse> toResponses(List<Message> messages) {
        Map<UUID, CustomerRef> customers = refs.customers(idsOf(messages, SenderType.CUSTOMER));
        List<UUID> agentUserIds = idsOf(messages, SenderType.AGENT);
        Map<UUID, String> agents = agentUserIds.isEmpty() ? new HashMap<>()
                : agentRepository.findByUserIdIn(agentUserIds).stream()
                .collect(Collectors.toMap(Agent::getUserId, Agent::getDisplayName, (a, b) -> a, HashMap::new));
        return messages.stream().map(m -> new MessageResponse(m.getId(), m.getConversationId(), m.getSenderType(),
                m.getSenderId(), senderName(m, customers, agents), m.getContent(), m.getMetadata(),
                m.getCreatedAt())).toList();
    }

    private static String senderName(Message m, Map<UUID, CustomerRef> customers, Map<UUID, String> agents) {
        return switch (m.getSenderType()) {
            case CUSTOMER -> {
                CustomerRef customer = customers.get(m.getSenderId());
                yield customer != null ? customer.fullName() : "Customer";
            }
            case AGENT -> agents.getOrDefault(m.getSenderId(), "Agent");
            case AI -> "AI Assistant";
            case SYSTEM -> "System";
        };
    }

    private static List<UUID> idsOf(List<Message> messages, SenderType type) {
        return messages.stream().filter(m -> m.getSenderType() == type).map(Message::getSenderId)
                .filter(Objects::nonNull).distinct().toList();
    }
}
