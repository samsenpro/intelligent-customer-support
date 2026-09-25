package com.supportmind.ticket;

import com.fasterxml.jackson.databind.JsonNode;
import com.supportmind.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TicketIntegrationTest extends IntegrationTest {

    @Test
    void ticketLifecycleIsRecordedInItsHistoryAndAudit() throws Exception {
        Session admin = registerOrganization();
        Session customer = registerCustomer(admin);
        Session agent = createMember(admin, "AGENT");
        JsonNode created = json(mockMvc.perform(post("/api/v1/tickets")
                        .header(HttpHeaders.AUTHORIZATION, customer.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subject": "No me llegó la factura", "description": "Compré el 10 de septiembre"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.priority").value("MEDIUM"))
                .andExpect(jsonPath("$.category").value("GENERAL"))
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.customer.fullName").value("Cliente Prueba")));
        String url = "/api/v1/tickets/" + created.path("id").asText();

        mockMvc.perform(patch(url).header(HttpHeaders.AUTHORIZATION, agent.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "IN_PROGRESS", "priority": "HIGH", "category": "BILLING",
                                 "assignedAgentId": "%s"}""".formatted(agent.agentId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedAgent.id").value(agent.agentId().toString()));
        mockMvc.perform(patch(url).header(HttpHeaders.AUTHORIZATION, agent.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"RESOLVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedAt").isNotEmpty());

        JsonNode detail = getJson(agent, url);
        assertThat(detail.path("history").findValuesAsText("field"))
                .containsExactlyInAnyOrder("status", "priority", "category", "assignedAgentId", "status", "status");
        assertThat(getJson(admin, "/api/v1/audit-logs?event=TICKET_UPDATED").path("totalElements").asInt())
                .isEqualTo(2);

        // Transiciones no permitidas: un ticket cerrado es definitivo
        mockMvc.perform(patch(url).header(HttpHeaders.AUTHORIZATION, agent.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"CLOSED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch(url).header(HttpHeaders.AUTHORIZATION, agent.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"OPEN\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
        // El cliente ve su ticket y su estado
        assertThat(getJson(customer, url).path("ticket").path("status").asText()).isEqualTo("CLOSED");
    }

    @Test
    void aConversationHasAtMostOneOpenTicket() throws Exception {
        Session admin = registerOrganization();
        Session customer = registerCustomer(admin);
        UUID conversationId = openConversation(customer, "Tengo un problema");
        String body = "{\"conversationId\": \"%s\", \"subject\": \"S\", \"description\": \"D\"}".formatted(conversationId);
        mockMvc.perform(post("/api/v1/tickets").header(HttpHeaders.AUTHORIZATION, admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/tickets").header(HttpHeaders.AUTHORIZATION, admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPEN_TICKET_ALREADY_EXISTS"));
    }

    @Test
    void ticketsCanBeFilteredByStatusPriorityAndAssignee() throws Exception {
        Session admin = registerOrganization();
        Session customer = registerCustomer(admin);
        for (String priority : new String[]{"LOW", "URGENT", "URGENT"}) {
            mockMvc.perform(post("/api/v1/tickets").header(HttpHeaders.AUTHORIZATION, customer.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"subject\": \"S\", \"description\": \"D\", \"priority\": \"%s\"}"
                                    .formatted(priority)))
                    .andExpect(status().isCreated());
        }
        assertThat(getJson(admin, "/api/v1/tickets?priority=URGENT").path("totalElements").asInt()).isEqualTo(2);
        assertThat(getJson(admin, "/api/v1/tickets?status=OPEN").path("totalElements").asInt()).isEqualTo(3);
        assertThat(getJson(admin, "/api/v1/tickets?assignedToMe=true").path("totalElements").asInt()).isZero();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/tickets?sort=password").header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isBadRequest());
    }
}
