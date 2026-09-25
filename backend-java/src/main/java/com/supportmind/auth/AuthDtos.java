package com.supportmind.auth;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/** Peticiones y respuestas de autenticación. */
public final class AuthDtos {

    private AuthDtos() {
    }

    @Schema(description = """
            Two sign-up modes: with `organizationName` a new organization is created and the user becomes \
            its ADMIN; with `organizationSlug` the user joins that organization as a CUSTOMER (only if the \
            organization allows customer sign-ups). Exactly one of them is required.""")
    public record RegisterRequest(
            @Schema(example = "ana@acme.com")
            @NotBlank @Email @Size(max = 254) String email,
            @Schema(example = "S3cure-Passw0rd!")
            @NotBlank @Size(min = 12, max = 72, message = "must have between 12 and 72 characters")
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).*$", message = "must contain letters and digits")
            String password,
            @Schema(example = "Ana Gómez")
            @NotBlank @Size(max = 120) String fullName,
            @Schema(example = "Acme Store")
            @Size(min = 2, max = 120) String organizationName,
            @Schema(example = "acme-store")
            @Size(max = 60) String organizationSlug) {

        @JsonIgnore
        @AssertTrue(message = "exactly one of organizationName or organizationSlug is required")
        public boolean isExactlyOneOrganizationTarget() {
            return isBlank(organizationName) != isBlank(organizationSlug);
        }

        @JsonIgnore
        public boolean joinsExistingOrganization() {
            return !isBlank(organizationSlug);
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }

        @Override
        public String toString() {
            return "RegisterRequest[email=" + email + "]";
        }
    }

    public record LoginRequest(
            @Schema(example = "ana@acme.com") @NotBlank @Size(max = 254) String email,
            @Schema(example = "S3cure-Passw0rd!") @NotBlank @Size(max = 72) String password) {

        @Override
        public String toString() {
            return "LoginRequest[email=" + email + "]";
        }
    }

    public record RefreshTokenRequest(@NotBlank @Size(max = 100) String refreshToken) {

        @Override
        public String toString() {
            return "RefreshTokenRequest[***]";
        }
    }

    public record AuthResponse(
            String accessToken,
            String refreshToken,
            @Schema(example = "Bearer") String tokenType,
            @Schema(description = "Access token lifetime in seconds", example = "900") long expiresIn,
            UserResponse user) {
    }

    /**
     * Usuario autenticado. {@code agentId} solo existe para el equipo de soporte y {@code customerId}
     * solo para clientes.
     */
    public record UserResponse(UUID id, UUID organizationId, String organizationName, String organizationSlug,
                               String email, String fullName, Role role, UUID agentId, UUID customerId,
                               Instant createdAt) {
    }
}
