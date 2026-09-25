package com.supportmind.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.supportmind.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthIntegrationTest extends IntegrationTest {

    @Test
    void registerCreatesAnOrganizationAndItsAdminWithAnAgentProfile() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "Ana.Gomez@Example.com", "password": "Sup3r-Secret-Pass",
                                 "fullName": "Ana Gómez", "organizationName": "Tienda Ñandú S.A.S."}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.user.email").value("ana.gomez@example.com"))
                .andExpect(jsonPath("$.user.role").value("ADMIN"))
                .andExpect(jsonPath("$.user.organizationSlug").value("tienda-nandu-s-a-s"))
                .andExpect(jsonPath("$.user.agentId").isNotEmpty())
                .andExpect(jsonPath("$.user.customerId").isEmpty());
    }

    @Test
    void duplicateEmailIsRejectedIgnoringCase() throws Exception {
        String body = """
                {"email": "%s", "password": "Sup3r-Secret-Pass", "fullName": "A", "organizationName": "Org"}""";
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted("dup@example.com")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted("DUP@example.com")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    void validationErrorsAreProblemDetailsWithCorrelationId() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .header("X-Correlation-Id", "test-correlation-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "not-an-email", "password": "short", "fullName": ""}"""))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("X-Correlation-Id", "test-correlation-1"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.correlationId").value("test-correlation-1"))
                .andExpect(jsonPath("$.errors[*].field",
                        hasItems("email", "password", "fullName", "exactlyOneOrganizationTarget")));
    }

    @Test
    void customersCanOnlySignUpWhenTheOrganizationAllowsIt() throws Exception {
        Session admin = registerOrganization();
        String slug = getJson(admin, "/api/v1/organizations/me").path("slug").asText();
        String request = """
                {"email": "client-%s@example.com", "password": "Sup3r-Secret-Pass", "fullName": "Cliente",
                 "organizationSlug": "%s"}""";

        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(request.formatted(1, slug)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIGNUP_NOT_ALLOWED"));

        updateAiSettings(admin, "\"customerSignupEnabled\": true");
        JsonNode customer = json(mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(request.formatted(System.nanoTime(), slug)))
                .andExpect(status().isCreated()));
        assertThat(customer.path("user").path("role").asText()).isEqualTo("CUSTOMER");
        assertThat(customer.path("user").path("organizationId").asText()).isEqualTo(admin.organizationId().toString());
        assertThat(customer.path("user").path("customerId").asText()).isNotBlank();
        assertThat(customer.path("user").path("agentId").isNull()).isTrue();
    }

    @Test
    void loginWithWrongPasswordOrUnknownEmailGivesTheSameError() throws Exception {
        Session admin = registerOrganization();
        String email = getJson(admin, "/api/v1/auth/me").path("email").asText();

        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "Wrong-Password-1"}""".formatted(email)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "nobody@example.com", "password": "Wrong-Password-1"}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void loginIsAudited() throws Exception {
        Session admin = registerOrganization();
        login(getJson(admin, "/api/v1/auth/me").path("email").asText());
        JsonNode audit = getJson(admin, "/api/v1/audit-logs?event=USER_LOGIN");
        assertThat(audit.path("totalElements").asInt()).isEqualTo(1);
        assertThat(audit.path("content").get(0).path("ipAddress").asText()).isNotBlank();
    }

    @Test
    void refreshTokenRotatesAndCannotBeReused() throws Exception {
        Session session = registerOrganization();
        String refresh = """
                {"refreshToken": "%s"}""".formatted(session.refreshToken());

        JsonNode renewed = json(mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(refresh))
                .andExpect(status().isOk()));
        assertThat(renewed.path("refreshToken").asText()).isNotEqualTo(session.refreshToken());

        mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(refresh))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
        assertThat(redis.keys("auth:refresh:*")).noneMatch(key -> key.contains(session.refreshToken()));
    }

    @Test
    void protectedEndpointsRequireAValidToken() throws Exception {
        mockMvc.perform(get("/api/v1/conversations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(get("/api/v1/conversations").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void actuatorHealthIsPublicButMetricsNeedAnAdmin() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        Session admin = registerOrganization();
        mockMvc.perform(get("/actuator/metrics").header(HttpHeaders.AUTHORIZATION, admin.bearer()))
                .andExpect(status().isOk());
    }
}
