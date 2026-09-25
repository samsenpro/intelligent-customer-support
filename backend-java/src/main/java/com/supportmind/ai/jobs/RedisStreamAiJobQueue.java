package com.supportmind.ai.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Publicación de jobs de IA en Redis Streams. El consumo está en {@link RedisStreamAiJobConsumer}:
 * separarlos evita que los servicios que publican dependan de los que consumen.
 */
@Component
public class RedisStreamAiJobQueue implements AiJobQueue {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamAiJobQueue.class);

    private final StringRedisTemplate redis;
    private final QueueProperties properties;

    public RedisStreamAiJobQueue(StringRedisTemplate redis, QueueProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public void publish(AiJob job) {
        RecordId id = redis.opsForStream().add(StreamRecords.newRecord()
                .in(properties.stream())
                .ofMap(job.toMap()));
        log.debug("AI job {} published for {} (message {})", job.type(), job.entityId(), id);
    }
}
