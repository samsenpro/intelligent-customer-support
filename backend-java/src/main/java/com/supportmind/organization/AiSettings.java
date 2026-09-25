package com.supportmind.organization;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuración de la IA de una organización: cuándo responde sola, cuándo deriva a un humano y
 * cuándo crea tickets. Cada empresa (tenant) decide su propio equilibrio entre automatización y
 * atención humana.
 */
@Embeddable
public class AiSettings {

    /** La IA responde automáticamente a los clientes. Sin esto solo sugiere respuestas a los agentes. */
    @Column(name = "ai_auto_reply_enabled", nullable = false)
    private boolean autoReplyEnabled = true;

    @Column(name = "ai_confidence_threshold", nullable = false, precision = 3, scale = 2)
    private BigDecimal confidenceThreshold = new BigDecimal("0.55");

    @Enumerated(EnumType.STRING)
    @Column(name = "no_context_action", nullable = false, length = 20)
    private NoContextAction noContextAction = NoContextAction.ASK_MORE_INFO;

    /** Respuestas fallidas seguidas (sin contexto o con baja confianza) antes de derivar a un humano. */
    @Column(name = "max_failed_ai_answers", nullable = false)
    private int maxFailedAiAnswers = 2;

    /** Categorías que siempre atiende un humano (p. ej. SECURITY, LEGAL). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sensitive_categories", nullable = false)
    private List<String> sensitiveCategories = new ArrayList<>(List.of("SECURITY", "LEGAL"));

    @Column(name = "handoff_on_urgent", nullable = false)
    private boolean handoffOnUrgent = true;

    @Column(name = "auto_ticket_enabled", nullable = false)
    private boolean autoTicketEnabled = true;

    /** Permite que los clientes se registren solos en el portal con el slug de la organización. */
    @Column(name = "customer_signup_enabled", nullable = false)
    private boolean customerSignupEnabled;

    /** A partir de cuántos mensajes se resume la conversación para reducir tokens. */
    @Column(name = "summary_after_messages", nullable = false)
    private int summaryAfterMessages = 12;

    protected AiSettings() {
        // JPA
    }

    public static AiSettings defaults(BigDecimal confidenceThreshold) {
        AiSettings settings = new AiSettings();
        settings.confidenceThreshold = confidenceThreshold.setScale(2, RoundingMode.HALF_UP);
        return settings;
    }

    public void update(boolean autoReplyEnabled, BigDecimal confidenceThreshold, NoContextAction noContextAction,
                       int maxFailedAiAnswers, List<String> sensitiveCategories, boolean handoffOnUrgent,
                       boolean autoTicketEnabled, boolean customerSignupEnabled, int summaryAfterMessages) {
        this.autoReplyEnabled = autoReplyEnabled;
        this.confidenceThreshold = confidenceThreshold.setScale(2, RoundingMode.HALF_UP);
        this.noContextAction = noContextAction;
        this.maxFailedAiAnswers = maxFailedAiAnswers;
        this.sensitiveCategories = new ArrayList<>(sensitiveCategories);
        this.handoffOnUrgent = handoffOnUrgent;
        this.autoTicketEnabled = autoTicketEnabled;
        this.customerSignupEnabled = customerSignupEnabled;
        this.summaryAfterMessages = summaryAfterMessages;
    }

    public boolean isAutoReplyEnabled() {
        return autoReplyEnabled;
    }

    public BigDecimal getConfidenceThreshold() {
        return confidenceThreshold;
    }

    public NoContextAction getNoContextAction() {
        return noContextAction;
    }

    public int getMaxFailedAiAnswers() {
        return maxFailedAiAnswers;
    }

    public List<String> getSensitiveCategories() {
        return List.copyOf(sensitiveCategories);
    }

    public boolean isHandoffOnUrgent() {
        return handoffOnUrgent;
    }

    public boolean isAutoTicketEnabled() {
        return autoTicketEnabled;
    }

    public boolean isCustomerSignupEnabled() {
        return customerSignupEnabled;
    }

    public int getSummaryAfterMessages() {
        return summaryAfterMessages;
    }
}
