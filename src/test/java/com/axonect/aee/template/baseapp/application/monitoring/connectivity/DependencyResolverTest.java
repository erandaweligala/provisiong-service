package com.axonect.aee.template.baseapp.application.monitoring.connectivity;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.net.ConnectException;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DependencyResolverTest {

    @Test
    void attributesJdbcFailuresToTheDatabase() {
        assertEquals(Dependency.DATABASE, DependencyResolver.resolve(
                new DataAccessResourceFailureException("Unable to acquire JDBC Connection",
                        new SQLException("ORA-12541: TNS:no listener"))));
    }

    @Test
    void attributesRedisFailuresToRedisAndNotToTheDatabase() {
        // RedisSystemException is a DataAccessException too - if the generic Spring
        // data-access packages were checked first, a Redis outage would be charted as
        // a database outage.
        assertEquals(Dependency.REDIS, DependencyResolver.resolve(
                new RedisSystemException("Redis exception", new IllegalStateException("boom"))));
        assertEquals(Dependency.REDIS, DependencyResolver.resolve(
                new RedisConnectionFailureException("Unable to connect to Redis",
                        new ConnectException("Connection refused"))));
    }

    @Test
    void attributesKafkaFailuresToKafka() {
        assertEquals(Dependency.KAFKA, DependencyResolver.resolve(
                new org.apache.kafka.common.errors.TimeoutException(
                        "Topic dc-provisioning not present in metadata after 2000 ms.")));
        assertEquals(Dependency.KAFKA, DependencyResolver.resolve(
                new org.springframework.kafka.KafkaException("Publish failed",
                        new IllegalStateException("no brokers"))));
    }

    @Test
    void ignoresFailuresThatPointAtNoDependency() {
        assertNull(DependencyResolver.resolve(new IllegalArgumentException("user_name is required")));
        assertNull(DependencyResolver.resolve(null));
    }

    @Test
    void survivesASelfReferencingCauseChain() {
        Throwable looping = new RuntimeException("business rule failed") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertNull(DependencyResolver.resolve(looping));
    }
}
