package com.supportmind.knowledge;

import com.supportmind.ai.client.AiContract.EmbedRequest;
import com.supportmind.ai.client.AiContract.EmbedResult;
import com.supportmind.ai.client.AiServiceClient;
import com.supportmind.ai.client.AiServiceException;
import com.supportmind.ai.jobs.AiJob;
import com.supportmind.ai.jobs.AiJobHandler;
import com.supportmind.ai.jobs.AiJobQueue;
import com.supportmind.ai.jobs.AiJobType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Set;

/**
 * Jobs de indexación: Knowledge Document -> Python (limpieza, chunking, embeddings) -> vector store.
 * <p>
 * El documento puede cambiar mientras se indexa. Al terminar se comprueba la versión: si cambió, se
 * encola otra indexación (los fragmentos acaban siempre reflejando la última versión), y si ya no está
 * publicado, se retiran del vector store.
 */
@Component
public class KnowledgeIndexer implements AiJobHandler {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexer.class);

    private final KnowledgeRepository repository;
    private final AiServiceClient aiClient;
    private final AiJobQueue jobs;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public KnowledgeIndexer(KnowledgeRepository repository, AiServiceClient aiClient, AiJobQueue jobs,
                            PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        this.aiClient = aiClient;
        this.jobs = jobs;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Override
    public Set<AiJobType> handles() {
        return Set.of(AiJobType.INDEX_DOCUMENT, AiJobType.REMOVE_DOCUMENT);
    }

    @Override
    public void handle(AiJob job) {
        if (job.type() == AiJobType.REMOVE_DOCUMENT) {
            remove(job);
        } else {
            index(job);
        }
    }

    private void index(AiJob job) {
        KnowledgeDocument document = repository.findById(job.entityId()).orElse(null);
        if (document == null || document.getStatus() != KnowledgeStatus.PUBLISHED) {
            return;
        }
        long version = document.getVersion();
        EmbedResult result;
        try {
            result = aiClient.embed(new EmbedRequest(document.getOrganizationId(), document.getId(),
                    document.getTitle(), document.getType().name(), document.getContent()));
        } catch (AiServiceException ex) {
            transaction.executeWithoutResult(status -> repository.findById(job.entityId()).ifPresent(current -> {
                if (current.getVersion() == version) {
                    current.markIndexFailed(ex.errorCode() + ": " + ex.getMessage());
                }
            }));
            log.warn("Knowledge document {} could not be indexed: {}", job.entityId(), ex.errorCode());
            return;
        }
        transaction.executeWithoutResult(status -> {
            KnowledgeDocument current = repository.findById(job.entityId()).orElse(null);
            if (current == null) {
                return;
            }
            if (current.getStatus() != KnowledgeStatus.PUBLISHED) {
                jobs.publish(AiJob.of(AiJobType.REMOVE_DOCUMENT, job.organizationId(), job.entityId(),
                        job.correlationId()));
            } else if (current.getVersion() != version) {
                jobs.publish(AiJob.of(AiJobType.INDEX_DOCUMENT, job.organizationId(), job.entityId(),
                        job.correlationId()));
            } else {
                current.markIndexed(result.chunkCount(), result.embeddingModel(), clock.instant());
                log.info("Knowledge document {} indexed: {} chunks with {}", current.getId(), result.chunkCount(),
                        result.embeddingModel());
            }
        });
    }

    private void remove(AiJob job) {
        try {
            aiClient.deleteDocument(job.organizationId(), job.entityId());
        } catch (AiServiceException ex) {
            // Sigue PENDING: el reconciliador lo reintentará
            log.warn("Knowledge document {} not removed from the vector store yet: {}", job.entityId(),
                    ex.errorCode());
            return;
        }
        transaction.executeWithoutResult(status -> repository.findById(job.entityId()).ifPresent(current -> {
            if (current.getStatus() != KnowledgeStatus.PUBLISHED) {
                current.markNotIndexed();
            }
        }));
    }
}
