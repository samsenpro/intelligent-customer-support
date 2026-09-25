package com.supportmind.ai.jobs;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mensaje de la cola. Solo lleva identificadores: el handler lee el estado actual de la base de
 * datos, así un mensaje repetido o antiguo nunca aplica datos obsoletos.
 *
 * @param entityId      mensaje, conversación o documento, según el tipo
 * @param correlationId el de la petición que originó el job: permite seguirlo en los logs hasta Python
 */
public record AiJob(AiJobType type, UUID organizationId, UUID entityId, String correlationId, Instant enqueuedAt) {

    public static AiJob of(AiJobType type, UUID organizationId, UUID entityId, String correlationId) {
        return new AiJob(type, organizationId, entityId, correlationId, Instant.now());
    }

    public Map<String, String> toMap() {
        Map<String, String> values = new HashMap<>();
        values.put("type", type.name());
        values.put("organizationId", organizationId.toString());
        values.put("entityId", entityId.toString());
        values.put("correlationId", correlationId == null ? "" : correlationId);
        values.put("enqueuedAt", enqueuedAt.toString());
        return values;
    }

    public static AiJob fromMap(Map<String, String> values) {
        String correlationId = values.get("correlationId");
        return new AiJob(
                AiJobType.valueOf(values.get("type")),
                UUID.fromString(values.get("organizationId")),
                UUID.fromString(values.get("entityId")),
                correlationId == null || correlationId.isBlank() ? null : correlationId,
                Instant.parse(values.get("enqueuedAt")));
    }
}
