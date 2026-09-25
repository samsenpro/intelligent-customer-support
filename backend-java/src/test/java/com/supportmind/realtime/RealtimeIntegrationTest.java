package com.supportmind.realtime;

import com.supportmind.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Chat en tiempo real con Server-Sent Events: el frontend recibe la respuesta de la IA por fragmentos. */
class RealtimeIntegrationTest extends IntegrationTest {

    @Test
    void theConversationStreamReceivesTheAiAnswerChunkByChunk() throws Exception {
        Session admin = registerOrganization();
        Session customer = registerCustomer(admin);
        UUID conversationId = openConversation(customer, "Hola");
        awaitConversation(customer, conversationId, hasMessageFrom("AI"));

        MvcResult stream = mockMvc.perform(get("/api/v1/conversations/" + conversationId + "/events")
                        .header(HttpHeaders.AUTHORIZATION, customer.bearer())
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        sendMessage(customer, conversationId, "¿Hasta cuándo puedo pedir el reembolso?").andExpect(status().isCreated());

        String events = await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
                .until(() -> stream.getResponse().getContentAsString(StandardCharsets.UTF_8),
                        body -> body.contains("event:ai.completed"));
        assertThat(events).contains("event:ready", "event:message.created", "event:ai.started", "event:ai.delta",
                "event:conversation.updated", "event:message.updated");
        // Los fragmentos llegan en orden y, juntos, forman la respuesta
        assertThat(events).containsSubsequence("Puedes solicitar el reem", "bolso dentro de los 30 días");
        assertThat(events.indexOf("event:ai.delta")).isLessThan(events.indexOf("event:ai.completed"));
        assertThat(stream.getResponse().getContentType()).startsWith("text/event-stream");
    }

    @Test
    void onlyAuthorizedUsersCanSubscribeToAConversation() throws Exception {
        Session admin = registerOrganization();
        Session owner = registerCustomer(admin);
        Session other = registerCustomer(admin);
        Session otherOrganization = registerOrganization();
        UUID conversationId = openConversation(owner, "Hola");

        mockMvc.perform(get("/api/v1/conversations/" + conversationId + "/events")
                        .header(HttpHeaders.AUTHORIZATION, other.bearer()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/conversations/" + conversationId + "/events")
                        .header(HttpHeaders.AUTHORIZATION, otherOrganization.bearer()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/conversations/" + conversationId + "/events"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theInboxStreamOnlyReceivesVisibleConversations() throws Exception {
        Session acme = registerOrganization();
        Session acmeCustomer = registerCustomer(acme);
        Session globex = registerOrganization();
        Session globexCustomer = registerCustomer(globex);

        MvcResult acmeInbox = mockMvc.perform(get("/api/v1/inbox/events").header(HttpHeaders.AUTHORIZATION,
                        acme.bearer()))
                .andExpect(request().asyncStarted()).andReturn();
        UUID acmeConversation = openConversation(acmeCustomer, "Consulta de Acme");
        UUID globexConversation = openConversation(globexCustomer, "Consulta de Globex");
        awaitConversation(globex, globexConversation, hasMessageFrom("AI"));

        String events = await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
                .until(() -> acmeInbox.getResponse().getContentAsString(StandardCharsets.UTF_8),
                        body -> body.contains("event:ai.completed"));
        assertThat(events).contains(acmeConversation.toString()).doesNotContain(globexConversation.toString());
        // El texto en streaming solo va al stream de la conversación, no al inbox
        assertThat(events).doesNotContain("event:ai.delta");
    }
}
