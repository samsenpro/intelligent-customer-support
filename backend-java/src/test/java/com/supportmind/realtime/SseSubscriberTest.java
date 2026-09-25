package com.supportmind.realtime;

import com.supportmind.auth.Role;
import com.supportmind.realtime.SseHub.Subscriber;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Los eventos en tiempo real respetan las mismas reglas de visibilidad que la API REST. */
class SseSubscriberTest {

    private final UUID org = UUID.randomUUID();
    private final UUID agent = UUID.randomUUID();
    private final UUID customer = UUID.randomUUID();

    private RealtimeEvent event(UUID organization, UUID customerId, UUID assignedAgent, boolean inQueue) {
        return new RealtimeEvent("message.created", organization, UUID.randomUUID(), customerId, assignedAgent,
                inQueue, null);
    }

    @Test
    void supervisorsSeeEverythingInTheirOrganizationOnly() {
        Subscriber supervisor = new Subscriber(UUID.randomUUID(), org, Role.SUPERVISOR, UUID.randomUUID(), null);
        assertThat(supervisor.sees(event(org, customer, null, false))).isTrue();
        assertThat(supervisor.sees(event(UUID.randomUUID(), customer, null, false))).isFalse();
    }

    @Test
    void agentsSeeAssignedConversationsAndTheQueue() {
        Subscriber subscriber = new Subscriber(UUID.randomUUID(), org, Role.AGENT, agent, null);
        assertThat(subscriber.sees(event(org, customer, agent, false))).isTrue();
        assertThat(subscriber.sees(event(org, customer, null, true))).isTrue();
        assertThat(subscriber.sees(event(org, customer, UUID.randomUUID(), false))).isFalse();
        assertThat(subscriber.sees(event(org, customer, null, false))).isFalse();
    }

    @Test
    void customersSeeOnlyTheirOwnConversations() {
        Subscriber subscriber = new Subscriber(UUID.randomUUID(), org, Role.CUSTOMER, null, customer);
        assertThat(subscriber.sees(event(org, customer, agent, false))).isTrue();
        assertThat(subscriber.sees(event(org, UUID.randomUUID(), null, true))).isFalse();
    }
}
