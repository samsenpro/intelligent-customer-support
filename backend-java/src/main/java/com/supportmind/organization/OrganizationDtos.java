package com.supportmind.organization;

import com.supportmind.auth.Role;
import com.supportmind.auth.User;
import com.supportmind.ticket.TicketCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class OrganizationDtos {

    private OrganizationDtos() {
    }

    public record OrganizationResponse(UUID id, String name, String slug, AiSettingsResponse aiSettings,
                                       Instant createdAt) {

        static OrganizationResponse from(Organization organization) {
            return new OrganizationResponse(organization.getId(), organization.getName(), organization.getSlug(),
                    AiSettingsResponse.from(organization.getAiSettings()), organization.getCreatedAt());
        }
    }

    public record AiSettingsResponse(boolean autoReplyEnabled, BigDecimal confidenceThreshold,
                                     NoContextAction noContextAction, int maxFailedAiAnswers,
                                     List<String> sensitiveCategories, boolean handoffOnUrgent,
                                     boolean autoTicketEnabled, boolean customerSignupEnabled,
                                     int summaryAfterMessages) {

        static AiSettingsResponse from(AiSettings s) {
            return new AiSettingsResponse(s.isAutoReplyEnabled(), s.getConfidenceThreshold(), s.getNoContextAction(),
                    s.getMaxFailedAiAnswers(), s.getSensitiveCategories(), s.isHandoffOnUrgent(),
                    s.isAutoTicketEnabled(), s.isCustomerSignupEnabled(), s.getSummaryAfterMessages());
        }
    }

    public record UpdateAiSettingsRequest(
            @Schema(description = "The AI answers customers automatically; otherwise it only suggests replies to agents")
            @NotNull Boolean autoReplyEnabled,
            @Schema(example = "0.55") @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal confidenceThreshold,
            @NotNull NoContextAction noContextAction,
            @NotNull @Min(1) @Max(10) Integer maxFailedAiAnswers,
            @Schema(description = "Categories that are always handled by a human", example = "[\"SECURITY\", \"LEGAL\"]")
            @NotNull @Size(max = 8) Set<TicketCategory> sensitiveCategories,
            @NotNull Boolean handoffOnUrgent,
            @NotNull Boolean autoTicketEnabled,
            @NotNull Boolean customerSignupEnabled,
            @NotNull @Min(4) @Max(200) Integer summaryAfterMessages) {
    }

    public record CreateMemberRequest(
            @Schema(example = "carlos@acme.com") @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 12, max = 72, message = "must have between 12 and 72 characters")
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).*$",
                    message = "must contain letters and digits")
            String password,
            @NotBlank @Size(max = 120) String fullName,
            @Schema(description = "ADMIN, SUPERVISOR or AGENT (customers register themselves or are created as customers)")
            @NotNull @Pattern(regexp = "ADMIN|SUPERVISOR|AGENT", message = "must be ADMIN, SUPERVISOR or AGENT")
            String role) {

        @Override
        public String toString() {
            return "CreateMemberRequest[email=" + email + ", role=" + role + "]";
        }
    }

    public record MemberResponse(UUID id, String email, String fullName, Role role, boolean enabled,
                                 Instant lastLoginAt, Instant createdAt) {

        static MemberResponse from(User user) {
            return new MemberResponse(user.getId(), user.getEmail(), user.getFullName(), user.getRole(),
                    user.isEnabled(), user.getLastLoginAt(), user.getCreatedAt());
        }
    }
}
