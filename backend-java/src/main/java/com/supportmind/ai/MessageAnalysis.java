package com.supportmind.ai;

import com.supportmind.ai.client.AiContract.Classification;
import com.supportmind.ai.client.AiContract.Sentiment;
import com.supportmind.ticket.TicketCategory;
import com.supportmind.ticket.TicketPriority;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Análisis de un mensaje del cliente (clasificación + sentimiento). Se guarda en
 * {@code message.metadata.analysis} y lo ve el agente como información auxiliar.
 * <p>
 * El sentimiento nunca decide nada por sí solo: la prioridad y el escalamiento dependen de la
 * intención, la categoría y el lenguaje de urgencia.
 */
public record MessageAnalysis(String intent, TicketCategory category, TicketPriority priority, double confidence,
                              String strategy, boolean urgentLanguage, String sentiment, double sentimentConfidence) {

    public static final String METADATA_KEY = "analysis";

    public static MessageAnalysis of(Classification classification, Sentiment sentiment) {
        return new MessageAnalysis(classification.intent(), TicketCategory.valueOf(classification.category()),
                TicketPriority.valueOf(classification.priority()), classification.confidence(),
                classification.strategy(), classification.urgentLanguage(),
                sentiment != null ? sentiment.sentiment() : null,
                sentiment != null ? sentiment.confidence() : 0);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("intent", intent);
        map.put("category", category.name());
        map.put("priority", priority.name());
        map.put("confidence", confidence);
        map.put("strategy", strategy);
        map.put("urgentLanguage", urgentLanguage);
        if (sentiment != null) {
            map.put("sentiment", sentiment);
            map.put("sentimentConfidence", sentimentConfidence);
        }
        return map;
    }

    /** Análisis guardado en los metadatos de un mensaje, o null si no se analizó (o está incompleto). */
    @SuppressWarnings("unchecked")
    public static MessageAnalysis fromMetadata(Map<String, Object> metadata) {
        if (!(metadata.get(METADATA_KEY) instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> map = (Map<String, Object>) raw;
        try {
            return new MessageAnalysis((String) map.get("intent"), TicketCategory.valueOf((String) map.get("category")),
                    TicketPriority.valueOf((String) map.get("priority")), ((Number) map.get("confidence")).doubleValue(),
                    (String) map.get("strategy"), Boolean.TRUE.equals(map.get("urgentLanguage")),
                    (String) map.get("sentiment"),
                    map.get("sentimentConfidence") instanceof Number n ? n.doubleValue() : 0);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
