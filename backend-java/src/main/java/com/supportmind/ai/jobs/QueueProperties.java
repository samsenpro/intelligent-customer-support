package com.supportmind.ai.jobs;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param consumerName   nombre de este consumidor dentro del grupo (uno distinto por instancia)
 * @param concurrency    workers en paralelo en esta instancia
 * @param abandonedAfter un mensaje entregado y sin confirmar durante este tiempo se considera
 *                       abandonado (su consumidor murió) y se vuelve a encolar
 */
@Validated
@ConfigurationProperties(prefix = "supportmind.queue")
public record QueueProperties(
        @NotBlank String stream,
        @NotBlank String group,
        @NotBlank String consumerName,
        @Min(1) int concurrency,
        @NotNull Duration pollTimeout,
        @NotNull Duration abandonedAfter,
        @Positive long maxLength,
        @NotNull Duration maintenanceInterval) {
}
