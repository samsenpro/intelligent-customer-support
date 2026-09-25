package com.supportmind.ai;

import com.supportmind.ai.client.AiContract.ChatResult;
import com.supportmind.ai.client.AiContract.Classification;
import com.supportmind.ai.client.AiContract.Handoff;
import com.supportmind.conversation.HandoffReason;
import com.supportmind.organization.AiSettings;
import com.supportmind.organization.NoContextAction;
import com.supportmind.ticket.TicketCategory;
import com.supportmind.ticket.TicketPriority;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class HandoffPolicyTest {

    private final HandoffPolicy policy = new HandoffPolicy();

    private static AiSettings settings(NoContextAction noContextAction, boolean handoffOnUrgent) {
        AiSettings settings = AiSettings.defaults(new BigDecimal("0.55"));
        settings.update(true, new BigDecimal("0.55"), noContextAction, 2, List.of("SECURITY", "LEGAL"),
                handoffOnUrgent, true, false, 12);
        return settings;
    }

    private static MessageAnalysis analysis(String intent, TicketCategory category, TicketPriority priority) {
        return new MessageAnalysis(intent, category, priority, 0.9, "rules", false, "NEUTRAL", 0.7);
    }

    private static ChatResult result(String status, double confidence, String category, String priority) {
        return new ChatResult(status, "respuesta", confidence,
                new Classification("GENERAL_QUESTION", category, priority, 0.8, "rules", false), "es", List.of(),
                new Handoff(false, null), "model", "customer_response_v2", null, false, 100);
    }

    @Test
    void beforeReplyHandsOffExplicitRequestsSensitiveCategoriesAndUrgency() {
        AiSettings settings = settings(NoContextAction.ASK_MORE_INFO, true);
        assertThat(policy.beforeReply(settings, analysis("HUMAN_REQUEST", TicketCategory.GENERAL,
                TicketPriority.MEDIUM), null)).contains(HandoffReason.CUSTOMER_REQUESTED_HUMAN);
        assertThat(policy.beforeReply(settings, analysis("FRAUD_REPORT", TicketCategory.SECURITY,
                TicketPriority.HIGH), null)).contains(HandoffReason.SENSITIVE_CATEGORY);
        assertThat(policy.beforeReply(settings, analysis("PAYMENT_ISSUE", TicketCategory.BILLING,
                TicketPriority.URGENT), null)).contains(HandoffReason.URGENT_TICKET);
        assertThat(policy.beforeReply(settings, analysis("ORDER_STATUS", TicketCategory.SHIPPING,
                TicketPriority.MEDIUM), TicketPriority.URGENT)).contains(HandoffReason.URGENT_TICKET);
        assertThat(policy.beforeReply(settings, analysis("ORDER_STATUS", TicketCategory.SHIPPING,
                TicketPriority.MEDIUM), TicketPriority.HIGH)).isEmpty();
        assertThat(policy.beforeReply(settings, null, null)).isEmpty();
    }

    @Test
    void urgencyOnlyHandsOffWhenTheOrganizationEnablesIt() {
        assertThat(policy.beforeReply(settings(NoContextAction.ASK_MORE_INFO, false),
                analysis("PAYMENT_ISSUE", TicketCategory.BILLING, TicketPriority.URGENT), null)).isEmpty();
    }

    @Test
    void afterReplyChecksConfidenceAgainstTheThreshold() {
        AiSettings settings = settings(NoContextAction.ASK_MORE_INFO, true);
        assertThat(policy.afterReply(settings, 0, result("ANSWERED", 0.80, "GENERAL", "LOW"))).isEmpty();
        assertThat(policy.afterReply(settings, 0, result("ANSWERED", 0.55, "GENERAL", "LOW"))).isEmpty();
        assertThat(policy.afterReply(settings, 0, result("ANSWERED", 0.54, "GENERAL", "LOW")))
                .contains(HandoffReason.LOW_CONFIDENCE);
        assertThat(policy.afterReply(settings, 0, result("AI_HANDOFF_REQUESTED", 0.4, "GENERAL", "LOW")))
                .contains(HandoffReason.LOW_CONFIDENCE);
    }

    @Test
    void noContextAsksForMoreInformationUntilTheFailuresRepeat() {
        AiSettings settings = settings(NoContextAction.ASK_MORE_INFO, true);
        ChatResult noContext = result("NO_RELEVANT_CONTEXT", 0.0, "GENERAL", "LOW");
        assertThat(policy.afterReply(settings, 0, noContext)).isEmpty();
        assertThat(policy.afterReply(settings, 1, noContext)).contains(HandoffReason.REPEATED_FAILED_ANSWERS);
        assertThat(policy.afterReply(settings(NoContextAction.HANDOFF, true), 0, noContext))
                .contains(HandoffReason.NO_RELEVANT_CONTEXT);
    }

    @Test
    void blockedMessagesAndSensitiveResultsFromTheAiService() {
        AiSettings settings = settings(NoContextAction.ASK_MORE_INFO, true);
        assertThat(policy.afterReply(settings, 5, result("BLOCKED", 0.9, "SECURITY", "URGENT"))).isEmpty();
        assertThat(policy.afterReply(settings, 0, result("ANSWERED", 0.9, "LEGAL", "HIGH")))
                .isEqualTo(Optional.of(HandoffReason.SENSITIVE_CATEGORY));
        assertThat(policy.afterReply(settings, 0, result("ANSWERED", 0.9, "BILLING", "URGENT")))
                .contains(HandoffReason.URGENT_TICKET);
    }
}
