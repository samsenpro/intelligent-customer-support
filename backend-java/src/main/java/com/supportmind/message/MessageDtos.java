package com.supportmind.message;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class MessageDtos {

    private MessageDtos() {
    }

    public record SendMessageRequest(
            @Schema(example = "¿Cuánto tarda el reembolso de mi pedido?")
            @NotBlank @Size(max = 4000) String content) {
    }

    /**
     * Mensaje de la conversación. {@code senderType} indica claramente quién lo escribió: cliente,
     * agente humano, IA o sistema. En los de la IA, {@code metadata.ai} incluye confianza y fuentes.
     */
    public record MessageResponse(UUID id, UUID conversationId, SenderType senderType, UUID senderId,
                                  String senderName, String content, Map<String, Object> metadata,
                                  Instant createdAt) {
    }
}
