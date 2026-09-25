package com.supportmind.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Ejecuta efectos externos (eventos en tiempo real, jobs de IA, caché) solo si la transacción se
 * confirma: nunca se publica un mensaje o se encola un job de algo que después se deshizo.
 */
public final class AfterCommit {

    private static final Logger log = LoggerFactory.getLogger(AfterCommit.class);

    private AfterCommit() {
    }

    public static void run(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            safely(action);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                safely(action);
            }
        });
    }

    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ex) {
            // Los datos ya están guardados: un fallo aquí (p. ej. Redis caído) no debe convertirse en un
            // error para el cliente
            log.warn("After-commit action failed: {}", ex.toString());
        }
    }
}
