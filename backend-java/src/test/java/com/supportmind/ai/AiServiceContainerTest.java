package com.supportmind.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Comunicación Java -> Python real: el backend llama a la imagen Docker del servicio de IA, que indexa y
 * busca en PostgreSQL + pgvector (embeddings locales por hashing y respuestas extractivas, sin LLM).
 * <p>
 * Construye la imagen del servicio de IA, así que va en su propio perfil: {@code ./mvnw test -Pai-service-it}
 */
@Tag("ai-service")
@SpringBootTest
@AutoConfigureMockMvc
class AiServiceContainerTest {

    private static final String API_KEY = "container-test-internal-api-key-0123456789";
    private static final Network NETWORK = Network.newNetwork();

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg16").asCompatibleSubstituteFor("postgres"))
            .withNetwork(NETWORK).withNetworkAliases("postgres");
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);
    static final GenericContainer<?> AI_SERVICE = new GenericContainer<>(
            new ImageFromDockerfile("supportmind-ai-service-test", false)
                    .withFileFromPath(".", Path.of("..", "ai-service"))
                    .withTarget("runtime"))
            .withNetwork(NETWORK)
            .withEnv("AI_SERVICE_API_KEY", API_KEY)
            .withEnv("VECTOR_DB_HOST", "postgres")
            .withEnv("VECTOR_DB_PORT", "5432")
            .withEnv("VECTOR_DB_NAME", "test")
            .withEnv("VECTOR_DB_USER", "test")
            .withEnv("VECTOR_DB_PASSWORD", "test")
            .withEnv("EMBEDDING_PROVIDER", "hash")
            .withEnv("LOG_FORMAT", "text")
            .withExposedPorts(8000)
            .waitingFor(Wait.forHttp("/api/v1/health").forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(5));

    static {
        POSTGRES.start();
        // El servicio de IA crea su esquema con el usuario de la base de datos de test
        try (var connection = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), "test", "test")) {
            connection.createStatement().execute("CREATE EXTENSION IF NOT EXISTS vector");
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException(ex);
        }
        REDIS.start();
        AI_SERVICE.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("supportmind.security.jwt.secret", () -> "test-jwt-secret-with-at-least-32-bytes-0123456789");
        registry.add("supportmind.ai-service.url",
                () -> "http://" + AI_SERVICE.getHost() + ":" + AI_SERVICE.getMappedPort(8000));
        registry.add("supportmind.ai-service.api-key", () -> API_KEY);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String REFUND_POLICY = """
            # Política de reembolsos

            Puedes solicitar el reembolso de un producto dentro de los 30 días siguientes a la entrega. \
            El producto debe estar sin usar y en su empaque original.

            ## Plazos

            Una vez aprobado, el reembolso se acredita en un plazo de 5 a 10 días hábiles.""";

    @Test
    void ragEndToEndThroughTheRealAiService() throws Exception {
        String admin = register("{\"organizationName\": \"Tienda Real\"}", null);
        String customer = customerOf(admin);

        String documentId = json(mockMvc.perform(post("/api/v1/knowledge").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode().put("title", "Política de reembolsos")
                                .put("type", "POLICY").put("status", "PUBLISHED").put("content", REFUND_POLICY)
                                .toString()))
                .andExpect(status().isCreated())).path("id").asText();
        JsonNode indexed = await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250))
                .until(() -> getJson(admin, "/api/v1/knowledge/" + documentId),
                        body -> "INDEXED".equals(body.path("indexStatus").asText()));
        assertThat(indexed.path("chunkCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(indexed.path("embeddingModel").asText()).isEqualTo("hash-768");

        JsonNode search = getJson(admin, "/api/v1/knowledge/search?q=plazo%20del%20reembolso");
        assertThat(search.path("results").get(0).path("title").asText()).isEqualTo("Política de reembolsos");

        // El cliente pregunta y la IA responde con la base de conocimiento (RAG real, extractivo sin LLM)
        String conversationId = json(mockMvc.perform(post("/api/v1/conversations")
                        .header(HttpHeaders.AUTHORIZATION, customer).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode()
                                .put("message", "¿En cuántos días puedo pedir el reembolso de un producto?").toString()))
                .andExpect(status().isCreated())).path("id").asText();
        JsonNode detail = await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250))
                .until(() -> getJson(customer, "/api/v1/conversations/" + conversationId),
                        body -> body.path("messages").findValuesAsText("senderType").contains("AI"));
        JsonNode aiMessage = detail.path("messages").get(1);
        assertThat(aiMessage.path("content").asText()).contains("30 días");
        assertThat(aiMessage.path("metadata").path("ai").path("sources").get(0).path("title").asText())
                .isEqualTo("Política de reembolsos");
        assertThat(aiMessage.path("metadata").path("ai").path("model").asText()).isEqualTo("extractive");
        assertThat(lastAnalysis(detail).path("intent").asText()).isEqualTo("REFUND_REQUEST");

        // Aislamiento real en pgvector: otra organización no encuentra el documento
        String other = register("{\"organizationName\": \"Otra Tienda\"}", null);
        assertThat(getJson(other, "/api/v1/knowledge/search?q=plazo%20del%20reembolso").path("results")).isEmpty();

        // Archivado: sale del vector store
        mockMvc.perform(put("/api/v1/knowledge/" + documentId).header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode().put("title", "Política de reembolsos")
                                .put("type", "POLICY").put("status", "ARCHIVED").put("content", REFUND_POLICY)
                                .toString()))
                .andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250))
                .until(() -> getJson(admin, "/api/v1/knowledge/search?q=plazo%20del%20reembolso").path("results"),
                        JsonNode::isEmpty);
    }

    private static JsonNode lastAnalysis(JsonNode detail) {
        return detail.path("messages").get(0).path("metadata").path("analysis");
    }

    private String register(String organization, String email) throws Exception {
        var body = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(organization);
        body.put("email", email != null ? email : "user-" + UUID.randomUUID() + "@example.com");
        body.put("password", "Sup3r-Secret-Pass");
        body.put("fullName", "Usuario");
        return "Bearer " + json(mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(body.toString())).andExpect(status().isCreated())).path("accessToken").asText();
    }

    private String customerOf(String admin) throws Exception {
        JsonNode organization = getJson(admin, "/api/v1/organizations/me");
        var settings = (com.fasterxml.jackson.databind.node.ObjectNode) organization.path("aiSettings");
        settings.put("customerSignupEnabled", true);
        mockMvc.perform(put("/api/v1/organizations/me/ai-settings").header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON).content(settings.toString())).andExpect(status().isOk());
        return register("{\"organizationSlug\": \"" + organization.path("slug").asText() + "\"}", null);
    }

    private JsonNode getJson(String bearer, String path) throws Exception {
        return json(mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isOk()));
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
