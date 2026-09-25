package com.supportmind.ai;

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
import static com.supportmind.support.AiServiceStubs.suggestEndpoint;
import static com.supportmind.support.AiServiceStubs.suggestion;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** "Suggest response": la IA propone y el agente acepta, edita o rechaza. */
class SuggestionIntegrationTest extends IntegrationTest {

    @Test
    void agentsAcceptEditOrRejectSuggestions() throws Exception {
        AI_SERVICE.stubFor(suggestEndpoint().willReturn(suggestion(
                "Hola, puedes solicitar el reembolso dentro de los 30 días siguientes a la entrega.", 0.91)));
        Session admin = registerOrganization();
        Session customer = registerCustomer(admin);
        UUID conversationId = openConversation(customer, "¿Hasta cuándo puedo devolver un producto?");
        awaitConversation(customer, conversationId, hasMessageFrom("AI"));
        String base = "/api/v1/conversations/" + conversationId + "/ai/suggestions/";

        JsonNode first = json(mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/ai/suggest")
                        .header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.confidence").value(0.91))
                .andExpect(jsonPath("$.sources[0].title").value("Política de reembolsos")));
        AI_SERVICE.verify(postRequestedFor(urlEqualTo("/api/v1/ai/suggest"))
                .withRequestBody(matchingJsonPath("$.customer_message",
                        equalTo("¿Hasta cuándo puedo devolver un producto?")))
                .withRequestBody(matchingJsonPath("$.organization_id", equalTo(admin.organizationId().toString()))));

        // La sugerencia no se envía sola: el cliente todavía no la ve
        assertThat(hasMessageFrom("AGENT").test(getJson(customer, "/api/v1/conversations/" + conversationId)))
                .isFalse();

        JsonNode sent = json(mockMvc.perform(post(base + first.path("id").asText() + "/accept")
                        .header(HttpHeaders.AUTHORIZATION, admin.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"Hola Ana, puedes pedir el reembolso dentro de los 30 días.\"}"))
                .andExpect(status().isCreated()));
        assertThat(sent.path("senderType").asText()).isEqualTo("AGENT");
        assertThat(sent.path("metadata").path("suggestion").path("edited").asBoolean()).isTrue();
        mockMvc.perform(post(base + first.path("id").asText() + "/reject").header(HttpHeaders.AUTHORIZATION,
                        admin.bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUGGESTION_ALREADY_REVIEWED"));

        String second = json(mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/ai/suggest")
                        .header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isCreated())).path("id").asText();
        mockMvc.perform(post(base + second + "/reject").header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        JsonNode suggestions = json(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/conversations/" + conversationId + "/ai/suggestions")
                        .header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isOk()));
        assertThat(suggestions.findValuesAsText("status")).containsExactlyInAnyOrder("EDITED", "REJECTED");
        assertThat(getJson(admin, "/api/v1/audit-logs").path("content").findValuesAsText("event"))
                .contains("AI_SUGGESTION_ACCEPTED", "AI_SUGGESTION_REJECTED");
    }

    @Test
    void aSuggestionNeedsACustomerMessage() throws Exception {
        Session admin = registerOrganization();
        JsonNode customer = json(mockMvc.perform(post("/api/v1/customers").header(HttpHeaders.AUTHORIZATION,
                        admin.bearer()).contentType(MediaType.APPLICATION_JSON).content("{\"fullName\": \"Pedro\"}"))
                .andExpect(status().isCreated()));
        String conversationId = json(mockMvc.perform(post("/api/v1/conversations")
                        .header(HttpHeaders.AUTHORIZATION, admin.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\": \"%s\", \"channel\": \"EMAIL\"}".formatted(customer.path("id").asText())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.channel").value("EMAIL"))).path("id").asText();
        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/ai/suggest")
                        .header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isBadRequest());
    }
}
