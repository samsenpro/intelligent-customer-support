package com.supportmind.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.supportmind.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.supportmind.support.AiServiceStubs.chatEndpoint;
import static com.supportmind.support.AiServiceStubs.chatStream;
import static com.supportmind.support.AiServiceStubs.classification;
import static com.supportmind.support.AiServiceStubs.classifyEndpoint;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Escalamiento a humano: AI -> HANDOFF -> Ticket -> cola de agentes. */
class HandoffIntegrationTest extends IntegrationTest {

    @Test
    void customerAskingForAHumanIsHandedOffWithoutCallingTheLlm() throws Exception {
        AI_SERVICE.stubFor(classifyEndpoint().willReturn(classification("HUMAN_REQUEST", "GENERAL", "MEDIUM")));
        Session admin = registerOrganization();
        Session customer = registerCustomer(admin);
        Session agent = createMember(admin, "AGENT");

        UUID conversationId = openConversation(customer, "Quiero hablar con un agente humano");
        JsonNode detail = awaitConversation(customer, conversationId, hasMessageFrom("SYSTEM"));

        JsonNode system = lastMessageFrom(detail, "SYSTEM");
        assertThat(system.path("metadata").path("systemEvent").asText()).isEqualTo("AI_HANDOFF_REQUESTED");
        assertThat(system.path("content").asText()).contains("agente");
        assertThat(detail.path("conversation").path("aiEnabled").asBoolean()).isFalse();
        assertThat(detail.path("conversation").path("handoffReason").asText()).isEqualTo("CUSTOMER_REQUESTED_HUMAN");
        assertThat(hasMessageFrom("AI").test(detail)).isFalse();
        AI_SERVICE.verify(0, postRequestedFor(urlEqualTo("/api/v1/ai/chat"))
                .withRequestBody(matchingJsonPath("$.conversation_id", equalTo(conversationId.toString()))));

        // Ticket de la derivación, con prioridad alta (el cliente pidió un humano)
        JsonNode ticket = detail.path("openTicket");
        assertThat(ticket.path("source").asText()).isEqualTo("AI_HANDOFF");
        assertThat(ticket.path("priority").asText()).isEqualTo("HIGH");

        // La conversación entra en la cola de agentes; el agente la ve y la toma
        JsonNode queue = getJson(agent, "/api/v1/conversations?view=QUEUE");
        assertThat(ids(queue)).contains(conversationId.toString());
        JsonNode handoff = getJson(agent, "/api/v1/conversations/" + conversationId + "/handoff");
        assertThat(handoff.path("reason").asText()).isEqualTo("CUSTOMER_REQUESTED_HUMAN");
        assertThat(handoff.path("customer").path("fullName").asText()).isEqualTo("Cliente Prueba");

        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/assign")
                        .header(HttpHeaders.AUTHORIZATION, agent.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedAgent.id").value(agent.agentId().toString()))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        assertThat(ids(getJson(agent, "/api/v1/conversations?view=QUEUE")))
                .doesNotContain(conversationId.toString());
    }

    @Test
    void lowConfidenceAnswersAreNotSentAndTheAgentReceivesTheContext() throws Exception {
        AI_SERVICE.stubFor(chatEndpoint().willReturn(chatStream("ANSWERED", "Quizás tarde unos días.", 0.31,
                "ORDER_STATUS", "SHIPPING", "MEDIUM", null)));
        Session admin = registerOrganization();
        Session customer = registerCustomer(admin);

        UUID conversationId = openConversation(customer, "¿Mi pedido llega hoy?");
        JsonNode detail = awaitConversation(customer, conversationId, hasMessageFrom("SYSTEM"));

        assertThat(hasMessageFrom("AI").test(detail)).isFalse();
        assertThat(detail.path("conversation").path("handoffReason").asText()).isEqualTo("LOW_CONFIDENCE");
        JsonNode staffView = getJson(admin, "/api/v1/conversations/" + conversationId);
        assertThat(staffView.path("handoff").path("reason").asText()).isEqualTo("LOW_CONFIDENCE");
        assertThat(staffView.path("handoff").path("aiConfidence").asDouble()).isEqualTo(0.31);
        assertThat(lastMessageFrom(staffView, "SYSTEM").path("metadata").path("aiConfidence").asDouble())
                .isEqualTo(0.31);
        assertThat(jdbc.queryForObject("SELECT status FROM ai_responses WHERE conversation_id = ?", String.class,
                conversationId)).isEqualTo("AI_HANDOFF_REQUESTED");
    }

    @Test
    void sensitiveCategoriesAlwaysGoToAHuman() throws Exception {
        AI_SERVICE.stubFor(classifyEndpoint().willReturn(classification("FRAUD_REPORT", "SECURITY", "HIGH")));
        Session admin = registerOrganization();
        Session customer = registerCustomer(admin);

        UUID conversationId = openConversation(customer, "Hay compras en mi cuenta que no hice");
        JsonNode detail = awaitConversation(customer, conversationId, hasMessageFrom("SYSTEM"));
        assertThat(detail.path("conversation").path("handoffReason").asText()).isEqualTo("SENSITIVE_CATEGORY");
        assertThat(detail.path("openTicket").path("category").asText()).isEqualTo("SECURITY");
        AI_SERVICE.verify(0, postRequestedFor(urlEqualTo("/api/v1/ai/chat"))
                .withRequestBody(matchingJsonPath("$.conversation_id", equalTo(conversationId.toString()))));
    }

    @Test
    void urgentMessagesAreHandedOffWhenTheOrganizationSaysSo() throws Exception {
        AI_SERVICE.stubFor(classifyEndpoint().willReturn(classification("PAYMENT_ISSUE", "BILLING", "URGENT")));
        Session admin = registerOrganization();
        Session customer = registerCustomer(admin);
        UUID conversationId = openConversation(customer, "Me cobraron dos veces, es urgente");
        JsonNode detail = awaitConversation(customer, conversationId, hasMessageFrom("SYSTEM"));
        assertThat(detail.path("conversation").path("handoffReason").asText()).isEqualTo("URGENT_TICKET");
        assertThat(detail.path("openTicket").path("priority").asText()).isEqualTo("URGENT");
    }

    @Test
    void repeatedAnswersWithoutContextEndInAHandoff() throws Exception {
        AI_SERVICE.stubFor(chatEndpoint().willReturn(chatStream("NO_RELEVANT_CONTEXT",
                "No encontré información sobre eso. ¿Me das más detalles?", 0.0, "GENERAL_QUESTION", "GENERAL",
                "LOW", null)));
        Session admin = registerOrganization();
        Session customer = registerCustomer(admin);

        UUID conversationId = openConversation(customer, "¿Venden repuestos de tractores?");
        JsonNode first = awaitConversation(customer, conversationId, hasMessageFrom("AI"));
        // Primera vez: pide más información (configuración ASK_MORE_INFO), sin derivar
        assertThat(lastMessageFrom(first, "AI").path("metadata").path("ai").path("status").asText())
                .isEqualTo("NO_RELEVANT_CONTEXT");
        assertThat(first.path("conversation").path("aiEnabled").asBoolean()).isTrue();

        sendMessage(customer, conversationId, "Repuestos para tractor John Deere").andExpect(status().isCreated());
        JsonNode second = awaitConversation(customer, conversationId, hasMessageFrom("SYSTEM"));
        assertThat(second.path("conversation").path("handoffReason").asText()).isEqualTo("REPEATED_FAILED_ANSWERS");
    }

    @Test
    void organizationsCanPreferAnImmediateHandoffWhenThereIsNoContext() throws Exception {
        AI_SERVICE.stubFor(chatEndpoint().willReturn(chatStream("NO_RELEVANT_CONTEXT",
                "Te comunico con un agente.", 0.0, "GENERAL_QUESTION", "GENERAL", "LOW", "NO_RELEVANT_CONTEXT")));
        Session admin = registerOrganization();
        updateAiSettings(admin, "\"noContextAction\": \"HANDOFF\"");
        Session customer = registerCustomer(admin);

        UUID conversationId = openConversation(customer, "¿Tienen servicio de lavado de autos?");
        JsonNode detail = awaitConversation(customer, conversationId, hasMessageFrom("SYSTEM"));
        assertThat(detail.path("conversation").path("handoffReason").asText()).isEqualTo("NO_RELEVANT_CONTEXT");
    }

    @Test
    void theAiNeverRepliesWhenAutoReplyIsDisabled() throws Exception {
        Session admin = registerOrganization();
        updateAiSettings(admin, "\"autoReplyEnabled\": false");
        Session customer = registerCustomer(admin);

        UUID conversationId = openConversation(customer, "¿Cuánto cuesta el envío?");
        // El mensaje se analiza igualmente (información para el agente)...
        JsonNode detail = awaitConversation(admin, conversationId,
                d -> lastMessageFrom(d, "CUSTOMER").path("metadata").has("analysis"));
        // ...pero la conversación la atiende un humano desde el principio
        assertThat(detail.path("conversation").path("aiEnabled").asBoolean()).isFalse();
        Thread.sleep(300);
        assertThat(getJson(admin, "/api/v1/conversations/" + conversationId).path("messages").size()).isEqualTo(1);
    }
}
