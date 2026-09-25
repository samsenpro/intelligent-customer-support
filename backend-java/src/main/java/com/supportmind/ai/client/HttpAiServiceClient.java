package com.supportmind.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.supportmind.ai.client.AiContract.ChatRequest;
import com.supportmind.ai.client.AiContract.ChatResult;
import com.supportmind.ai.client.AiContract.Classification;
import com.supportmind.ai.client.AiContract.ClassifyRequest;
import com.supportmind.ai.client.AiContract.EmbedRequest;
import com.supportmind.ai.client.AiContract.EmbedResult;
import com.supportmind.ai.client.AiContract.SearchRequest;
import com.supportmind.ai.client.AiContract.SearchResult;
import com.supportmind.ai.client.AiContract.Sentiment;
import com.supportmind.ai.client.AiContract.SentimentRequest;
import com.supportmind.ai.client.AiContract.SuggestRequest;
import com.supportmind.ai.client.AiContract.Suggestion;
import com.supportmind.ai.client.AiContract.SummarizeRequest;
import com.supportmind.ai.client.AiContract.Summary;
import com.supportmind.common.web.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Implementación HTTP del cliente del servicio de IA. Cada operación pasa por
 * {@link ResilientAiExecutor} (Retry, CircuitBreaker, TimeLimiter, métricas y correlation ID).
 */
@Component
public class HttpAiServiceClient implements AiServiceClient {

    private static final Logger log = LoggerFactory.getLogger(HttpAiServiceClient.class);

    private final RestClient restClient;
    private final ResilientAiExecutor executor;
    private final ObjectMapper snakeCase;

