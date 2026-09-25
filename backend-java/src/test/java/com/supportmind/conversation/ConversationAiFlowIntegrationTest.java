package com.supportmind.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.supportmind.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.supportmind.support.AiServiceStubs.classification;
import static com.supportmind.support.AiServiceStubs.classifyEndpoint;
import static com.supportmind.support.AiServiceStubs.sentiment;
import static com.supportmind.support.AiServiceStubs.sentimentEndpoint;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Flujo IA completo: Cliente -> Spring Boot -> Python (clasificación, RAG, LLM) -> Spring Boot -> Cliente. */
class ConversationAiFlowIntegrationTest extends IntegrationTest {

    @Test
    void customerMessageIsAnalyzedAndAnsweredByTheAiWithSources() throws Exception {
        AI_SERVICE.stubFor(classifyEndpoint().willReturn(classification("REFUND_REQUEST", "BILLING", "HIGH")));
        AI_SERVICE.stubFor(sentimentEndpoint().willReturn(sentiment("NEGATIVE")));
        Session admin = registerOrganization();
        Session customer = registerCustomer(admin);

        UUID conversationId = openConversation(customer, "¿Hasta cuándo puedo pedir el reembolso?");
        JsonNode detail = awaitConversation(customer, conversationId, hasMessageFrom("AI"));

        JsonNode aiMessage = lastMessageFrom(detail, "AI");
        assertThat(aiMessage.path("content").asText())
                .isEqualTo("Puedes solicitar el reembolso dentro de los 30 días siguientes a la entrega.");
        assertThat(aiMessage.path("senderName").asText()).isEqualTo("AI Assistant");
        JsonNode ai = aiMessage.path("metadata").path("ai");
        assertThat(ai.path("status").asText()).isEqualTo("ANSWERED");
        assertThat(ai.path("confidence").asDouble()).isEqualTo(0.86);
        assertThat(ai.path("sources").get(0).path("title").asText()).isEqualTo("Política de reembolsos");
        assertThat(ai.path("promptVersion").asText()).isEqualTo("customer_response_v2");

        JsonNode conversation = detail.path("conversation");
        assertThat(conversation.path("status").asText()).isEqualTo("WAITING_CUSTOMER");
        assertThat(conversation.path("aiEnabled").asBoolean()).isTrue();
        assertThat(conversation.path("lastIntent").asText()).isEqualTo("REFUND_REQUEST");
        assertThat(conversation.path("lastSentiment").asText()).isEqualTo("NEGATIVE");
        assertThat(conversation.path("lastAiConfidence").asDouble()).isEqualTo(0.86);

        // El análisis queda en los metadatos del mensaje del cliente, como información para el agente
        JsonNode analysis = lastMessageFrom(detail, "CUSTOMER").path("metadata").path("analysis");
        assertThat(analysis.path("intent").asText()).isEqualTo("REFUND_REQUEST");
        assertThat(analysis.path("sentiment").asText()).isEqualTo("NEGATIVE");

        // El reembolso requiere gestión: ticket automático por clasificación
        assertThat(detail.path("openTicket").path("source").asText()).isEqualTo("AUTO_CLASSIFICATION");
        assertThat(detail.path("openTicket").path("priority").asText()).isEqualTo("HIGH");
        assertThat(detail.path("openTicket").path("category").asText()).isEqualTo("BILLING");
    }

    @Test
    void theChatRequestCarriesTheTenantMemoryAndTheIntentHint() throws Exception {
        AI_SERVICE.stubFor(classifyEndpoint().willReturn(classification("ORDER_STATUS", "SHIPPING", "MEDIUM")));
        Session admin = registerOrganization();
        Session customer = registerCustomer(admin);
        UUID conversationId = openConversation(customer, "Hola, compré unos audífonos");
        awaitConversation(customer, conversationId, hasMessageFrom("AI"));

        sendMessage(customer, conversationId, "¿Cuándo llega mi pedido 4521?").andExpect(status().isCreated());
        awaitConversation(customer, conversationId, d -> d.path("conversation").path("messageCount").asInt() == 4);

        AI_SERVICE.verify(postRequestedFor(urlEqualTo("/api/v1/ai/chat"))
                .withHeader("X-Internal-Api-Key", equalTo(INTERNAL_API_KEY))
                .withHeader("X-Correlation-Id", com.github.tomakehurst.wiremock.client.WireMock.matching(".+"))
                .withRequestBody(matchingJsonPath("$.organization_id", equalTo(admin.organizationId().toString())))
                .withRequestBody(matchingJsonPath("$.message", equalTo("¿Cuándo llega mi pedido 4521?")))
                .withRequestBody(matchingJsonPath("$.stream", equalTo("true")))
                .withRequestBody(matchingJsonPath("$.intent_hint.intent", equalTo("ORDER_STATUS")))
                .withRequestBody(matchingJsonPath("$.settings.no_context_action", equalTo("ASK_MORE_INFO")))
                // Memoria: el mensaje y la respuesta anteriores viajan como historial
                .withRequestBody(matchingJsonPath("$.history[0].content", equalTo("Hola, compré unos audífonos")))
                .withRequestBody(matchingJsonPath("$.history[1].role", equalTo("AI"))));

        // Memoria de la conversación en Redis (conversation:context:{id})
        assertThat(redis.opsForList().size("conversation:context:" + conversationId)).isEqualTo(4);
    }

