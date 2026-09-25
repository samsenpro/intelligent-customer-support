package com.supportmind.realtime;

import com.supportmind.auth.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Conexiones SSE abiertas en esta instancia y reparto de los eventos que llegan de Redis.
 * <p>
 * Cada evento se filtra con las mismas reglas de visibilidad que la API REST: un agente solo recibe
 * las conversaciones asignadas a él o en la cola, y un cliente solo las suyas.
 */
@Component
public class SseHub {

    private static final Logger log = LoggerFactory.getLogger(SseHub.class);
    /** Conexiones simultáneas por usuario (varias pestañas); la más antigua se cierra al superarlo. */
    private static final int MAX_SUBSCRIPTIONS_PER_USER = 5;

    private final Set<Subscription> subscriptions = ConcurrentHashMap.newKeySet();
    private final RealtimeProperties properties;
    private final AtomicLong sequence = new AtomicLong();

    public SseHub(RealtimeProperties properties) {
        this.properties = properties;
    }

    /**
     * @param conversationId conversación concreta, o {@code null} para el inbox (todas las visibles)
     */
    public SseEmitter subscribe(Subscriber subscriber, UUID conversationId) {
        evictExcess(subscriber.userId());
        SseEmitter emitter = new SseEmitter(properties.sseTimeout().toMillis());
        Subscription subscription = new Subscription(subscriber, conversationId, emitter, sequence.incrementAndGet());
        subscriptions.add(subscription);
        emitter.onCompletion(() -> subscriptions.remove(subscription));
        emitter.onTimeout(() -> {
            subscriptions.remove(subscription);
            emitter.complete();
        });
        emitter.onError(error -> subscriptions.remove(subscription));
        send(subscription, SseEmitter.event().name("ready").data("{}", MediaType.APPLICATION_JSON));
        return emitter;
    }

    public void dispatch(RealtimeEvent event, String json) {
        for (Subscription subscription : subscriptions) {
            if (subscription.accepts(event)) {
                send(subscription, SseEmitter.event().name(event.type()).data(json, MediaType.APPLICATION_JSON));
            }
        }
    }

    /** Mantiene vivas las conexiones a través de proxies y detecta clientes desconectados. */
    @Scheduled(fixedDelayString = "${supportmind.realtime.heartbeat-interval:20s}")
    public void heartbeat() {
        for (Subscription subscription : subscriptions) {
            send(subscription, SseEmitter.event().comment("ping"));
        }
    }

    public int size() {
        return subscriptions.size();
    }

    private void send(Subscription subscription, SseEmitter.SseEventBuilder event) {
        try {
            subscription.emitter().send(event);
        } catch (IOException | IllegalStateException ex) {
            // Cliente desconectado o conexión ya cerrada
            subscriptions.remove(subscription);
            log.debug("SSE subscription closed: {}", ex.getMessage());
        }
    }

    private void evictExcess(UUID userId) {
        var own = subscriptions.stream().filter(s -> s.subscriber().userId().equals(userId))
                .sorted(Comparator.comparingLong(Subscription::order)).toList();
        for (int i = 0; i <= own.size() - MAX_SUBSCRIPTIONS_PER_USER; i++) {
            subscriptions.remove(own.get(i));
            own.get(i).emitter().complete();
        }
    }

    /** Quién escucha: datos del usuario autenticado en el momento de suscribirse. */
    public record Subscriber(UUID userId, UUID organizationId, Role role, UUID agentId, UUID customerId) {

        boolean sees(RealtimeEvent event) {
            if (!organizationId.equals(event.organizationId())) {
                return false;
            }
            if (role.seesAllConversations()) {
                return true;
            }
            if (role == Role.AGENT) {
                return event.inQueue() || (agentId != null && agentId.equals(event.assignedAgentId()));
            }
            return customerId != null && customerId.equals(event.customerId());
        }
    }

    private record Subscription(Subscriber subscriber, UUID conversationId, SseEmitter emitter, long order) {

        boolean accepts(RealtimeEvent event) {
            if (conversationId != null && !conversationId.equals(event.conversationId())) {
                return false;
            }
            // En el inbox no se reenvía el texto en streaming de la IA: solo interesa en la conversación abierta
            if (conversationId == null && event.type().equals("ai.delta")) {
                return false;
            }
            return subscriber.sees(event);
        }
    }
}
