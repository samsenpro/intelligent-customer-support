package com.supportmind.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    Page<AuditLog> findByOrganizationId(UUID organizationId, Pageable pageable);

    Page<AuditLog> findByOrganizationIdAndEvent(UUID organizationId, AuditEvent event, Pageable pageable);
}
