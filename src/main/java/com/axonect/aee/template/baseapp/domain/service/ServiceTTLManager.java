package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.domain.entities.dto.BucketInstance;
import com.axonect.aee.template.baseapp.domain.entities.dto.ServiceInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class ServiceTTLManager {

    private final RedisTemplate<String, String> redisTemplate;

    private static final String SERVICE_KEY_PREFIX = "service::";

    /**
     * Publishes one Redis TTL key per BucketInstance belonging to the service.
     *
     * Key format:   service::{serviceInstanceId}::{bucketInstanceId}
     * Value format: {username}:{planId}
     *
     * Recurring     → TTL end = nextCycleStartDate
     * Non-recurring → TTL end = expiryDate
     */
    public void publishServiceTTL(ServiceInstance service, List<BucketInstance> bucketInstances) {
        if (bucketInstances == null || bucketInstances.isEmpty()) {
            log.warn("No bucket instances found for Service ID: {}. Skipping Redis publish.", service.getId());
            return;
        }

        LocalDateTime ttlEndDate = resolveTTLEndDate(service);

        if (ttlEndDate == null) {
            log.warn("Could not resolve TTL end date for Service ID: {}. Skipping Redis publish.", service.getId());
            return;
        }

        long ttlSeconds = calculateTTL(ttlEndDate);

        if (ttlSeconds <= 0) {
            log.warn("TTL is zero or negative for Service ID: {} (TTL end: {}). Skipping Redis publish.",
                    service.getId(), ttlEndDate);
            return;
        }

        String value = service.getUsername() + ":" + service.getPlanId();

        for (BucketInstance bucket : bucketInstances) {
            try {
                String key = buildKey(service.getId(), bucket.getId());
                redisTemplate.opsForValue().set(key, value, ttlSeconds, TimeUnit.SECONDS);
                log.info("Published Redis TTL key '{}' with value '{}' and TTL {} seconds (end date: {})",
                        key, value, ttlSeconds, ttlEndDate);
            } catch (Exception e) {
                log.error("Failed to publish TTL to Redis for Service ID: {}, Bucket ID: {}",
                        service.getId(), bucket.getId(), e);
            }
        }
    }

    /**
     * Removes all Redis TTL keys for a given service by scanning the pattern:
     * service::{serviceId}::*
     *
     * Call this on service delete or inactivation.
     */
    public void removeServiceTTL(Long serviceId) {
        try {
            String pattern = SERVICE_KEY_PREFIX + serviceId + "::*";
            Set<String> keys = redisTemplate.keys(pattern);

            if (keys == null || keys.isEmpty()) {
                log.info("No Redis TTL keys found for pattern '{}'. Nothing to remove.", pattern);
                return;
            }

            Long deletedCount = redisTemplate.delete(keys);
            log.info("Removed {} Redis TTL key(s) matching pattern '{}'.", deletedCount, pattern);
        } catch (Exception e) {
            log.error("Failed to remove TTL keys from Redis for Service ID: {}", serviceId, e);
        }
    }

    /**
     * Removes a single bucket instance TTL key.
     *
     * Useful when a specific bucket is removed without affecting the whole service.
     */
    public void removeBucketTTL(Long serviceId, Long bucketInstanceId) {
        try {
            String key = buildKey(serviceId, bucketInstanceId);
            Boolean deleted = redisTemplate.delete(key);
            log.info("Removed Redis TTL key '{}': {}", key,
                    Boolean.TRUE.equals(deleted) ? "deleted" : "not found");
        } catch (Exception e) {
            log.error("Failed to remove TTL key from Redis for Service ID: {}, Bucket ID: {}",
                    serviceId, bucketInstanceId, e);
        }
    }

    // ─── Private Helpers ────────────────────────────────────────────────────────

    /**
     * Builds the Redis key: service::{serviceInstanceId}::{bucketInstanceId}
     */
    private String buildKey(Long serviceId, Long bucketInstanceId) {
        return SERVICE_KEY_PREFIX + serviceId + "::" + bucketInstanceId;
    }

    /**
     * Resolves TTL end date from the service based on recurringFlag.
     *
     * true  → nextCycleStartDate
     * false → expiryDate
     */
    private LocalDateTime resolveTTLEndDate(ServiceInstance service) {
        if (Boolean.TRUE.equals(service.getRecurringFlag())) {
            if (service.getNextCycleStartDate() == null) {
                log.warn("Service ID: {} is recurring but nextCycleStartDate is null — skipping.", service.getId());
                return null;
            }
            return service.getNextCycleStartDate();
        } else {
            if (service.getExpiryDate() == null) {
                log.warn("Service ID: {} is non-recurring but expiryDate is null — skipping.", service.getId());
                return null;
            }
            return service.getExpiryDate();
        }
    }

    /**
     * Calculates TTL in seconds: ttlEndDate - now
     */
    private long calculateTTL(LocalDateTime ttlEndDate) {
        long now    = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
        long expiry = ttlEndDate.toEpochSecond(ZoneOffset.UTC);
        return expiry - now;
    }
}