    @Test
    void anAgentReplyTakesOverTheConversationAndStopsTheAi() throws Exception {
        Session admin = registerOrganization();
        Session customer = registerCustomer(admin);
        Session agent = createMember(admin, "AGENT");
        UUID conversationId = openConversation(customer, "Necesito ayuda con mi pedido");
        awaitConversation(customer, conversationId, hasMessageFrom("AI"));

        // Un agente solo ve conversaciones asignadas o en cola: el supervisor/admin la responde y la toma
        sendMessage(admin, conversationId, "Hola, soy Laura y te ayudo con tu pedido.").andExpect(status().isCreated());
        JsonNode detail = getJson(admin, "/api/v1/conversations/" + conversationId);
        assertThat(detail.path("conversation").path("aiEnabled").asBoolean()).isFalse();
        assertThat(detail.path("conversation").path("handoffReason").asText()).isEqualTo("AGENT_TOOK_OVER");
        assertThat(detail.path("conversation").path("assignedAgent").path("id").asText())
                .isEqualTo(admin.agentId().toString());
        assertThat(lastMessageFrom(detail, "AGENT").path("senderName").asText()).isEqualTo("Admin");

        int chatCalls = AI_SERVICE.findAll(postRequestedFor(urlEqualTo("/api/v1/ai/chat"))).size();
        sendMessage(customer, conversationId, "Gracias, espero tu respuesta").andExpect(status().isCreated());
        awaitConversation(admin, conversationId, d -> d.path("messages").get(d.path("messages").size() - 1)
                .path("metadata").has("analysis"));
        // La IA ya no responde en una conversación atendida por un humano
        assertThat(AI_SERVICE.findAll(postRequestedFor(urlEqualTo("/api/v1/ai/chat")))).hasSize(chatCalls);
        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, agent.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\": \"hola\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void idempotencyKeyPreventsDuplicatedMessages() throws Exception {
        Session admin = registerOrganization();
        Session customer = registerCustomer(admin);
        UUID conversationId = openConversation(customer, "Hola");
        String url = "/api/v1/conversations/" + conversationId + "/messages";

        JsonNode first = json(mockMvc.perform(post(url).header(HttpHeaders.AUTHORIZATION, customer.bearer())
                        .header("X-Idempotency-Key", "msg-key-0001").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"Mi pedido no llegó\"}"))
                .andExpect(status().isCreated()));
        mockMvc.perform(post(url).header(HttpHeaders.AUTHORIZATION, customer.bearer())
                        .header("X-Idempotency-Key", "msg-key-0001").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"Mi pedido no llegó\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(first.path("id").asText()));
        mockMvc.perform(post(url).header(HttpHeaders.AUTHORIZATION, customer.bearer())
                        .header("X-Idempotency-Key", "msg-key-0001").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"Otro contenido\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        long customerMessages = getJson(customer, "/api/v1/conversations/" + conversationId + "/messages")
                .path("content").findValuesAsText("senderType").stream().filter("CUSTOMER"::equals).count();
        assertThat(customerMessages).isEqualTo(2);
    }

    @Test
    void closedConversationsDoNotAcceptMessages() throws Exception {
        Session admin = registerOrganization();
        Session customer = registerCustomer(admin);
        UUID conversationId = openConversation(customer, "Hola");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/conversations/" + conversationId)
                        .header(HttpHeaders.AUTHORIZATION, admin.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CLOSED\"}"))
                .andExpect(status().isOk());
        sendMessage(customer, conversationId, "¿Sigue ahí?")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONVERSATION_CLOSED"));
    }

    @Test
    void customersCanOnlyMarkTheirConversationAsResolved() throws Exception {
        Session admin = registerOrganization();
        Session customer = registerCustomer(admin);
        UUID conversationId = openConversation(customer, "Hola");
        var patch = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .patch("/api/v1/conversations/" + conversationId)
                .header(HttpHeaders.AUTHORIZATION, customer.bearer()).contentType(MediaType.APPLICATION_JSON);
        mockMvc.perform(patch.content("{\"aiEnabled\": false}")).andExpect(status().isForbidden());
        mockMvc.perform(patch.content("{\"status\": \"RESOLVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));
    }
}
