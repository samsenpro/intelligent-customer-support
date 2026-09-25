package com.supportmind.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.supportmind.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static com.supportmind.support.AiServiceStubs.chatEndpoint;
import static com.supportmind.support.AiServiceStubs.chatStream;
import static com.supportmind.support.AiServiceStubs.classification;
import static com.supportmind.support.AiServiceStubs.classifyEndpoint;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AnalyticsIntegrationTest extends IntegrationTest {

    @Test
    void dashboardMetricsReflectTheActivityOfTheOrganization() throws Exception {
        Session admin = registerOrganization();
        Session supervisor = createMember(admin, "SUPERVISOR");
        Session customer = registerCustomer(admin);

        // Conversación resuelta por la IA
        UUID answered = openConversation(customer, "¿Cuánto tarda el envío?");
        awaitConversation(customer, answered, hasMessageFrom("AI"));
        // Conversación derivada a un humano
        AI_SERVICE.stubFor(classifyEndpoint().willReturn(classification("HUMAN_REQUEST", "GENERAL", "MEDIUM")));
        UUID handedOff = openConversation(customer, "Quiero hablar con una persona");
        awaitConversation(customer, handedOff, hasMessageFrom("SYSTEM"));

        JsonNode analytics = getJson(supervisor, "/api/v1/analytics");
        JsonNode overview = analytics.path("overview");
        assertThat(overview.path("totalConversations").asInt()).isEqualTo(2);
        assertThat(overview.path("openTickets").asInt()).isEqualTo(1);
        assertThat(overview.path("aiHandledConversations").asInt()).isEqualTo(2);
        assertThat(overview.path("aiResolutionRate").asDouble()).isEqualTo(0.5);
        assertThat(overview.path("humanHandoffRate").asDouble()).isEqualTo(0.5);
        assertThat(overview.path("averageAiConfidence").asDouble()).isEqualTo(0.86);
        assertThat(overview.path("averageResponseTimeSeconds").asDouble()).isGreaterThan(0);
        assertThat(overview.path("totalMessages").asInt()).isEqualTo(4);

        JsonNode days = analytics.path("messagesPerDay");
        assertThat(days).hasSize(30);
        JsonNode today = days.get(days.size() - 1);
        assertThat(today.path("date").asText()).isEqualTo(LocalDate.now(ZoneOffset.UTC).toString());
        assertThat(today.path("customer").asInt()).isEqualTo(2);
        assertThat(today.path("ai").asInt()).isEqualTo(1);

        assertThat(analytics.path("ticketsByPriority").path("HIGH").asInt()).isEqualTo(1);
        assertThat(analytics.path("ticketsByCategory").path("GENERAL").asInt()).isEqualTo(1);
        assertThat(analytics.path("ticketsByPriority").fieldNames()).toIterable()
                .containsExactly("LOW", "MEDIUM", "HIGH", "URGENT");
    }

    @Test
    void specificEndpointsAndRangeValidation() throws Exception {
        Session admin = registerOrganization();
        mockMvc.perform(get("/api/v1/analytics/overview").header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalConversations").value(0))
                .andExpect(jsonPath("$.aiResolutionRate").isEmpty());
        mockMvc.perform(get("/api/v1/analytics/messages-per-day?from=2026-01-01&to=2026-01-07")
                        .header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7));
        mockMvc.perform(get("/api/v1/analytics/tickets-by-category").header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.SECURITY").value(0));
        mockMvc.perform(get("/api/v1/analytics?from=2026-02-01&to=2026-01-01")
                        .header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/analytics?from=2024-01-01&to=2026-01-01")
                        .header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resultsAreCachedInRedis() throws Exception {
        AI_SERVICE.stubFor(chatEndpoint().willReturn(chatStream("ANSWERED", "Respuesta", 0.8)));
        Session admin = registerOrganization();
        getJson(admin, "/api/v1/analytics");
        assertThat(redis.keys("cache:analytics::" + admin.organizationId() + ":*")).hasSize(1);
    }
}
