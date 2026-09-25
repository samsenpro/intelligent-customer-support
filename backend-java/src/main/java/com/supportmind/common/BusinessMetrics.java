package com.supportmind.common;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Métricas de negocio y de IA en Micrometer. En Prometheus aparecen como conversation_created_total,
 * ticket_created_total, human_handoff_total, ai_requests_total, ai_errors_total y ai_latency_seconds.
 */
@Component
public class BusinessMetrics {

    private final MeterRegistry registry;

    public BusinessMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void conversationCreated(String channel) {
        Counter.builder("conversation.created").description("Conversations created")
                .tag("channel", channel).register(registry).increment();
    }

    public void ticketCreated(String source, String priority) {
        Counter.builder("ticket.created").description("Tickets created")
                .tag("source", source).tag("priority", priority).register(registry).increment();
    }

    public void humanHandoff(String reason) {
        Counter.builder("human.handoff").description("Conversations handed off from the AI to a human agent")
                .tag("reason", reason).register(registry).increment();
    }

    public void aiRequest(String operation, String outcome, Duration latency) {
        Counter.builder("ai.requests").description("Requests sent to the AI service")
                .tag("operation", operation).tag("outcome", outcome).register(registry).increment();
        Timer.builder("ai.latency").description("Latency of the AI service calls")
                .tag("operation", operation).register(registry).record(latency);
    }

    public void aiError(String operation, String errorCode) {
        Counter.builder("ai.errors").description("Failed requests to the AI service")
                .tag("operation", operation).tag("error_code", errorCode).register(registry).increment();
    }

    public void aiResponse(String status) {
        Counter.builder("ai.responses").description("AI replies to customers by result")
                .tag("status", status).register(registry).increment();
    }
}
