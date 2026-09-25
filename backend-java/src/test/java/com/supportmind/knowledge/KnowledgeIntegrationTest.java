package com.supportmind.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.supportmind.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.supportmind.support.AiServiceStubs.embedEndpoint;
import static com.supportmind.support.AiServiceStubs.error;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Knowledge Document -> Spring Boot -> Python (chunking + embeddings) -> vector store. */
class KnowledgeIntegrationTest extends IntegrationTest {

    private static final String POLICY = """
            {"title": "Política de reembolsos", "type": "POLICY", "status": "%s",
             "content": "Puedes pedir el reembolso dentro de los 30 días siguientes a la entrega."}""";

    @Test
    void publishedDocumentsAreIndexedAndArchivedOnesRemoved() throws Exception {
        Session admin = registerOrganization();
        JsonNode created = json(mockMvc.perform(post("/api/v1/knowledge").header(HttpHeaders.AUTHORIZATION,
                        admin.bearer()).contentType(MediaType.APPLICATION_JSON).content(POLICY.formatted("PUBLISHED")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.indexStatus").value("PENDING")));
        String id = created.path("id").asText();

        JsonNode indexed = awaitIndexStatus(admin, id, "INDEXED");
        assertThat(indexed.path("chunkCount").asInt()).isEqualTo(3);
        assertThat(indexed.path("embeddingModel").asText()).isEqualTo("hash-768");
        AI_SERVICE.verify(postRequestedFor(urlEqualTo("/api/v1/knowledge/embed"))
                .withRequestBody(matchingJsonPath("$.document_id", equalTo(id)))
                .withRequestBody(matchingJsonPath("$.organization_id", equalTo(admin.organizationId().toString())))
                .withRequestBody(matchingJsonPath("$.document_type", equalTo("POLICY"))));

        mockMvc.perform(put("/api/v1/knowledge/" + id).header(HttpHeaders.AUTHORIZATION, admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(POLICY.formatted("ARCHIVED")))
                .andExpect(status().isOk());
        JsonNode archived = awaitIndexStatus(admin, id, "NOT_INDEXED");
        assertThat(archived.path("chunkCount").asInt()).isZero();
        AI_SERVICE.verify(deleteRequestedFor(urlPathEqualTo("/api/v1/knowledge/" + id))
                .withQueryParam("organization_id", equalTo(admin.organizationId().toString())));
        assertThat(getJson(admin, "/api/v1/audit-logs").path("content").findValuesAsText("event"))
                .contains("KNOWLEDGE_DOCUMENT_CREATED", "KNOWLEDGE_DOCUMENT_UPDATED");
    }

    @Test
    void draftsAreNotIndexed() throws Exception {
        Session admin = registerOrganization();
        JsonNode created = json(mockMvc.perform(post("/api/v1/knowledge").header(HttpHeaders.AUTHORIZATION,
                        admin.bearer()).contentType(MediaType.APPLICATION_JSON).content(POLICY.formatted("DRAFT")))
                .andExpect(status().isCreated()));
        assertThat(created.path("indexStatus").asText()).isEqualTo("NOT_INDEXED");
        mockMvc.perform(post("/api/v1/knowledge/" + created.path("id").asText() + "/reindex")
                        .header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isConflict());
    }

    @Test
    void indexingFailuresAreVisibleAndCanBeRetried() throws Exception {
        AI_SERVICE.stubFor(embedEndpoint().willReturn(error(422, "INVALID_REQUEST")));
        Session admin = registerOrganization();
        String id = json(mockMvc.perform(post("/api/v1/knowledge").header(HttpHeaders.AUTHORIZATION, admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(POLICY.formatted("PUBLISHED")))
                .andExpect(status().isCreated())).path("id").asText();
        JsonNode failed = awaitIndexStatus(admin, id, "FAILED");
        assertThat(failed.path("indexError").asText()).startsWith("INVALID_REQUEST");

        AI_SERVICE.resetAll();
        defaultAiStubs();
        mockMvc.perform(post("/api/v1/knowledge/" + id + "/reindex").header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isAccepted());
        awaitIndexStatus(admin, id, "INDEXED");
    }

    @Test
    void semanticSearchGoesThroughTheAiService() throws Exception {
        Session admin = registerOrganization();
        Session agent = createMember(admin, "AGENT");
        mockMvc.perform(get("/api/v1/knowledge/search").param("q", "¿cuánto tarda el reembolso?").param("topK", "2")
                        .header(HttpHeaders.AUTHORIZATION, agent.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embeddingModel").value("hash-768"))
                .andExpect(jsonPath("$.results[0].title").value("Política de reembolsos"))
                .andExpect(jsonPath("$.results[0].score").value(0.64));
        AI_SERVICE.verify(postRequestedFor(urlEqualTo("/api/v1/knowledge/search"))
                .withRequestBody(matchingJsonPath("$.top_k", equalTo("2"))));
    }

    @Test
    void listingSupportsFiltersAndTitleSearch() throws Exception {
        Session admin = registerOrganization();
        for (String title : new String[]{"Garantía 100% original", "Envíos nacionales", "Garantía extendida"}) {
            mockMvc.perform(post("/api/v1/knowledge").header(HttpHeaders.AUTHORIZATION, admin.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\": \"%s\", \"content\": \"Contenido\", \"type\": \"FAQ\"}".formatted(title)))
                    .andExpect(status().isCreated());
        }
        assertThat(getJson(admin, "/api/v1/knowledge?search=garant").path("totalElements").asInt()).isEqualTo(2);
        // "%" se busca literalmente, no como comodín
        JsonNode literal = json(mockMvc.perform(get("/api/v1/knowledge").param("search", "100%")
                        .header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isOk()));
        assertThat(literal.path("totalElements").asInt()).isEqualTo(1);
        assertThat(getJson(admin, "/api/v1/knowledge?status=PUBLISHED").path("totalElements").asInt()).isZero();
    }

    private JsonNode awaitIndexStatus(Session admin, String id, String expected) {
        return await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100))
                .until(() -> getJson(admin, "/api/v1/knowledge/" + id),
                        body -> expected.equals(body.path("indexStatus").asText()));
    }
}
