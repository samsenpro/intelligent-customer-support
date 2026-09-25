package com.supportmind.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.supportmind.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** RBAC: ADMIN, SUPERVISOR, AGENT y CUSTOMER solo pueden hacer lo que su rol permite. */
class AuthorizationIntegrationTest extends IntegrationTest {

    @Test
    void onlyAdminsManageUsersSettingsKnowledgeAndAudit() throws Exception {
        Session admin = registerOrganization();
        Session supervisor = createMember(admin, "SUPERVISOR");
        Session agent = createMember(admin, "AGENT");

        for (Session notAdmin : new Session[]{supervisor, agent}) {
            mockMvc.perform(get("/api/v1/organizations/me/users").header(HttpHeaders.AUTHORIZATION, notAdmin.bearer()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
            mockMvc.perform(get("/api/v1/audit-logs").header(HttpHeaders.AUTHORIZATION, notAdmin.bearer()))
                    .andExpect(status().isForbidden());
            mockMvc.perform(put("/api/v1/organizations/me/ai-settings").header(HttpHeaders.AUTHORIZATION,
                            notAdmin.bearer()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/v1/knowledge").header(HttpHeaders.AUTHORIZATION, notAdmin.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\": \"T\", \"content\": \"C\", \"type\": \"FAQ\"}"))
                    .andExpect(status().isForbidden());
        }
        JsonNode members = getJson(admin, "/api/v1/organizations/me/users");
        assertThat(members.path("totalElements").asInt()).isEqualTo(3);
        assertThat(getJson(admin, "/api/v1/audit-logs").path("content").findValuesAsText("event"))
                .contains("ORGANIZATION_CREATED", "USER_REGISTERED", "USER_CREATED");
    }

    @Test
    void supervisorsSeeAnalyticsAndAgentsButAgentsDoNot() throws Exception {
        Session admin = registerOrganization();
        Session supervisor = createMember(admin, "SUPERVISOR");
        Session agent = createMember(admin, "AGENT");

        mockMvc.perform(get("/api/v1/analytics").header(HttpHeaders.AUTHORIZATION, supervisor.bearer()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/agents").header(HttpHeaders.AUTHORIZATION, supervisor.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
        mockMvc.perform(get("/api/v1/analytics").header(HttpHeaders.AUTHORIZATION, agent.bearer()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/agents").header(HttpHeaders.AUTHORIZATION, agent.bearer()))
                .andExpect(status().isForbidden());
    }

    @Test
    void agentsOnlyChangeTheirOwnAvailability() throws Exception {
        Session admin = registerOrganization();
        Session agent = createMember(admin, "AGENT");
        Session other = createMember(admin, "AGENT");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/agents/" + agent.agentId()).header(HttpHeaders.AUTHORIZATION, agent.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"AVAILABLE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/agents/" + other.agentId()).header(HttpHeaders.AUTHORIZATION, agent.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"OFFLINE\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/agents/" + agent.agentId()).header(HttpHeaders.AUTHORIZATION, agent.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"maxActiveConversations\": 50}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void agentsSeeTheirConversationsAndTheQueueButNotOthers() throws Exception {
        Session admin = registerOrganization();
        Session customer = registerCustomer(admin);
        Session agentA = createMember(admin, "AGENT");
        Session agentB = createMember(admin, "AGENT");
        UUID aiHandled = openConversation(customer, "Primera conversación");
        UUID assignedToA = openConversation(customer, "Segunda conversación");
        awaitConversation(customer, assignedToA, hasMessageFrom("AI"));
        mockMvc.perform(post("/api/v1/conversations/" + assignedToA + "/assign")
                        .header(HttpHeaders.AUTHORIZATION, admin.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\": \"" + agentA.agentId() + "\"}"))
                .andExpect(status().isOk());

        assertThat(ids(getJson(agentA, "/api/v1/conversations")))
                .containsExactly(assignedToA.toString());
        assertThat(getJson(agentB, "/api/v1/conversations").path("totalElements").asInt()).isZero();
        mockMvc.perform(get("/api/v1/conversations/" + assignedToA).header(HttpHeaders.AUTHORIZATION, agentB.bearer()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/conversations/" + aiHandled).header(HttpHeaders.AUTHORIZATION, agentA.bearer()))
                .andExpect(status().isNotFound());
        // Un agente no puede asignar conversaciones a otro
        mockMvc.perform(post("/api/v1/conversations/" + assignedToA + "/assign")
                        .header(HttpHeaders.AUTHORIZATION, agentA.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\": \"" + agentB.agentId() + "\"}"))
                .andExpect(status().isForbidden());
        assertThat(getJson(admin, "/api/v1/conversations").path("totalElements").asInt()).isEqualTo(2);
    }

    @Test
    void customersOnlySeeTheirOwnConversationsAndTickets() throws Exception {
        Session admin = registerOrganization();
        Session alice = registerCustomer(admin);
        Session bob = registerCustomer(admin);
        UUID aliceConversation = openConversation(alice, "Conversación de Alice");
        JsonNode aliceTicket = json(mockMvc.perform(post("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, alice.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\": \"Factura\", \"description\": \"No me llegó la factura\"}"))
                .andExpect(status().isCreated()));

        assertThat(getJson(bob, "/api/v1/conversations").path("totalElements").asInt()).isZero();
        assertThat(getJson(bob, "/api/v1/tickets").path("totalElements").asInt()).isZero();
        mockMvc.perform(get("/api/v1/conversations/" + aliceConversation).header(HttpHeaders.AUTHORIZATION, bob.bearer()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/tickets/" + aliceTicket.path("id").asText())
                        .header(HttpHeaders.AUTHORIZATION, bob.bearer()))
                .andExpect(status().isNotFound());
        sendMessage(bob, aliceConversation, "intruso").andExpect(status().isNotFound());

        // Los clientes no acceden a datos del equipo
        for (String path : new String[]{"/api/v1/customers", "/api/v1/knowledge", "/api/v1/analytics",
                "/api/v1/organizations/me"}) {
            mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bob.bearer())).andExpect(status().isForbidden());
        }
        mockMvc.perform(post("/api/v1/conversations/" + aliceConversation + "/ai/suggest")
                        .header(HttpHeaders.AUTHORIZATION, alice.bearer()))
                .andExpect(status().isForbidden());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/tickets/" + aliceTicket.path("id").asText())
                        .header(HttpHeaders.AUTHORIZATION, alice.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CLOSED\"}"))
                .andExpect(status().isForbidden());
    }
}
