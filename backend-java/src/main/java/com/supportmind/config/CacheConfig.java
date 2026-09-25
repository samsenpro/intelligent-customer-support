package com.supportmind.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.supportmind.analytics.AnalyticsDtos.AnalyticsResponse;
import com.supportmind.analytics.AnalyticsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import java.time.Duration;

@Configuration
public class CacheConfig {

    /**
     * Caché de la analítica en Redis, serializada como JSON con un tipo concreto (sin información de
     * clase embebida: una entrada manipulada en Redis no puede instanciar clases arbitrarias).
     */
    @Bean
    RedisCacheManagerBuilderCustomizer analyticsCache(ObjectMapper objectMapper,
                                                      @Value("${supportmind.cache.analytics-ttl:60s}") Duration ttl) {
        var serializer = new Jackson2JsonRedisSerializer<>(objectMapper, AnalyticsResponse.class);
        return builder -> builder.withCacheConfiguration(AnalyticsService.CACHE,
                RedisCacheConfiguration.defaultCacheConfig()
                        .entryTtl(ttl)
                        .prefixCacheNameWith("cache:")
                        .disableCachingNullValues()
                        .serializeValuesWith(SerializationPair.fromSerializer(serializer)));
    }
}
