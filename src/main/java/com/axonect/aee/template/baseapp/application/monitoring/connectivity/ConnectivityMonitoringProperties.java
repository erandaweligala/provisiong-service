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

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code connectivity} block that drives dependency connectivity
 * monitoring for the Oracle database, Redis and Kafka.
 *
 * <p>The prefix and the defaults are the ones the DB write service uses, so the same
 * settings mean the same thing across the AAA stack. The one difference is
 * {@code probe-interval-ms}: Spring's scheduler takes a plain millisecond value where
 * Quarkus takes {@code 15s}.</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "connectivity")
public class ConnectivityMonitoringProperties {

    /**
     * Value of the {@code service} tag on every {@code dependency_*} metric. Every AAA
     * service exports the same metric names, so this is what keeps their series - and
     * therefore their dashboards and alerts - apart in a shared Prometheus.
     */
    private String serviceName = "airtel-aaa-user-provisioning-service";

    /**
     * Master switch for the active probes. When false no probe runs; failures seen by
     * live traffic are still classified, counted and able to mark a dependency down.
     */
    private boolean enabled = true;

    /**
     * Number of consecutive connectivity failures (from live traffic or probes) before
     * a dependency is marked DOWN. Guards against single-blip flapping.
     */
    private int failureThreshold = 3;

    /**
     * Budget for each health probe, in milliseconds. A probe that has not answered
     * within this counts as a connectivity failure - so it must stay well under the
     * probe interval, and below the pool timeouts it is probing through.
     */
    private long probeTimeoutMs = 2000;

    /**
     * How often every enabled dependency is probed, in milliseconds. Each probe is one
     * round trip per pod, so 15s is cheap; below 5s the value drops off.
     */
    private long probeIntervalMs = 15000;

    /** Whether the Oracle probe runs. */
    private boolean probeDatabase = true;

    /** Whether the Redis probe ({@code PING}) runs. */
    private boolean probeRedis = true;

    /** Whether the Kafka probe (AdminClient {@code describeCluster}) runs. */
    private boolean probeKafka = true;

    /**
     * The query the database probe issues. The cheapest round trip that proves the
     * Oracle session is alive; override it if this service is ever pointed at a
     * database without {@code DUAL}.
     */
    private String databaseProbeQuery = "SELECT 1 FROM DUAL";
}
