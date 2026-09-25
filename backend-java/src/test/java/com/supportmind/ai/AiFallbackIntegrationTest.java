package com.supportmind.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.supportmind.ai.client.ResilientAiExecutor;
import com.supportmind.support.IntegrationTest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.supportmind.support.AiServiceStubs.chatEndpoint;
import static com.supportmind.support.AiServiceStubs.chatStream;
import static com.supportmind.support.AiServiceStubs.classifyEndpoint;
import static com.supportmind.support.AiServiceStubs.error;
import static com.supportmind.support.AiServiceStubs.suggestEndpoint;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Si Python no responde: Retry -> Retry -> Circuit Breaker -> Fallback ("AI temporarily unavailable")
 * y la conversación continúa con soporte humano.
 */
class AiFallbackIntegrationTest extends IntegrationTest {

    @Autowired
    private CircuitBreakerRegistry circuitBreakers;

    @Test
    void whenTheAiServiceFailsTheCustomerIsToldAndHandedOffToAHuman() throws Exception {
        AI_SERVICE.stubFor(chatEndpoint().willReturn(error(503, "VECTOR_STORE_UNAVAILABLE")));
        Session admin = registerOrganization();
        Session customer = registerCustomer(admin);

        UUID conversationId = openConversation(customer, "¿Cuánto tarda el reembolso?");
        JsonNode detail = awaitConversation(customer, conversationId, hasMessageFrom("SYSTEM"));

        JsonNode system = lastMessageFrom(detail, "SYSTEM");
        assertThat(system.path("metadata").path("systemEvent").asText()).isEqualTo("AI_UNAVAILABLE");
        assertThat(system.path("content").asText()).contains("no está disponible temporalmente");
        assertThat(detail.path("conversation").path("handoffReason").asText()).isEqualTo("AI_UNAVAILABLE");
        assertThat(detail.path("conversation").path("aiEnabled").asBoolean()).isFalse();
        assertThat(detail.path("openTicket").path("source").asText()).isEqualTo("AI_HANDOFF");

        // Tres intentos (Retry) antes del fallback
        AI_SERVICE.verify(3, postRequestedFor(urlEqualTo("/api/v1/ai/chat"))
                .withRequestBody(matchingJsonPath("$.conversation_id", equalTo(conversationId.toString()))));
        assertThat(jdbc.queryForObject("SELECT status FROM ai_responses WHERE conversation_id = ?", String.class,
                conversationId)).isEqualTo("UNAVAILABLE");

        // El cliente sigue la conversación con un agente
        sendMessage(admin, conversationId, "Hola, te atiendo yo.").andExpect(status().isCreated());
    }

    @Test
    void rejectedRequestsAreNotRetried() throws Exception {
        AI_SERVICE.stubFor(chatEndpoint().willReturn(error(422, "INVALID_REQUEST")));
        Session admin = registerOrganization();
        Session customer = registerCustomer(admin);
        UUID conversationId = openConversation(customer, "Hola, tengo una duda");
        awaitConversation(customer, conversationId, hasMessageFrom("SYSTEM"));
        AI_SERVICE.verify(1, postRequestedFor(urlEqualTo("/api/v1/ai/chat"))
                .withRequestBody(matchingJsonPath("$.conversation_id", equalTo(conversationId.toString()))));
    }

    @Test
    void slowAnswersAreCutByTheTimeLimiter() throws Exception {
        AI_SERVICE.stubFor(chatEndpoint().willReturn(chatStream("ANSWERED", "Respuesta lenta del modelo.", 0.9)
                .withFixedDelay(5_000)));
        Session admin = registerOrganization();
        Session customer = registerCustomer(admin);
        UUID conversationId = openConversation(customer, "¿Me ayudas con mi pedido?");
        JsonNode detail = awaitConversation(customer, conversationId, hasMessageFrom("SYSTEM"));
        assertThat(detail.path("conversation").path("handoffReason").asText()).isEqualTo("AI_UNAVAILABLE");
    }

    @Test
    void theCircuitOpensAfterRepeatedFailuresAndCallsFailFast() throws Exception {
        CircuitBreaker circuit = circuitBreakers.circuitBreaker(ResilientAiExecutor.INSTANCE);
        AI_SERVICE.stubFor(classifyEndpoint().willReturn(aResponse().withStatus(500)));
        AI_SERVICE.stubFor(chatEndpoint().willReturn(aResponse().withStatus(500)));
        Session admin = registerOrganization();
        Session customer = registerCustomer(admin);

        openConversation(customer, "Primera consulta");
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15))
                .until(() -> circuit.getState() == CircuitBreaker.State.OPEN);

        // Con el circuito abierto las sugerencias fallan al instante con un error claro
        UUID conversationId = openConversation(customer, "Segunda consulta");
        int before = AI_SERVICE.getAllServeEvents().size();
        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/ai/suggest")
                        .header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_TEMPORARILY_UNAVAILABLE"));
        AI_SERVICE.verify(0, postRequestedFor(urlEqualTo("/api/v1/ai/suggest")));
        assertThat(AI_SERVICE.getAllServeEvents().size() - before).isLessThanOrEqualTo(2);
    }

    @Test
    void suggestionsReportTheServiceAsTemporarilyUnavailable() throws Exception {
        AI_SERVICE.stubFor(suggestEndpoint().willReturn(error(503, "INTERNAL_ERROR")));
        Session admin = registerOrganization();
        Session customer = registerCustomer(admin);
        UUID conversationId = openConversation(customer, "¿Tienen garantía los audífonos?");
        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/ai/suggest")
                        .header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_TEMPORARILY_UNAVAILABLE"))
                .andExpect(jsonPath("$.detail").value(
                        "AI temporarily unavailable. A human agent can continue the conversation"));
    }
}
