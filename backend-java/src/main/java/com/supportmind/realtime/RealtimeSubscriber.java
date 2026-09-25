package com.supportmind.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Recibe los eventos de Redis Pub/Sub y los entrega al hub SSE de esta instancia. */
@Component
public class RealtimeSubscriber implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(RealtimeSubscriber.class);

    private final SseHub hub;
    private final ObjectMapper objectMapper;

    public RealtimeSubscriber(SseHub hub, ObjectMapper objectMapper) {
        this.hub = hub;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String json = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            RealtimeEvent event = objectMapper.readValue(json, RealtimeEvent.class);
            hub.dispatch(event, json);
        } catch (IOException ex) {
            log.warn("Ignoring malformed realtime event: {}", ex.getMessage());
        }
    }
}
