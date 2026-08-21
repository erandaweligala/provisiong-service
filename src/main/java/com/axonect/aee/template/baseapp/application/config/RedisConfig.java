package com.axonect.aee.template.baseapp.application.config;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    // Matches: spring.data.redis.sentinel.master
    @Value("${spring.data.redis.sentinel.master}")
    private String sentinelMaster;

    // Matches: spring.data.redis.sentinel.nodes (e.g. "host:26379")
    @Value("${spring.data.redis.sentinel.nodes[0]}")
    private String sentinelNode;

    // Matches: spring.data.redis.sentinel.password
    @Value("${spring.data.redis.sentinel.password}")
    private String sentinelPassword;

    // Matches: spring.data.redis.password (the Redis data node password)
    @Value("${spring.data.redis.password}")
    private String redisPassword;

    /**
     * Existing bean — no changes needed.
     * Spring Boot auto-configures the RedisConnectionFactory from your
     * application.yaml sentinel block, so this bean is fine as-is.
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

    /**
     * Native Lettuce client required by Bucket4j ProxyManager.
     * Connects via Sentinel so it discovers the Redis master automatically —
     * consistent with how Spring Data Redis connects in the rest of the app.
     */
    @Bean
    public RedisClient redisClient() {
        // Parse "host:port" from sentinel node string
        String[] parts = sentinelNode.split(":");
        String sentinelHost = parts[0];
        int sentinelPort = Integer.parseInt(parts[1]);

        RedisURI sentinelUri = RedisURI.builder()
                .withSentinel(sentinelHost, sentinelPort, sentinelPassword)
                .withSentinelMasterId(sentinelMaster)
                .withPassword(redisPassword.toCharArray())
                .build();

        return RedisClient.create(sentinelUri);
    }
}
//package com.axonect.aee.template.baseapp.application.config;
//
//import io.lettuce.core.RedisClient;
//import io.lettuce.core.RedisURI;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//import org.springframework.data.redis.connection.RedisConnectionFactory;
//import org.springframework.data.redis.core.RedisTemplate;
//import org.springframework.data.redis.serializer.StringRedisSerializer;
//
//@Configuration
//public class RedisConfig {
//
//    // Add these — reads same values already in your application.yaml
//    @Value("${spring.data.redis.host}")
//    private String redisHost;
//
//    @Value("${spring.data.redis.port}")
//    private int redisPort;
//
//    /**
//     * Existing bean — no changes
//     */
//    @Bean
//    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
//        RedisTemplate<String, String> template = new RedisTemplate<>();
//        template.setConnectionFactory(connectionFactory);
//
//        StringRedisSerializer serializer = new StringRedisSerializer();
//        template.setKeySerializer(serializer);
//        template.setValueSerializer(serializer);
//        template.setHashKeySerializer(serializer);
//        template.setHashValueSerializer(serializer);
//
//        template.afterPropertiesSet();
//        return template;
//    }
//
//    /**
//     * New bean — native Lettuce client required by Bucket4j ProxyManager.
//     * Uses the same host/port as your existing Spring Data Redis config,
//     * so no duplicate connection settings anywhere.
//     */
//    @Bean
//    public RedisClient redisClient() {
//        return RedisClient.create(
//                RedisURI.builder()
//                        .withHost(redisHost)
//                        .withPort(redisPort)
//                        .build()
//        );
//    }
//}