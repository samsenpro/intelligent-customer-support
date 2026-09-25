package com.supportmind.analytics;

import com.supportmind.analytics.AnalyticsDtos.AnalyticsResponse;
import com.supportmind.analytics.AnalyticsDtos.DailyMessages;
import com.supportmind.analytics.AnalyticsDtos.DateRange;
import com.supportmind.analytics.AnalyticsDtos.Overview;
import com.supportmind.ticket.TicketCategory;
import com.supportmind.ticket.TicketPriority;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Métricas de negocio para supervisores, calculadas con consultas agregadas en PostgreSQL (siempre
 * filtradas por organización). El resultado se cachea en Redis un minuto: el dashboard puede
 * refrescarse a menudo sin repetir las agregaciones.
 */
@Service
public class AnalyticsService {

    public static final String CACHE = "analytics";

    private final NamedParameterJdbcTemplate jdbc;

    public AnalyticsService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CACHE, key = "#organizationId + ':' + #range.from() + ':' + #range.to()")
    public AnalyticsResponse compute(UUID organizationId, DateRange range) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("org", organizationId)
                .addValue("from", Timestamp.from(range.from().atStartOfDay().toInstant(ZoneOffset.UTC)))
                .addValue("to", Timestamp.from(range.to().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)));
        return new AnalyticsResponse(range, overview(params), messagesPerDay(params, range),
                ticketsBy("priority", TicketPriority.values(), params),
                ticketsBy("category", TicketCategory.values(), params));
    }

    private Overview overview(MapSqlParameterSource params) {
        Map<String, Object> conversations = jdbc.queryForMap("""
                SELECT count(*) AS total,
                       avg(extract(epoch FROM first_response_at - first_customer_message_at))
                           FILTER (WHERE first_response_at IS NOT NULL AND first_customer_message_at IS NOT NULL)
                           AS avg_response
                FROM conversations
                WHERE organization_id = :org AND created_at >= :from AND created_at < :to""", params);
        Map<String, Object> tickets = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE status IN ('OPEN', 'IN_PROGRESS', 'WAITING')) AS open,
                       count(*) FILTER (WHERE resolved_at >= :from AND resolved_at < :to) AS resolved
                FROM tickets WHERE organization_id = :org""", params);
        // Conversaciones en las que la IA respondió en el rango, y cómo terminaron
        Map<String, Object> ai = jdbc.queryForMap("""
                WITH handled AS (
                    SELECT DISTINCT conversation_id FROM ai_responses
                    WHERE organization_id = :org AND created_at >= :from AND created_at < :to)
                SELECT count(*) AS handled,
                       count(*) FILTER (WHERE c.handoff_reason IS NULL) AS contained,
                       count(*) FILTER (WHERE c.handoff_reason IS NOT NULL AND c.handoff_reason <> 'AGENT_TOOK_OVER')
                           AS handed_off
                FROM handled h JOIN conversations c ON c.id = h.conversation_id""", params);
        Double avgConfidence = jdbc.queryForObject("""
                SELECT avg(confidence) FROM ai_responses
                WHERE organization_id = :org AND created_at >= :from AND created_at < :to AND model IS NOT NULL""",
                params, Double.class);
        Long totalMessages = jdbc.queryForObject("""
                SELECT count(*) FROM messages
                WHERE organization_id = :org AND created_at >= :from AND created_at < :to""", params, Long.class);

        long handled = number(ai.get("handled"));
        return new Overview(
                number(conversations.get("total")),
                number(tickets.get("open")),
                number(tickets.get("resolved")),
                round(decimal(conversations.get("avg_response"))),
                handled,
                handled == 0 ? null : round((double) number(ai.get("contained")) / handled),
                handled == 0 ? null : round((double) number(ai.get("handed_off")) / handled),
                round(avgConfidence),
                totalMessages == null ? 0 : totalMessages);
    }

    private List<DailyMessages> messagesPerDay(MapSqlParameterSource params, DateRange range) {
        Map<LocalDate, DailyMessages> byDay = new LinkedHashMap<>();
        jdbc.query("""
                SELECT (created_at AT TIME ZONE 'UTC')::date AS day,
                       count(*) AS total,
                       count(*) FILTER (WHERE sender_type = 'CUSTOMER') AS customer,
                       count(*) FILTER (WHERE sender_type = 'AI') AS ai,
                       count(*) FILTER (WHERE sender_type = 'AGENT') AS agent
                FROM messages
                WHERE organization_id = :org AND created_at >= :from AND created_at < :to
                GROUP BY day""", params, rs -> {
            LocalDate day = rs.getObject("day", LocalDate.class);
            byDay.put(day, new DailyMessages(day, rs.getLong("total"), rs.getLong("customer"), rs.getLong("ai"),
                    rs.getLong("agent")));
        });
        // Serie completa: los días sin mensajes aparecen con 0 (el gráfico no salta días)
        List<DailyMessages> series = new ArrayList<>();
        for (LocalDate day = range.from(); !day.isAfter(range.to()); day = day.plusDays(1)) {
            series.add(byDay.getOrDefault(day, new DailyMessages(day, 0, 0, 0, 0)));
        }
        return series;
    }

    private Map<String, Long> ticketsBy(String column, Enum<?>[] values, MapSqlParameterSource params) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Enum<?> value : values) {
            counts.put(value.name(), 0L);
        }
        // La columna viene de una lista cerrada (priority o category), nunca de la petición
        jdbc.query("SELECT " + column + " AS value, count(*) AS total FROM tickets "
                        + "WHERE organization_id = :org AND created_at >= :from AND created_at < :to GROUP BY " + column,
                params, rs -> {
                    counts.put(rs.getString("value"), rs.getLong("total"));
                });
        return counts;
    }

    private static long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static Double decimal(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }

    private static Double round(Double value) {
        return value == null ? null : Math.round(value * 1000) / 1000.0;
    }
}
