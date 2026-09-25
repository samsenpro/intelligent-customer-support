package com.supportmind.audit;

import com.supportmind.auth.AuthenticatedUser;
import com.supportmind.common.web.PageResponse;
import com.supportmind.common.web.SortableFields;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/audit-logs")
@Tag(name = "Audit", description = "Audit trail of the organization")
@SecurityRequirement(name = "bearerAuth")
public class AuditController {

    private final AuditLogRepository repository;

    public AuditController(AuditLogRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    @Operation(summary = "List audit events of the organization (ADMIN)")
    public PageResponse<AuditLogResponse> list(@AuthenticationPrincipal AuthenticatedUser user,
                                               @RequestParam(required = false) AuditEvent event,
                                               @PageableDefault(size = 50, sort = "createdAt",
                                                       direction = Sort.Direction.DESC) Pageable pageable) {
        Pageable page = SortableFields.validate(pageable, Set.of("createdAt", "event"));
        var logs = event == null
                ? repository.findByOrganizationId(user.organizationId(), page)
                : repository.findByOrganizationIdAndEvent(user.organizationId(), event, page);
        return PageResponse.of(logs, AuditLogResponse::from);
    }

    public record AuditLogResponse(UUID id, AuditEvent event, UUID userId, String entityType, UUID entityId,
                                   String ipAddress, Map<String, Object> metadata, Instant createdAt) {

        static AuditLogResponse from(AuditLog log) {
            return new AuditLogResponse(log.getId(), log.getEvent(), log.getUserId(), log.getEntityType(),
                    log.getEntityId(), log.getIpAddress(), log.getMetadata(), log.getCreatedAt());
        }
    }
}
