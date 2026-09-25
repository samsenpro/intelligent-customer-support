package com.supportmind.organization;

import com.fasterxml.jackson.databind.JsonNode;
import com.supportmind.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Una organización nunca puede consultar ni modificar datos de otra (SaaS multi-tenant). */
class MultiTenancyIntegrationTest extends IntegrationTest {

    @Test
    void noResourceOfAnotherOrganizationIsVisibleOrModifiable() throws Exception {
        Session acme = registerOrganization();
        Session acmeCustomer = registerCustomer(acme);
        UUID conversation = openConversation(acmeCustomer, "Consulta de Acme");
        awaitConversation(acme, conversation, hasMessageFrom("AI"));
        JsonNode customer = json(mockMvc.perform(post("/api/v1/customers").header(HttpHeaders.AUTHORIZATION,
                        acme.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\": \"Cliente Acme\", \"email\": \"cliente@acme.example\"}"))
                .andExpect(status().isCreated()));
        JsonNode ticket = json(mockMvc.perform(post("/api/v1/tickets").header(HttpHeaders.AUTHORIZATION, acme.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\": \"%s\", \"subject\": \"S\", \"description\": \"D\"}"
                                .formatted(customer.path("id").asText())))
                .andExpect(status().isCreated()));
        JsonNode document = json(mockMvc.perform(post("/api/v1/knowledge").header(HttpHeaders.AUTHORIZATION,
                        acme.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Interno\", \"content\": \"Política interna\", \"type\": \"POLICY\"}"))
                .andExpect(status().isCreated()));

        Session globex = registerOrganization();
        String[] foreign = {
                "/api/v1/conversations/" + conversation,
                "/api/v1/conversations/" + conversation + "/messages",
                "/api/v1/customers/" + customer.path("id").asText(),
                "/api/v1/tickets/" + ticket.path("id").asText(),
                "/api/v1/knowledge/" + document.path("id").asText(),
        };
        for (String path : foreign) {
            mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, globex.bearer()))
                    .andExpect(status().isNotFound());
        }
        mockMvc.perform(patch("/api/v1/tickets/" + ticket.path("id").asText())
                        .header(HttpHeaders.AUTHORIZATION, globex.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CLOSED\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/knowledge/" + document.path("id").asText())
                        .header(HttpHeaders.AUTHORIZATION, globex.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"x\", \"content\": \"x\", \"type\": \"FAQ\"}"))
                .andExpect(status().isNotFound());
        sendMessage(globex, conversation, "hola").andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/conversations").header(HttpHeaders.AUTHORIZATION, globex.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\": \"%s\"}".formatted(customer.path("id").asText())))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/conversations/" + conversation + "/assign")
                        .header(HttpHeaders.AUTHORIZATION, globex.bearer()))
                .andExpect(status().isNotFound());

        // Los listados solo contienen datos propios
        for (String path : new String[]{"/api/v1/conversations", "/api/v1/customers", "/api/v1/tickets",
                "/api/v1/knowledge", "/api/v1/audit-logs"}) {
            assertThat(ids(getJson(globex, path)))
                    .as(path).doesNotContain(conversation.toString(), customer.path("id").asText(),
                            ticket.path("id").asText(), document.path("id").asText());
        }
        assertThat(getJson(globex, "/api/v1/analytics").path("overview").path("totalConversations").asInt()).isZero();
    }

    @Test
    void theAiServiceAlwaysReceivesTheOrganizationOfTheAuthenticatedUser() throws Exception {
        Session acme = registerOrganization();
        Session customer = registerCustomer(acme);
        UUID conversation = openConversation(customer, "¿Cuánto tarda el reembolso?");
        awaitConversation(customer, conversation, hasMessageFrom("AI"));
        mockMvc.perform(get("/api/v1/knowledge/search?q=reembolso").header(HttpHeaders.AUTHORIZATION, acme.bearer()))
                .andExpect(status().isOk());

        // La búsqueda vectorial y el RAG siempre van filtrados por la organización del usuario (nunca del cliente HTTP)
        AI_SERVICE.verify(postRequestedFor(urlEqualTo("/api/v1/knowledge/search"))
                .withRequestBody(matchingJsonPath("$.organization_id", equalTo(acme.organizationId().toString()))));
        AI_SERVICE.verify(postRequestedFor(urlEqualTo("/api/v1/ai/chat"))
                .withRequestBody(matchingJsonPath("$.conversation_id", equalTo(conversation.toString())))
                .withRequestBody(matchingJsonPath("$.organization_id", equalTo(acme.organizationId().toString()))));
        AI_SERVICE.verify(0, getRequestedFor(urlEqualTo("/api/v1/knowledge/search")));
    }

    @Test
    void customersCannotJoinAnOrganizationThatDoesNotExist() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "x-%s@example.com", "password": "Sup3r-Secret-Pass", "fullName": "X",
                                 "organizationSlug": "does-not-exist"}""".formatted(UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }
}
