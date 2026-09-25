package com.supportmind.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Reintenta una operación transaccional completa cuando otra transacción modificó a la vez la misma
 * entidad (bloqueo optimista con {@code @Version}).
 * <p>
 * Una conversación la actualizan a la vez el cliente (nuevos mensajes), los agentes y los jobs de IA
 * (análisis, respuestas). Sin reintento, esas carreras normales acabarían en un 409 para el usuario o
 * en una respuesta de la IA perdida. La operación debe abrir su propia transacción dentro del supplier.
 */
@Component
public class OptimisticRetry {

    private static final Logger log = LoggerFactory.getLogger(OptimisticRetry.class);
    private static final int MAX_ATTEMPTS = 4;

    public <T> T execute(Supplier<T> operation) {
        for (int attempt = 1; ; attempt++) {
            try {
                return operation.get();
            } catch (OptimisticLockingFailureException ex) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw ex;
                }
                log.debug("Optimistic lock conflict (attempt {}), retrying", attempt);
                backoff(attempt);
            }
        }
    }

    public void run(Runnable operation) {
        execute(() -> {
            operation.run();
            return null;
        });
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(10, 30L * attempt));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
