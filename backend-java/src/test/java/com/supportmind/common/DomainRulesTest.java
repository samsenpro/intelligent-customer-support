package com.supportmind.common;

import com.supportmind.ai.AssistantTexts;
import com.supportmind.ai.MessageAnalysis;
import com.supportmind.conversation.Channel;
import com.supportmind.conversation.Conversation;
import com.supportmind.conversation.ConversationStatus;
import com.supportmind.conversation.HandoffReason;
import com.supportmind.exception.ApiException;
import com.supportmind.message.SenderType;
import com.supportmind.organization.Slugs;
import com.supportmind.ticket.Ticket;
import com.supportmind.ticket.TicketAutomation;
import com.supportmind.ticket.TicketCategory;
import com.supportmind.ticket.TicketPriority;
import com.supportmind.ticket.TicketSource;
import com.supportmind.ticket.TicketStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reglas de dominio sin infraestructura: estados de conversaciones y tickets, y utilidades. */
class DomainRulesTest {

    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");

    @Test
    void conversationStatusFollowsTheMessages() {
        Conversation conversation = new Conversation(UUID.randomUUID(), UUID.randomUUID(), Channel.WEB, " Ayuda ", true);
        assertThat(conversation.getSubject()).isEqualTo("Ayuda");
        conversation.recordMessage(SenderType.CUSTOMER, NOW);
        assertThat(conversation.getStatus()).isEqualTo(ConversationStatus.OPEN);
        conversation.recordMessage(SenderType.AI, NOW.plusSeconds(3));
        assertThat(conversation.getStatus()).isEqualTo(ConversationStatus.WAITING_CUSTOMER);
        assertThat(conversation.getFirstResponseAt()).isEqualTo(NOW.plusSeconds(3));
        conversation.recordMessage(SenderType.CUSTOMER, NOW.plusSeconds(10));
        assertThat(conversation.getStatus()).isEqualTo(ConversationStatus.OPEN);
        conversation.recordMessage(SenderType.SYSTEM, NOW.plusSeconds(11));
        assertThat(conversation.getStatus()).isEqualTo(ConversationStatus.OPEN);
        assertThat(conversation.getMessageCount()).isEqualTo(4);
        assertThat(conversation.getFirstResponseAt()).isEqualTo(NOW.plusSeconds(3));
    }

    @Test
    void handoffPutsTheConversationInTheAgentQueueUntilSomeoneTakesIt() {
        Conversation conversation = new Conversation(UUID.randomUUID(), UUID.randomUUID(), Channel.WEB, null, true);
        assertThat(conversation.isInAgentQueue()).isFalse();
        conversation.handOff(HandoffReason.LOW_CONFIDENCE, NOW);
        assertThat(conversation.isAiEnabled()).isFalse();
        assertThat(conversation.isInAgentQueue()).isTrue();
        conversation.assign(UUID.randomUUID());
        assertThat(conversation.isInAgentQueue()).isFalse();
        assertThat(conversation.getStatus()).isEqualTo(ConversationStatus.IN_PROGRESS);
        conversation.returnToAi();
        assertThat(conversation.isAiEnabled()).isTrue();
        assertThat(conversation.getFailedAiAnswers()).isZero();
    }

    @Test
    void failedAiAnswersAreCountedUntilOneSucceeds() {
        Conversation conversation = new Conversation(UUID.randomUUID(), UUID.randomUUID(), Channel.API, null, true);
        conversation.recordAiAnswer(BigDecimal.ZERO, true);
        conversation.recordAiAnswer(BigDecimal.ZERO, true);
        assertThat(conversation.getFailedAiAnswers()).isEqualTo(2);
        conversation.recordAiAnswer(new BigDecimal("0.9"), false);
        assertThat(conversation.getFailedAiAnswers()).isZero();
    }

    @Test
    void closedConversationsRejectMessagesAndCannotBeReopened() {
        Conversation conversation = new Conversation(UUID.randomUUID(), UUID.randomUUID(), Channel.WEB, null, false);
        conversation.changeStatus(ConversationStatus.CLOSED, NOW);
        assertThat(conversation.getResolvedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> conversation.recordMessage(SenderType.CUSTOMER, NOW)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> conversation.changeStatus(ConversationStatus.OPEN, NOW))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void ticketTransitionsAndResolutionTime() {
        Ticket ticket = new Ticket(UUID.randomUUID(), null, UUID.randomUUID(), "S", "D", TicketPriority.LOW,
                TicketCategory.GENERAL, TicketSource.MANUAL, null);
        assertThat(ticket.changeStatus(TicketStatus.RESOLVED, NOW)).containsKey("status");
        assertThat(ticket.getResolvedAt()).isEqualTo(NOW);
        ticket.changeStatus(TicketStatus.IN_PROGRESS, NOW.plusSeconds(60));
        assertThat(ticket.getResolvedAt()).isNull();
        ticket.changeStatus(TicketStatus.CLOSED, NOW.plusSeconds(120));
        assertThat(ticket.getResolvedAt()).isEqualTo(NOW.plusSeconds(120));
        assertThatThrownBy(() -> ticket.changeStatus(TicketStatus.OPEN, NOW)).isInstanceOf(ApiException.class);
        assertThat(ticket.changePriority(TicketPriority.LOW)).isEmpty();
        assertThat(TicketStatus.WAITING.canTransitionTo(TicketStatus.OPEN)).isFalse();
        assertThat(TicketPriority.URGENT.isHigherThan(TicketPriority.HIGH)).isTrue();
    }

    @Test
    void automaticTicketsOnlyForActionableOrHighPriorityMessages() {
        assertThat(TicketAutomation.requiresTicket("REFUND_REQUEST", TicketPriority.MEDIUM)).isTrue();
        assertThat(TicketAutomation.requiresTicket("ORDER_STATUS", TicketPriority.HIGH)).isTrue();
        assertThat(TicketAutomation.requiresTicket("ORDER_STATUS", TicketPriority.MEDIUM)).isFalse();
        assertThat(TicketAutomation.requiresTicket("GREETING", TicketPriority.LOW)).isFalse();
    }

    @Test
    void messageAnalysisRoundTripsThroughTheMetadata() {
        MessageAnalysis analysis = new MessageAnalysis("REFUND_REQUEST", TicketCategory.BILLING, TicketPriority.HIGH,
                0.92, "rules", true, "NEGATIVE", 0.8);
        assertThat(MessageAnalysis.fromMetadata(Map.of("analysis", analysis.toMap()))).isEqualTo(analysis);
        assertThat(MessageAnalysis.fromMetadata(Map.of())).isNull();
        assertThat(MessageAnalysis.fromMetadata(Map.of("analysis", Map.of("intent", "X")))).isNull();
    }

    @Test
    void slugsAndLanguageDetection() {
        assertThat(Slugs.from("Acme Store S.A.S.")).isEqualTo("acme-store-s-a-s");
        assertThat(Slugs.from("Café Ñandú")).isEqualTo("cafe-nandu");
        assertThat(Slugs.from("¡¡¡")).isEqualTo("org");
        assertThat(AssistantTexts.language("¿Dónde está mi pedido?")).isEqualTo("es");
        assertThat(AssistantTexts.language("Where is my order?")).isEqualTo("en");
        assertThat(AssistantTexts.aiUnavailable("en")).startsWith("AI temporarily unavailable");
    }
}
