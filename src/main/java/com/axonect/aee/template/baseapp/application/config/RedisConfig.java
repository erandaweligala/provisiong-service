package com.axonect.aee.template.baseapp.application.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    /**
     * RedisConnectionFactory is auto-configured by Spring Boot based on
     * spring.data.redis.* properties in application.yaml.
     *
     * Supports any topology without code changes:
     *   - Standalone  → spring.data.redis.host / port
     *   - Sentinel    → spring.data.redis.sentinel.master / nodes
     *   - Cluster     → spring.data.redis.cluster.nodes
     *
     * Pool settings (Lettuce/Jedis), password, timeouts, etc. are all
     * picked up automatically from yaml as well.
     */
    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer serializer = new StringRedisSerializer();
        template.setKeySerializer(serializer);
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(serializer);
        template.setHashValueSerializer(serializer);

        template.afterPropertiesSet();
        return template;
    }
}