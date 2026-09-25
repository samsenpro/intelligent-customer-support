package com.supportmind.ai.jobs;

import com.supportmind.common.web.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Entrega cada job a su handler con el correlation ID de la petición original en el MDC. */
@Component
public class AiJobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AiJobDispatcher.class);

    private final Map<AiJobType, AiJobHandler> handlers = new EnumMap<>(AiJobType.class);

    public AiJobDispatcher(List<AiJobHandler> handlers) {
        for (AiJobHandler handler : handlers) {
            handler.handles().forEach(type -> this.handlers.put(type, handler));
        }
    }

    public void dispatch(AiJob job) {
        MDC.put(CorrelationId.MDC_KEY, job.correlationId() != null ? job.correlationId() : CorrelationId.resolve(null));
        try {
            AiJobHandler handler = handlers.get(job.type());
            if (handler == null) {
                log.error("No handler for AI job type {}", job.type());
                return;
            }
            handler.handle(job);
        } catch (RuntimeException ex) {
            // Cada handler deja su entidad en un estado coherente; aquí solo se registra el fallo
            log.error("AI job {} for {} failed", job.type(), job.entityId(), ex);
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }
}
