package com.supportmind.ai;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * @param defaultConfidenceThreshold umbral de las organizaciones nuevas
 * @param topK                       fragmentos de la base de conocimiento por respuesta
 * @param historyMessages            últimos N mensajes que forman la memoria de la conversación
 * @param contextTtl                 vida en Redis de la memoria de una conversación inactiva
 * @param resummarizeEvery           mensajes nuevos desde el último resumen para volver a resumir
 * @param processingTtl              tiempo máximo que una conversación queda marcada como "IA procesando"
 */
@Validated
@ConfigurationProperties(prefix = "supportmind.ai")
public record AiProperties(
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal defaultConfidenceThreshold,
        @Min(1) @Max(20) int topK,
        @Min(1) @Max(50) int historyMessages,
        @NotNull Duration contextTtl,
        @Min(1) int resummarizeEvery,
        @NotNull Duration processingTtl) {
}