    public HttpAiServiceClient(@Qualifier("aiRestClient") RestClient restClient, ResilientAiExecutor executor,
                               Jackson2ObjectMapperBuilder jacksonBuilder) {
        this.restClient = restClient;
        this.executor = executor;
        this.snakeCase = jacksonBuilder.build().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    @Override
    public Classification classify(String message) {
        return executor.call("classify", () -> post("/api/v1/ai/classify", new ClassifyRequest(message),
                Classification.class));
    }

    @Override
    public Sentiment sentiment(String message) {
        return executor.call("sentiment", () -> post("/api/v1/ai/sentiment", new SentimentRequest(message),
                Sentiment.class));
    }

    @Override
    public ChatResult chat(ChatRequest request, Consumer<String> onDelta) {
        return executor.callStream("chat", emitted -> stream(request, onDelta, emitted));
    }

    @Override
    public Summary summarize(SummarizeRequest request) {
        return executor.call("summarize", () -> post("/api/v1/ai/summarize", request, Summary.class));
    }

    @Override
    public Suggestion suggest(SuggestRequest request) {
        return executor.call("suggest", () -> post("/api/v1/ai/suggest", request, Suggestion.class));
    }

    @Override
    public EmbedResult embed(EmbedRequest request) {
        return executor.call("embed", () -> post("/api/v1/knowledge/embed", request, EmbedResult.class));
    }

    @Override
    public void deleteDocument(UUID organizationId, UUID documentId) {
        executor.call("delete_document", () -> http(() -> restClient.delete()
                .uri(uri -> uri.path("/api/v1/knowledge/{id}").queryParam("organization_id", organizationId)
                        .build(documentId))
                .header(CorrelationId.HEADER, correlationId())
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, response) -> {
                    throw toException(response);
                })
                .toBodilessEntity()));
    }

    @Override
    public SearchResult search(SearchRequest request) {
        return executor.call("search", () -> post("/api/v1/knowledge/search", request, SearchResult.class));
    }

    // ---------------------------------------------------------------- HTTP

    private <T> T post(String path, Object body, Class<T> type) {
        return http(() -> {
            T result = restClient.post()
                    .uri(path)
                    .header(CorrelationId.HEADER, correlationId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, response) -> {
                        throw toException(response);
                    })
                    .body(type);
            if (result == null) {
                throw new AiServiceRejectedException("AI_INVALID_RESPONSE", "The AI service returned an empty body");
            }
            return result;
        });
    }

    /**
     * Lee la respuesta SSE del servicio de IA (eventos meta, delta, done y error) y reenvía cada
     * fragmento de texto a medida que llega.
     */
    private ChatResult stream(ChatRequest request, Consumer<String> onDelta, AtomicBoolean emitted) {
        return http(() -> restClient.post()
                .uri("/api/v1/ai/chat")
                .header(CorrelationId.HEADER, correlationId())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .body(request)
                .exchange((req, response) -> {
                    if (response.getStatusCode().isError()) {
                        throw toException(response);
                    }
                    return readEvents(response, onDelta, emitted);
                }));
    }

    private ChatResult readEvents(ClientHttpResponse response, Consumer<String> onDelta, AtomicBoolean emitted)
            throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.getBody(),
                StandardCharsets.UTF_8))) {
            String event = null;
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    ChatResult result = handleEvent(event, data.toString(), onDelta, emitted);
                    if (result != null) {
                        return result;
                    }
                    event = null;
                    data.setLength(0);
                } else if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5).trim());
                }
            }
        }
        throw new AiServiceUnavailableException("AI_STREAM_INCOMPLETE", "The AI stream ended without a result", null);
    }

    private ChatResult handleEvent(String event, String data, Consumer<String> onDelta, AtomicBoolean emitted)
            throws IOException {
        if (event == null) {
            return null;
        }
        switch (event) {
            case "delta" -> {
                String text = snakeCase.readTree(data).path("text").asText("");
                if (!text.isEmpty()) {
                    emitted.set(true);
                    onDelta.accept(text);
                }
            }
            case "done" -> {
                return snakeCase.readValue(data, ChatResult.class);
            }
            case "error" -> {
                JsonNode error = snakeCase.readTree(data);
                String code = error.path("code").asText("AI_STREAM_ERROR");
                throw new AiServiceUnavailableException(code, error.path("message").asText("AI stream error"), null);
            }
            default -> {
                // "meta" y eventos futuros: no se necesitan aquí
            }
        }
        return null;
    }

    /** Traduce los errores de transporte: sin respuesta o timeout es transitorio; respuesta ilegible no. */
    private static <T> T http(Supplier<T> call) {
        try {
            return call.get();
        } catch (ResourceAccessException ex) {
            boolean timeout = ex.getCause() instanceof HttpTimeoutException;
            throw new AiServiceUnavailableException(timeout ? "AI_SERVICE_TIMEOUT" : "AI_SERVICE_UNAVAILABLE",
                    timeout ? "The AI service did not respond in time" : "The AI service is unreachable", ex);
        } catch (AiServiceException ex) {
            throw ex;
        } catch (RestClientException ex) {
            throw new AiServiceRejectedException("AI_INVALID_RESPONSE", "The AI service returned an invalid response");
        }
    }

    private AiServiceException toException(ClientHttpResponse response) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        String code = "AI_SERVICE_ERROR";
        String message = "AI service returned HTTP " + status.value();
        try {
            JsonNode error = snakeCase.readTree(response.getBody()).path("error");
            code = error.path("code").asText(code);
            message = error.path("message").asText(message);
        } catch (IOException ignored) {
            // Cuerpo vacío o no JSON: se usan los valores por defecto
        }
        if (status.is5xxServerError() || status.value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
            return new AiServiceUnavailableException(code, message, null);
        }
        if (status.value() == HttpStatus.UNAUTHORIZED.value()) {
            // Error de configuración: reintentar no lo arreglará
            log.error("AI service rejected the internal API key: check AI_SERVICE_API_KEY on both services");
            return new AiServiceRejectedException("AI_SERVICE_AUTH_FAILED", "The AI service rejected the credentials");
        }
        return new AiServiceRejectedException(code, message);
    }

    private static String correlationId() {
        String id = MDC.get(CorrelationId.MDC_KEY);
        return id != null ? id : CorrelationId.resolve(null);
    }
}
