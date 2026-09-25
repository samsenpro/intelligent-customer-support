package com.supportmind.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.supportmind.ai.client.ResilientAiExecutor;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Predicate;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.supportmind.support.AiServiceStubs.chatEndpoint;
import static com.supportmind.support.AiServiceStubs.chatStream;
import static com.supportmind.support.AiServiceStubs.classification;
import static com.supportmind.support.AiServiceStubs.classifyEndpoint;
import static com.supportmind.support.AiServiceStubs.deleteDocumentEndpoint;
import static com.supportmind.support.AiServiceStubs.deleted;
import static com.supportmind.support.AiServiceStubs.embedEndpoint;
import static com.supportmind.support.AiServiceStubs.embedded;
import static com.supportmind.support.AiServiceStubs.searchEndpoint;
import static com.supportmind.support.AiServiceStubs.searchResults;
import static com.supportmind.support.AiServiceStubs.sentiment;
import static com.supportmind.support.AiServiceStubs.sentimentEndpoint;
import static com.supportmind.support.AiServiceStubs.summarizeEndpoint;
import static com.supportmind.support.AiServiceStubs.suggestEndpoint;
import static com.supportmind.support.AiServiceStubs.suggestion;
import static com.supportmind.support.AiServiceStubs.summary;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base de los tests de integración: PostgreSQL (imagen de pgvector, la misma que en producción) y
 * Redis reales con Testcontainers, y el servicio de IA simulado con WireMock. Los contenedores se
 * arrancan una vez para toda la batería y todos los tests comparten el contexto de Spring (un solo
 * grupo de workers consumiendo la cola de Redis).
 * <p>
 * Cada test crea sus propias organizaciones, así los datos de un test nunca interfieren en otro.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTest {

    public static final String INTERNAL_API_KEY = "test-internal-api-key-0123456789abcdef";
    public static final String PASSWORD = "Sup3r-Secret-Pass";

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg16").asCompatibleSubstituteFor("postgres"));
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);
    protected static final WireMockServer AI_SERVICE = new WireMockServer(options().dynamicPort());

    static {
        POSTGRES.start();
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
        registry.add("supportmind.ai-service.url", AI_SERVICE::baseUrl);
        registry.add("supportmind.ai-service.api-key", () -> INTERNAL_API_KEY);
        registry.add("resilience4j.retry.instances.aiService.wait-duration", () -> "20ms");
        registry.add("resilience4j.timelimiter.instances.aiService.timeout-duration", () -> "2s");
        registry.add("resilience4j.timelimiter.instances.aiStream.timeout-duration", () -> "3s");
        registry.add("supportmind.ai.resummarize-every", () -> "4");
        registry.add("supportmind.queue.poll-timeout", () -> "200ms");
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void resetExternalState() {
        AI_SERVICE.resetAll();
        circuitBreakerRegistry.circuitBreaker(ResilientAiExecutor.INSTANCE).reset();
        // Los contadores de rate limiting no deben pasar de un test a otro
        var keys = redis.keys("rate-limit:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        defaultAiStubs();
    }

    /** Respuestas por defecto del servicio de IA; cada test sobrescribe las que necesita. */
    protected void defaultAiStubs() {
        AI_SERVICE.stubFor(classifyEndpoint().atPriority(10)
                .willReturn(classification("GENERAL_QUESTION", "GENERAL", "LOW")));
        AI_SERVICE.stubFor(sentimentEndpoint().atPriority(10).willReturn(sentiment("NEUTRAL")));
        AI_SERVICE.stubFor(chatEndpoint().atPriority(10).willReturn(chatStream("ANSWERED",
                "Puedes solicitar el reembolso dentro de los 30 días siguientes a la entrega.", 0.86)));
        AI_SERVICE.stubFor(summarizeEndpoint().atPriority(10).willReturn(summary("Resumen de la conversación.")));
        AI_SERVICE.stubFor(embedEndpoint().atPriority(10).willReturn(embedded(3)));
        AI_SERVICE.stubFor(searchEndpoint().atPriority(10).willReturn(searchResults()));
        AI_SERVICE.stubFor(deleteDocumentEndpoint().atPriority(10).willReturn(deleted()));
        AI_SERVICE.stubFor(suggestEndpoint().atPriority(10).willReturn(suggestion("Borrador de respuesta.", 0.8)));
    }

    // ---------------------------------------------------------------- usuarios

    /** Registra una organización nueva y devuelve la sesión de su ADMIN. */
    protected Session registerOrganization() throws Exception {
        String email = "admin-" + UUID.randomUUID() + "@example.com";
        JsonNode body = json(mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s", "fullName": "Admin", "organizationName": "Org %s"}"""
                                .formatted(email, PASSWORD, email)))
                .andExpect(status().isCreated()));
        return Session.from(body);
    }

    /** Crea un miembro del equipo (ADMIN, SUPERVISOR o AGENT) y devuelve su sesión. */
    protected Session createMember(Session admin, String role) throws Exception {
        String email = role.toLowerCase() + "-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/v1/organizations/me/users")
                        .header(HttpHeaders.AUTHORIZATION, admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s", "fullName": "%s member", "role": "%s"}"""
                                .formatted(email, PASSWORD, role, role)))
                .andExpect(status().isCreated());
        return login(email);
    }

    /** Activa el registro de clientes de la organización y registra un cliente en su portal. */
    protected Session registerCustomer(Session admin) throws Exception {
        updateAiSettings(admin, "\"customerSignupEnabled\": true");
        String slug = getJson(admin, "/api/v1/organizations/me").path("slug").asText();
        String email = "customer-" + UUID.randomUUID() + "@example.com";
        JsonNode body = json(mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s", "fullName": "Cliente Prueba", "organizationSlug": "%s"}"""
                                .formatted(email, PASSWORD, slug)))
                .andExpect(status().isCreated()));
        return Session.from(body);
    }

    /**
     * Cambia la configuración de IA de la organización. {@code overrides} es un fragmento JSON con los
     * campos a cambiar sobre la configuración actual.
     */
    protected void updateAiSettings(Session admin, String overrides) throws Exception {
        var settings = (com.fasterxml.jackson.databind.node.ObjectNode) getJson(admin, "/api/v1/organizations/me")
                .path("aiSettings");
        settings.setAll((com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree("{" + overrides + "}"));
        mockMvc.perform(put("/api/v1/organizations/me/ai-settings")
                        .header(HttpHeaders.AUTHORIZATION, admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settings.toString()))
                .andExpect(status().isOk());
    }

    protected Session login(String email) throws Exception {
        return Session.from(json(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}""".formatted(email, PASSWORD)))
                .andExpect(status().isOk())));
    }

    // ---------------------------------------------------------------- conversaciones

    /** El cliente abre una conversación con un primer mensaje; devuelve su ID. */
    protected UUID openConversation(Session customer, String message) throws Exception {
        JsonNode body = json(mockMvc.perform(post("/api/v1/conversations")
                        .header(HttpHeaders.AUTHORIZATION, customer.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode().put("subject", "Consulta")
                                .put("message", message).toString()))
                .andExpect(status().isCreated()));
        return UUID.fromString(body.path("id").asText());
    }

    protected ResultActions sendMessage(Session session, UUID conversationId, String content) throws Exception {
        return mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/messages")
                .header(HttpHeaders.AUTHORIZATION, session.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.createObjectNode().put("content", content).toString()));
    }

    /** Espera a que la conversación cumpla la condición (el procesamiento de la IA es asíncrono). */
    protected JsonNode awaitConversation(Session session, UUID conversationId, Predicate<JsonNode> condition) {
        return await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100))
                .until(() -> getJson(session, "/api/v1/conversations/" + conversationId), condition);
    }

    /** Condición: hay un mensaje del remitente indicado. */
    protected static Predicate<JsonNode> hasMessageFrom(String senderType) {
        return detail -> detail.path("messages").findValuesAsText("senderType").contains(senderType);
    }

    protected static JsonNode lastMessageFrom(JsonNode detail, String senderType) {
        JsonNode found = null;
        for (JsonNode message : detail.path("messages")) {
            if (senderType.equals(message.path("senderType").asText())) {
                found = message;
            }
        }
        return found;
    }

    // ---------------------------------------------------------------- utilidades

    protected JsonNode getJson(Session session, String path) throws Exception {
        return json(mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, session.bearer()))
                .andExpect(status().isOk()));
    }

    /** IDs de los elementos de una página (sin los IDs anidados de clientes o agentes). */
    protected static java.util.List<String> ids(JsonNode page) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        page.path("content").forEach(item -> ids.add(item.path("id").asText()));
        return ids;
    }

    protected JsonNode json(ResultActions actions) throws Exception {
        MvcResult result = actions.andReturn();
        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return content.isEmpty() ? objectMapper.createObjectNode() : objectMapper.readTree(content);
    }

    public record Session(UUID userId, UUID organizationId, String role, UUID agentId, UUID customerId,
                          String accessToken, String refreshToken) {

        static Session from(JsonNode body) {
            JsonNode user = body.path("user");
            return new Session(UUID.fromString(user.path("id").asText()),
                    UUID.fromString(user.path("organizationId").asText()), user.path("role").asText(),
                    uuidOrNull(user.path("agentId")), uuidOrNull(user.path("customerId")),
                    body.path("accessToken").asText(), body.path("refreshToken").asText());
        }

        private static UUID uuidOrNull(JsonNode node) {
            return node.isMissingNode() || node.isNull() ? null : UUID.fromString(node.asText());
        }

        public String bearer() {
            return "Bearer " + accessToken;
        }
    }
}
