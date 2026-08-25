/**
 * Copyrights 2023 Axiata Digital Labs Pvt Ltd.
 * All Rights Reserved.
 * <p>
 * These material are unpublished, proprietary, confidential source
 * code of Axiata Digital Labs Pvt Ltd (ADL) and constitute a TRADE
 * SECRET of ADL.
 * <p>
 * ADL retains all title to and intellectual property rights in these
 * materials.
 */
package com.axonect.aee.template.baseapp.application.monitoring.connectivity;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Redis probe: {@code PING} through the same connection factory the application uses,
 * so a Sentinel failover or an exhausted Lettuce pool shows up here too.
 */
@RequiredArgsConstructor
public class RedisConnectivityProbe implements DependencyProbe {

    private final RedisConnectionFactory connectionFactory;

    @Override
    public Dependency dependency() {
        return Dependency.REDIS;
    }

    @Override
    public void probe() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.ping();
        }
    }
}
