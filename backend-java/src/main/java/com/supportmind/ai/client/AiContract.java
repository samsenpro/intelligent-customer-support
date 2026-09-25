package com.supportmind.ai.client;

import java.util.List;
import java.util.UUID;

/**
 * Contrato HTTP con el servicio de IA (Python/FastAPI). En JSON los campos viajan en snake_case: el
 * cliente usa su propio ObjectMapper, así el resto de la API sigue en camelCase.
 */
public final class AiContract {

    private AiContract() {
    }

    // ---------------------------------------------------------------- clasificación y sentimiento

    public record ClassifyRequest(String message) {
    }

    public record Classification(String intent, String category, String priority, double confidence,
                                 String strategy, boolean urgentLanguage) {
    }

    public record SentimentRequest(String message) {
    }

    public record Sentiment(String sentiment, double confidence, double score, String strategy) {
    }

    // ---------------------------------------------------------------- chat (RAG)

    /** Mensaje del historial: role es CUSTOMER, AGENT, AI o SYSTEM. */
    public record HistoryItem(String role, String content) {
    }

    public record ChatSettings(double confidenceThreshold, String noContextAction, Integer topK) {
    }

    public record IntentHint(String intent, String category, String priority, double confidence, String strategy) {
    }

    public record ChatRequest(UUID organizationId, String organizationName, UUID conversationId, String message,
                              List<HistoryItem> history, String summary, ChatSettings settings,
                              IntentHint intentHint, boolean stream) {
    }

    public record Source(UUID documentId, String title, String documentType, int chunkIndex, double score) {
    }

    public record Handoff(boolean requested, String reason) {
    }

    public record Validation(boolean valid, List<String> issues, double grounding) {
    }

    /**
     * Resultado del pipeline RAG. status: ANSWERED, NO_RELEVANT_CONTEXT, AI_HANDOFF_REQUESTED o BLOCKED.
     */
    public record ChatResult(String status, String answer, double confidence, Classification intent,
                             String language, List<Source> sources, Handoff handoff, String model,
                             String promptVersion, Validation validation, boolean degraded, long latencyMs) {
    }

    // ---------------------------------------------------------------- resumen y sugerencias

    public record SummarizeRequest(UUID organizationId, UUID conversationId, String previousSummary,
                                   List<HistoryItem> messages) {
    }

    public record Summary(String summary, String strategy, int messageCount) {
    }

    public record SuggestRequest(UUID organizationId, String organizationName, UUID conversationId,
                                 String customerMessage, List<HistoryItem> history, String summary, Integer topK) {
    }

    public record Suggestion(String suggestedResponse, double confidence, List<Source> sources, boolean hasContext,
                             String model, String promptVersion, String language) {
    }

    // ---------------------------------------------------------------- base de conocimiento

    public record EmbedRequest(UUID organizationId, UUID documentId, String title, String documentType,
                               String content) {
    }

    public record EmbedResult(UUID documentId, int chunkCount, String embeddingModel, int dimensions,
                              long durationMs) {
    }

    public record SearchRequest(UUID organizationId, String query, Integer topK) {
    }

    public record SearchHit(UUID documentId, String title, String documentType, int chunkIndex, String content,
                            double score) {
    }

    public record SearchResult(List<SearchHit> results, String embeddingModel) {
    }
}
