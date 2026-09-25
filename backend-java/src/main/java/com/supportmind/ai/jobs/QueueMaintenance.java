package com.supportmind.ai.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Recupera los jobs abandonados por instancias caídas y mantiene acotado el tamaño del stream. */
@Component
public class QueueMaintenance {

    private static final Logger log = LoggerFactory.getLogger(QueueMaintenance.class);

    private final RedisStreamAiJobConsumer queue;

    public QueueMaintenance(RedisStreamAiJobConsumer queue) {
        this.queue = queue;
    }

    @Scheduled(fixedDelayString = "${supportmind.queue.maintenance-interval:60s}",
            initialDelayString = "${supportmind.queue.maintenance-interval:60s}")
    public void run() {
        try {
            int requeued = queue.requeueAbandoned();
            if (requeued > 0) {
                log.warn("Requeued {} abandoned AI jobs", requeued);
            }
            queue.trim();
        } catch (DataAccessException ex) {
            log.warn("AI job queue maintenance skipped, Redis unavailable: {}", ex.getMessage());
        }
    }
}
