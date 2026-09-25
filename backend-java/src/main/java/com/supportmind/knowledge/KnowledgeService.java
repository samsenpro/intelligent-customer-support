package com.supportmind.knowledge;

import com.supportmind.ai.AiProperties;
import com.supportmind.ai.client.AiContract.SearchRequest;
import com.supportmind.ai.client.AiContract.SearchResult;
import com.supportmind.ai.client.AiServiceClient;
import com.supportmind.ai.client.AiServiceException;
import com.supportmind.ai.jobs.AiJob;
import com.supportmind.ai.jobs.AiJobQueue;
import com.supportmind.ai.jobs.AiJobType;
import com.supportmind.audit.AuditEvent;
import com.supportmind.audit.AuditService;
import com.supportmind.auth.AuthenticatedUser;
import com.supportmind.common.AfterCommit;
import com.supportmind.common.web.CorrelationId;
import com.supportmind.common.web.PageResponse;
import com.supportmind.exception.ApiException;
import com.supportmind.exception.ErrorCode;
import com.supportmind.knowledge.KnowledgeDtos.KnowledgeRequest;
import com.supportmind.knowledge.KnowledgeDtos.KnowledgeResponse;
import com.supportmind.knowledge.KnowledgeDtos.KnowledgeSummaryResponse;
import com.supportmind.knowledge.KnowledgeDtos.SearchHitResponse;
import com.supportmind.knowledge.KnowledgeDtos.SearchResponse;
import org.slf4j.MDC;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Base de conocimiento. Java es la fuente de verdad del documento; al publicarlo (o cambiarlo) se
 * encola su indexación: Spring Boot -> Python -> chunking -> embeddings -> vector store.
 * Al archivarlo o despublicarlo, sus fragmentos se retiran del vector store.
 */
@Service
public class KnowledgeService {

    private final KnowledgeRepository repository;
    private final AiJobQueue jobs;
    private final AiServiceClient aiClient;
    private final AuditService auditService;
    private final AiProperties aiProperties;

    public KnowledgeService(KnowledgeRepository repository, AiJobQueue jobs, AiServiceClient aiClient,
                            AuditService auditService, AiProperties aiProperties) {
        this.repository = repository;
        this.jobs = jobs;
        this.aiClient = aiClient;
        this.auditService = auditService;
        this.aiProperties = aiProperties;
    }

    @Transactional(readOnly = true)
    public PageResponse<KnowledgeSummaryResponse> list(AuthenticatedUser user, KnowledgeStatus status,
                                                       KnowledgeType type, String search, Pageable pageable) {
        Specification<KnowledgeDocument> spec = (root, query, cb) -> cb.equal(root.get("organizationId"),
                user.organizationId());
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (type != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("type"), type));
        }
        if (search != null && !search.isBlank()) {
            String pattern = "%" + search.strip().toLowerCase(Locale.ROOT).replace("\\", "\\\\")
                    .replace("%", "\\%").replace("_", "\\_") + "%";
            spec = spec.and((root, query, cb) -> cb.like(cb.lower(root.get("title")), pattern, '\\'));
        }
        return PageResponse.of(repository.findAll(spec, pageable), KnowledgeSummaryResponse::from);
    }

    @Transactional(readOnly = true)
    public KnowledgeResponse get(AuthenticatedUser user, UUID id) {
        return KnowledgeResponse.from(find(user, id));
    }

    @Transactional
    public KnowledgeResponse create(AuthenticatedUser user, KnowledgeRequest request) {
        KnowledgeStatus status = request.status() != null ? request.status() : KnowledgeStatus.DRAFT;
        KnowledgeDocument document = new KnowledgeDocument(user.organizationId(), request.title(), request.content(),
                request.type(), status, user.id());
        syncIndex(document);
        repository.saveAndFlush(document);
        auditService.record(AuditEvent.KNOWLEDGE_DOCUMENT_CREATED, user.organizationId(), user.id(),
                "KnowledgeDocument", document.getId(), Map.of("type", document.getType().name(),
                        "status", status.name()));
        return KnowledgeResponse.from(document);
    }

    @Transactional
    public KnowledgeResponse update(AuthenticatedUser user, UUID id, KnowledgeRequest request) {
        KnowledgeDocument document = find(user, id);
        KnowledgeStatus previous = document.getStatus();
        KnowledgeStatus status = request.status() != null ? request.status() : previous;
        document.update(request.title(), request.content(), request.type(), status);
        syncIndex(document);
        repository.saveAndFlush(document);
        auditService.record(AuditEvent.KNOWLEDGE_DOCUMENT_UPDATED, user.organizationId(), user.id(),
                "KnowledgeDocument", document.getId(), Map.of("from", previous.name(), "to", status.name()));
        return KnowledgeResponse.from(document);
    }

    /** Vuelve a indexar un documento publicado (p. ej. tras cambiar de modelo de embeddings). */
    @Transactional
    public KnowledgeResponse reindex(AuthenticatedUser user, UUID id) {
        KnowledgeDocument document = find(user, id);
        if (document.getStatus() != KnowledgeStatus.PUBLISHED) {
            throw new ApiException(ErrorCode.INVALID_STATUS_TRANSITION, "Only published documents are indexed");
        }
        syncIndex(document);
        repository.saveAndFlush(document);
        return KnowledgeResponse.from(document);
    }

    /** Búsqueda semántica en la base de conocimiento de la organización (solo documentos publicados). */
    public SearchResponse search(AuthenticatedUser user, String query, Integer topK) {
        try {
            SearchResult result = aiClient.search(new SearchRequest(user.organizationId(), query.strip(),
                    topK != null ? topK : aiProperties.topK()));
            return new SearchResponse(query, result.embeddingModel(), result.results().stream()
                    .map(hit -> new SearchHitResponse(hit.documentId(), hit.title(), hit.documentType(),
                            hit.chunkIndex(), hit.content(), hit.score())).toList());
        } catch (AiServiceException ex) {
            throw new ApiException(ErrorCode.AI_TEMPORARILY_UNAVAILABLE);
        }
    }

    /** Publicado -> se (re)indexa; borrador o archivado -> se retira del vector store. */
    private void syncIndex(KnowledgeDocument document) {
        String correlationId = MDC.get(CorrelationId.MDC_KEY);
        if (document.getStatus() == KnowledgeStatus.PUBLISHED) {
            document.markPendingIndex();
            AfterCommit.run(() -> jobs.publish(AiJob.of(AiJobType.INDEX_DOCUMENT, document.getOrganizationId(),
                    document.getId(), correlationId)));
        } else if (document.hasIndexedContent()) {
            // Queda PENDING hasta que el servicio de IA confirme la retirada (si falla, la reintenta el reconciliador)
            document.markPendingIndex();
            AfterCommit.run(() -> jobs.publish(AiJob.of(AiJobType.REMOVE_DOCUMENT, document.getOrganizationId(),
                    document.getId(), correlationId)));
        }
    }

    private KnowledgeDocument find(AuthenticatedUser user, UUID id) {
        return repository.findByIdAndOrganizationId(id, user.organizationId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Knowledge document not found"));
    }
}
