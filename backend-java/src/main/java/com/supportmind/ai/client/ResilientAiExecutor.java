package com.supportmind.ai.client;

import com.supportmind.common.BusinessMetrics;
import com.supportmind.common.web.CorrelationId;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Aplica Resilience4j a cada llamada al servicio de IA:
 * <pre>
 *   Retry( CircuitBreaker( TimeLimiter( llamada HTTP ) ) )
 * </pre>
 * <ul>
 *   <li>TimeLimiter: cada intento tiene un tiempo máximo; al vencer se cancela y cuenta como fallo.</li>
 *   <li>CircuitBreaker: si la mitad de las últimas llamadas fallan, se abre y las siguientes fallan al
 *       instante ({@link CallNotPermittedException}) sin esperar timeouts.</li>
 *   <li>Retry: reintenta con backoff exponencial solo los fallos transitorios, nunca con el circuito
 *       abierto ni una respuesta en streaming que ya empezó a llegar al cliente.</li>
 * </ul>
 * Además registra ai_requests_total, ai_errors_total y ai_latency_seconds, y propaga el correlation ID
 * al hilo que hace la llamada.
 */
@Component
public class ResilientAiExecutor {

    public static final String INSTANCE = "aiService";
    public static final String STREAM_TIME_LIMITER = "aiStream";

    private static final Logger log = LoggerFactory.getLogger(ResilientAiExecutor.class);

    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final TimeLimiter timeLimiter;
    private final TimeLimiter streamTimeLimiter;
    private final BusinessMetrics metrics;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ResilientAiExecutor(CircuitBreakerRegistry circuitBreakers, RetryRegistry retries,
                               TimeLimiterRegistry timeLimiters, BusinessMetrics metrics) {
        this.circuitBreaker = circuitBreakers.circuitBreaker(INSTANCE);
        this.retry = retries.retry(INSTANCE);
        this.timeLimiter = timeLimiters.timeLimiter(INSTANCE);
        this.streamTimeLimiter = timeLimiters.timeLimiter(STREAM_TIME_LIMITER);
        this.metrics = metrics;
    }

    public <T> T call(String operation, Supplier<T> call) {
        return execute(operation, timeLimiter, call);
    }

    /**
     * Llamada en streaming. La función recibe una marca que la llamada activa al emitir el primer
     * fragmento: a partir de ahí un fallo ya no se reintenta (el cliente vería el texto duplicado).
     */
    public <T> T callStream(String operation, Function<AtomicBoolean, T> call) {
        AtomicBoolean emitted = new AtomicBoolean();
        return execute(operation, streamTimeLimiter, () -> {
            try {
                return call.apply(emitted);
            } catch (AiServiceUnavailableException ex) {
                if (emitted.get()) {
                    throw new AiServiceRejectedException("AI_STREAM_INTERRUPTED",
                            "The AI answer was interrupted after it started: " + ex.getMessage());
                }
                throw ex;
            }
        });
    }

    private <T> T execute(String operation, TimeLimiter limiter, Supplier<T> call) {
        String correlationId = MDC.get(CorrelationId.MDC_KEY);
        Callable<T> attempt = () -> {
            try {
                return limiter.executeFutureSupplier(() -> executor.submit(() -> withMdc(correlationId, call)));
            } catch (TimeoutException ex) {
                throw new AiServiceUnavailableException("AI_SERVICE_TIMEOUT",
                        "The AI service did not answer within "
                                + limiter.getTimeLimiterConfig().getTimeoutDuration().toSeconds() + " s",
                        ex);
            }
        };
        Callable<T> decorated = Retry.decorateCallable(retry, CircuitBreaker.decorateCallable(circuitBreaker, attempt));

        long started = System.nanoTime();
        try {
            T result = decorated.call();
            metrics.aiRequest(operation, "success", elapsed(started));
            return result;
        } catch (CallNotPermittedException ex) {
            metrics.aiRequest(operation, "circuit_open", elapsed(started));
            metrics.aiError(operation, "AI_CIRCUIT_OPEN");
            throw new AiServiceUnavailableException("AI_CIRCUIT_OPEN", "AI temporarily unavailable", ex);
        } catch (AiServiceException ex) {
            metrics.aiRequest(operation, ex instanceof AiServiceRejectedException ? "rejected" : "error",
                    elapsed(started));
            metrics.aiError(operation, ex.errorCode());
            log.warn("AI service {} failed: {} - {}", operation, ex.errorCode(), ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            metrics.aiRequest(operation, "error", elapsed(started));
            metrics.aiError(operation, "AI_CLIENT_ERROR");
            throw new AiServiceUnavailableException("AI_CLIENT_ERROR", "Unexpected error calling the AI service", ex);
        }
    }

    private static <T> T withMdc(String correlationId, Supplier<T> call) {
        if (correlationId != null) {
            MDC.put(CorrelationId.MDC_KEY, correlationId);
        }
        try {
            return call.get();
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }

    private static Duration elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
