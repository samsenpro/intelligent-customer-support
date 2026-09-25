package com.supportmind.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KnowledgeRepository extends JpaRepository<KnowledgeDocument, UUID>,
        JpaSpecificationExecutor<KnowledgeDocument> {

    Optional<KnowledgeDocument> findByIdAndOrganizationId(UUID id, UUID organizationId);

    boolean existsByOrganizationId(UUID organizationId);

    /** Documentos con la sincronización con el vector store pendiente desde antes de {@code before}. */
    List<KnowledgeDocument> findTop100ByIndexStatusAndUpdatedAtBefore(IndexStatus indexStatus, Instant before);
}
