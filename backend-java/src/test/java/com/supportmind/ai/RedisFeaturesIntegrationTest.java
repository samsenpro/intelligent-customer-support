package com.supportmind.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.supportmind.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.supportmind.support.AiServiceStubs.summarizeEndpoint;
import static com.supportmind.support.AiServiceStubs.summary;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Redis: memoria de conversación, resumen automático, rate limiting y estado temporal de la IA. */
class RedisFeaturesIntegrationTest extends IntegrationTest {

    @Test
    void longConversationsAreSummarizedAndTheSummaryReplacesOldMessagesInTheContext() throws Exception {
        AI_SERVICE.stubFor(summarizeEndpoint().willReturn(summary("El cliente pregunta por su pedido 4521.")));
        Session admin = registerOrganization();
        Session customer = registerCustomer(admin);
        updateAiSettings(admin, "\"summaryAfterMessages\": 4");
        UUID conversationId = openConversation(customer, "Hola, tengo dudas sobre mi pedido 4521");
        awaitConversation(customer, conversationId, hasMessageFrom("AI"));
        sendMessage(customer, conversationId, "¿Ya fue despachado?").andExpect(status().isCreated());
        awaitConversation(customer, conversationId, d -> d.path("conversation").path("messageCount").asInt() == 4);

        JsonNode detail = await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
                .until(() -> getJson(admin, "/api/v1/conversations/" + conversationId),
                        d -> !d.path("summary").isNull() && !d.path("summary").isMissingNode());
        assertThat(detail.path("summary").asText()).isEqualTo("El cliente pregunta por su pedido 4521.");
        AI_SERVICE.verify(postRequestedFor(urlEqualTo("/api/v1/ai/summarize"))
                .withRequestBody(matchingJsonPath("$.conversation_id", equalTo(conversationId.toString())))
                .withRequestBody(matchingJsonPath("$.messages[0].content",
                        equalTo("Hola, tengo dudas sobre mi pedido 4521"))));
        // Los clientes no ven el resumen interno
        assertThat(getJson(customer, "/api/v1/conversations/" + conversationId).path("summary").isNull()).isTrue();

        // La siguiente respuesta de la IA recibe el resumen como memoria
        sendMessage(customer, conversationId, "¿Cuándo llega?").andExpect(status().isCreated());
        awaitConversation(customer, conversationId, d -> d.path("conversation").path("messageCount").asInt() == 6);
        AI_SERVICE.verify(postRequestedFor(urlEqualTo("/api/v1/ai/chat"))
                .withRequestBody(matchingJsonPath("$.message", equalTo("¿Cuándo llega?")))
                .withRequestBody(matchingJsonPath("$.summary", equalTo("El cliente pregunta por su pedido 4521."))));
    }

    @Test
    void loginIsRateLimitedPerIp() throws Exception {
        String body = """
                {"email": "brute-force@example.com", "password": "Wrong-Password-1"}""";
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.policy").value("auth"));
        assertThat(redis.keys("rate-limit:auth:ip:*")).isNotEmpty();
    }

    @Test
    void aiRequestsAreRateLimitedPerUser() throws Exception {
        Session admin = registerOrganization();
        Session customer = registerCustomer(admin);
        UUID conversationId = openConversation(customer, "Hola");
        awaitConversation(customer, conversationId, hasMessageFrom("AI"));
        int limited = 0;
        for (int i = 0; i < 21; i++) {
            int status = mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/ai/suggest")
                    .header(HttpHeaders.AUTHORIZATION, admin.bearer())).andReturn().getResponse().getStatus();
            if (status == 429) {
                limited++;
            }
        }
        assertThat(limited).isEqualTo(1);
        assertThat(redis.keys("rate-limit:ai:user:" + admin.userId())).hasSize(1);
    }

    @Test
    void manualAiRepliesAreRejectedWhileTheAiIsAlreadyAnswering() throws Exception {
        Session admin = registerOrganization();
        Session customer = registerCustomer(admin);
        UUID conversationId = openConversation(customer, "Hola");
        awaitConversation(customer, conversationId, hasMessageFrom("AI"));

        redis.opsForValue().set("ai:processing:" + conversationId, "1", Duration.ofSeconds(30));
        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/ai/reply")
                        .header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AI_REPLY_IN_PROGRESS"));
        assertThat(getJson(admin, "/api/v1/conversations/" + conversationId).path("aiProcessing").asBoolean()).isTrue();
        redis.delete("ai:processing:" + conversationId);

        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/ai/reply")
                        .header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"));
        awaitConversation(admin, conversationId, d -> d.path("messages").findValuesAsText("senderType").stream()
                .filter("AI"::equals).count() == 2);
    }
}
