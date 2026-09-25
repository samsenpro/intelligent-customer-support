package com.supportmind.support;

import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

/** Respuestas simuladas del servicio de IA (Python) con el mismo contrato (snake_case) que el real. */
public final class AiServiceStubs {

    public static final String REFUND_DOCUMENT_ID = "11111111-1111-1111-1111-111111111111";

    private AiServiceStubs() {
    }

    public static MappingBuilder classifyEndpoint() {
        return post(urlEqualTo("/api/v1/ai/classify"));
    }

    public static MappingBuilder sentimentEndpoint() {
        return post(urlEqualTo("/api/v1/ai/sentiment"));
    }

    public static MappingBuilder chatEndpoint() {
        return post(urlEqualTo("/api/v1/ai/chat"));
    }

    public static MappingBuilder summarizeEndpoint() {
        return post(urlEqualTo("/api/v1/ai/summarize"));
    }

    public static MappingBuilder suggestEndpoint() {
        return post(urlEqualTo("/api/v1/ai/suggest"));
    }

    public static MappingBuilder embedEndpoint() {
        return post(urlEqualTo("/api/v1/knowledge/embed"));
    }

    public static MappingBuilder searchEndpoint() {
        return post(urlEqualTo("/api/v1/knowledge/search"));
    }

    public static MappingBuilder deleteDocumentEndpoint() {
        return any(urlPathMatching("/api/v1/knowledge/[0-9a-f-]+"));
    }

    public static ResponseDefinitionBuilder json(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }

    public static ResponseDefinitionBuilder classification(String intent, String category, String priority) {
        return json("""
                {"intent": "%s", "category": "%s", "priority": "%s", "confidence": 0.9, "strategy": "rules",
                 "urgent_language": false}""".formatted(intent, category, priority));
    }

    public static ResponseDefinitionBuilder sentiment(String sentiment) {
        return json("""
                {"sentiment": "%s", "confidence": 0.8, "score": 0.0, "strategy": "lexicon"}""".formatted(sentiment));
    }

    /** Respuesta en streaming (SSE) con dos fragmentos de texto y el resultado final. */
    public static ResponseDefinitionBuilder chatStream(String status, String answer, double confidence) {
        return chatStream(status, answer, confidence, "REFUND_REQUEST", "BILLING", "HIGH", null);
    }

    public static ResponseDefinitionBuilder chatStream(String status, String answer, double confidence, String intent,
                                                       String category, String priority, String handoffReason) {
        int middle = answer.length() / 2;
        String sources = "ANSWERED".equals(status) ? """
                [{"document_id": "%s", "title": "Política de reembolsos", "document_type": "POLICY",
                  "chunk_index": 0, "score": 0.71}]""".formatted(REFUND_DOCUMENT_ID) : "[]";
        String intentJson = """
                {"intent": "%s", "category": "%s", "priority": "%s", "confidence": 0.9, "strategy": "hint",
                 "urgent_language": false}""".formatted(intent, category, priority).replace("\n", " ");
        String done = """
                {"status": "%s", "answer": "%s", "confidence": %s, "intent": %s, "language": "es", "sources": %s,
                 "handoff": {"requested": %s, "reason": %s}, "model": "test-llm",
                 "prompt_version": "customer_response_v2",
                 "validation": {"valid": true, "issues": [], "grounding": 0.9}, "degraded": false, "latency_ms": 120}"""
                .formatted(status, answer, confidence, intentJson, sources.replace("\n", " "),
                        handoffReason != null, handoffReason == null ? "null" : "\"" + handoffReason + "\"")
                .replace("\n", " ");
        String body = "event: meta\ndata: {\"language\": \"es\"}\n\n"
                + "event: delta\ndata: {\"text\": \"" + answer.substring(0, middle) + "\"}\n\n"
                + "event: delta\ndata: {\"text\": \"" + answer.substring(middle) + "\"}\n\n"
                + "event: done\ndata: " + done + "\n\n";
        return aResponse().withStatus(200).withHeader("Content-Type", "text/event-stream").withBody(body);
    }

    public static ResponseDefinitionBuilder summary(String text) {
        return json("""
                {"summary": "%s", "strategy": "llm", "message_count": 4}""".formatted(text));
    }

    public static ResponseDefinitionBuilder suggestion(String text, double confidence) {
        return json("""
                {"suggested_response": "%s", "confidence": %s, "has_context": true, "model": "test-llm",
                 "prompt_version": "agent_suggestion_v1", "language": "es",
                 "sources": [{"document_id": "%s", "title": "Política de reembolsos", "document_type": "POLICY",
                              "chunk_index": 0, "score": 0.7}]}""".formatted(text, confidence, REFUND_DOCUMENT_ID));
    }

    /** WireMock copia el document_id de la petición en la respuesta, como el servicio real. */
    public static ResponseDefinitionBuilder embedded(int chunks) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withTransformers("response-template")
                .withBody("""
                        {"document_id": "{{jsonPath request.body '$.document_id'}}", "chunk_count": %d,
                         "embedding_model": "hash-768", "dimensions": 768, "duration_ms": 15}""".formatted(chunks));
    }

    public static ResponseDefinitionBuilder searchResults() {
        return json("""
                {"embedding_model": "hash-768", "results": [
                  {"document_id": "%s", "title": "Política de reembolsos", "document_type": "POLICY",
                   "chunk_index": 1, "content": "El reembolso se acredita en 5 a 10 días hábiles.", "score": 0.64}]}"""
                .formatted(REFUND_DOCUMENT_ID));
    }

    public static ResponseDefinitionBuilder deleted() {
        return json("""
                {"document_id": "%s", "deleted_chunks": 3}""".formatted(REFUND_DOCUMENT_ID));
    }

    public static ResponseDefinitionBuilder error(int status, String code) {
        return aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"error": {"code": "%s", "message": "simulated %s", "correlation_id": "test"}}"""
                        .formatted(code, code));
    }
}
