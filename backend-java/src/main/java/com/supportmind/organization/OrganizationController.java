package com.supportmind.organization;

import com.supportmind.auth.AuthenticatedUser;
import com.supportmind.common.web.PageResponse;
import com.supportmind.common.web.SortableFields;
import com.supportmind.organization.OrganizationDtos.CreateMemberRequest;
import com.supportmind.organization.OrganizationDtos.MemberResponse;
import com.supportmind.organization.OrganizationDtos.OrganizationResponse;
import com.supportmind.organization.OrganizationDtos.UpdateAiSettingsRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@RestController
@RequestMapping("/api/v1/organizations/me")
@Tag(name = "Organization", description = "Organization profile, AI settings and team members")
@SecurityRequirement(name = "bearerAuth")
public class OrganizationController {

    private final OrganizationService organizationService;

    public OrganizationController(OrganizationService organizationService) {
        this.organizationService = organizationService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @Operation(summary = "Organization of the authenticated user, with its AI settings")
    public OrganizationResponse get(@AuthenticationPrincipal AuthenticatedUser user) {
        return organizationService.get(user);
    }

    @PutMapping("/ai-settings")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update the AI settings: auto-reply, confidence threshold, handoff rules and tickets (ADMIN)")
    public OrganizationResponse updateAiSettings(@AuthenticationPrincipal AuthenticatedUser user,
                                                 @Valid @RequestBody UpdateAiSettingsRequest request) {
        return organizationService.updateAiSettings(user, request);
    }

    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List the users of the organization (ADMIN)")
    public PageResponse<MemberResponse> members(@AuthenticationPrincipal AuthenticatedUser user,
                                                @PageableDefault(size = 50, sort = "createdAt",
                                                        direction = Sort.Direction.ASC) Pageable pageable) {
        return organizationService.members(user,
                SortableFields.validate(pageable, Set.of("createdAt", "email", "fullName", "role")));
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a team member: ADMIN, SUPERVISOR or AGENT (ADMIN)")
    public MemberResponse createMember(@AuthenticationPrincipal AuthenticatedUser user,
                                       @Valid @RequestBody CreateMemberRequest request) {
        return organizationService.createMember(user, request);
    }
}
