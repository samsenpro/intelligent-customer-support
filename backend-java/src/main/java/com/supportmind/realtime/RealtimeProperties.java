package com.supportmind.realtime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param channel           canal de Redis Pub/Sub compartido por todas las instancias
 * @param sseTimeout        duración máxima de una conexión SSE (el cliente se reconecta)
 * @param heartbeatInterval comentario periódico que mantiene viva la conexión a través de proxies
 */
@Validated
@ConfigurationProperties(prefix = "supportmind.realtime")
public record RealtimeProperties(@NotBlank String channel, @NotNull Duration sseTimeout,
                                 @NotNull Duration heartbeatInterval) {
}
