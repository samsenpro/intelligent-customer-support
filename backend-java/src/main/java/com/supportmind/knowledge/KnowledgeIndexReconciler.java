package com.supportmind.knowledge;

import com.supportmind.ai.jobs.AiJob;
import com.supportmind.ai.jobs.AiJobQueue;
import com.supportmind.ai.jobs.AiJobType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Reintenta la sincronización con el vector store que quedó a medias (servicio de IA caído, job
 * perdido): indexa los documentos publicados que siguen pendientes y retira los archivados o
 * despublicados. Garantiza que la IA nunca siga usando un documento archivado.
 */
@Component
public class KnowledgeIndexReconciler {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexReconciler.class);
    private static final Duration STALE_AFTER = Duration.ofMinutes(5);

    private final KnowledgeRepository repository;
    private final AiJobQueue jobs;
    private final Clock clock;

    public KnowledgeIndexReconciler(KnowledgeRepository repository, AiJobQueue jobs, Clock clock) {
        this.repository = repository;
        this.jobs = jobs;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${supportmind.knowledge.reconcile-interval:5m}",
            initialDelayString = "${supportmind.knowledge.reconcile-interval:5m}")
    public void reconcile() {
        try {
            var stale = repository.findTop100ByIndexStatusAndUpdatedAtBefore(IndexStatus.PENDING,
                    clock.instant().minus(STALE_AFTER));
            for (KnowledgeDocument document : stale) {
                AiJobType type = document.getStatus() == KnowledgeStatus.PUBLISHED
                        ? AiJobType.INDEX_DOCUMENT : AiJobType.REMOVE_DOCUMENT;
                jobs.publish(AiJob.of(type, document.getOrganizationId(), document.getId(), null));
            }
            if (!stale.isEmpty()) {
                log.info("Re-queued the vector store synchronization of {} knowledge documents", stale.size());
            }
        } catch (DataAccessException ex) {
            log.warn("Knowledge index reconciliation skipped: {}", ex.getMessage());
        }
    }
}
