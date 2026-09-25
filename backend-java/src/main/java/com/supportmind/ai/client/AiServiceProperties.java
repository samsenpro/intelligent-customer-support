package com.supportmind.ai.client;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param url           URL base del servicio de IA (Python)
 * @param apiKey        clave interna compartida: solo este backend puede llamar al servicio de IA
 * @param readTimeout   operaciones cortas (clasificación, búsqueda, sugerencias, indexación)
 * @param streamTimeout duración máxima de una respuesta del LLM en streaming
 */
@Validated
@ConfigurationProperties(prefix = "supportmind.ai-service")
public record AiServiceProperties(
        @NotBlank String url,
        @NotBlank @Size(min = 32, message = "AI_SERVICE_API_KEY must have at least 32 characters") String apiKey,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @NotNull Duration streamTimeout) {
}
