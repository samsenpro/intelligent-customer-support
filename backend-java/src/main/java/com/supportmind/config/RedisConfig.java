package com.supportmind.config;

import com.supportmind.realtime.RealtimeProperties;
import com.supportmind.realtime.RealtimeSubscriber;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class RedisConfig {

    /** Suscripción de esta instancia al canal de eventos en tiempo real (Redis Pub/Sub). */
    @Bean
    RedisMessageListenerContainer realtimeListenerContainer(RedisConnectionFactory connectionFactory,
                                                            RealtimeSubscriber subscriber,
                                                            RealtimeProperties properties) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(properties.channel()));
        return container;
    }
}
