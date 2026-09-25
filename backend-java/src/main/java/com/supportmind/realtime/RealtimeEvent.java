package com.supportmind.realtime;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * Evento en tiempo real. Viaja por Redis Pub/Sub entre instancias de la API y llega al navegador por
 * Server-Sent Events. Lleva los datos de la conversación necesarios para decidir, en cada instancia,
 * qué suscriptores pueden verlo (organización, cliente, agente asignado y cola).
 *
 * @param type tipo de evento: message.created, message.updated, conversation.updated, ai.started,
 *             ai.delta, ai.completed
 */
public record RealtimeEvent(String type, UUID organizationId, UUID conversationId, UUID customerId,
                            UUID assignedAgentId, boolean inQueue, JsonNode payload) {
}
