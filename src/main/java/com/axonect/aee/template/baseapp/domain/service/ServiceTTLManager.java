package com.axonect.aee.template.baseapp.domain.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class ServiceTTLManager {

    private final RedisTemplate<String, String> redisTemplate;

    private static final String SERVICE_KEY_PREFIX = "service::";

    /**
     * Publishes a service key to Redis with TTL calculated from expiry date.
     * Key format: service:ttl:{serviceId}
     * Value: {username}:{planId}
     */
    public void publishServiceTTL(Long serviceId, String username, String planId, LocalDateTime expiryDate) {
        try {
            long ttlSeconds = calculateTTL(expiryDate);

            if (ttlSeconds <= 0) {
                log.warn("TTL is zero or negative for Service ID: {} (Expiry: {}). Skipping Redis publish.",
                        serviceId, expiryDate);
                return;
            }

            String key   = SERVICE_KEY_PREFIX + serviceId;  // → service::9876543210
            String value = username + ":" + planId;

            redisTemplate.opsForValue().set(key, value, ttlSeconds, TimeUnit.SECONDS);

            log.info("Published Redis TTL key '{}' with value '{}' and TTL {} seconds",
                    key, value, ttlSeconds);

        } catch (Exception e) {
            log.error("Failed to publish TTL to Redis for Service ID: {}", serviceId, e);
        }
    }

    /**
     * Removes a service TTL key from Redis (used on delete/inactivation).
     */
    public void removeServiceTTL(Long serviceId) {
        try {
            String key = SERVICE_KEY_PREFIX + serviceId;
            Boolean deleted = redisTemplate.delete(key);
            log.info("Removed Redis TTL key '{}': {}", key, Boolean.TRUE.equals(deleted) ? "deleted" : "not found");
        } catch (Exception e) {
            log.error("Failed to remove TTL key from Redis for Service ID: {}", serviceId, e);
        }
    }

    /**
     * Calculates TTL in seconds: expiryDate - now
     */
    private long calculateTTL(LocalDateTime expiryDate) {
        if (expiryDate == null) {
            log.warn("Expiry date is null — cannot calculate TTL");
            return 0L;
        }
        long now = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
        long expiry = expiryDate.toEpochSecond(ZoneOffset.UTC);
        return expiry - now;
    }
}