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
package com.axonect.aee.template.baseapp.application.monitoring;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Binds the {@code monitoring.api.*} block that drives REST endpoint
 * availability monitoring.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "monitoring.api")
public class ApiMonitoringProperties {

    /**
     * Master switch. When false no {@code api} tag is added to
     * {@code http_server_requests} and the synthetic probe never runs.
     */
    private boolean enabled = true;

    /**
     * Value published as the {@code microservice} common tag on every meter, so a
     * shared Prometheus/Grafana instance can separate this service from the rest
     * of the AAA stack.
     */
    private String microservice = "airtel-aaa-user-provisioning-service";

    /**
     * Response time budget applied to endpoints that do not declare their own.
     */
    private long defaultThresholdMs = 2000L;

    /**
     * Extra latency histogram buckets (in milliseconds) published for every
     * monitored endpoint. The per-endpoint thresholds are added automatically;
     * these give the dashboard enough resolution to draw a useful heat map.
     */
    private List<Long> latencyBucketsMs = new ArrayList<>(
            List.of(50L, 100L, 250L, 500L, 750L, 1000L, 1500L, 2000L, 3000L, 5000L, 10000L));

    /**
     * Publishes Micrometer's full percentile histogram (roughly 66 buckets per
     * series) instead of only {@link #latencyBucketsMs}. Gives smoother
     * {@code histogram_quantile} results at a large cost in series count, so it
     * is off by default.
     */
    private boolean percentilesHistogram = false;

    /**
     * Synthetic probe settings. See {@link EndpointAvailabilityProbe}.
     */
    private Probe probe = new Probe();

    /**
     * The catalog of endpoints shown on the availability dashboard.
     */
    private List<MonitoredEndpoint> endpoints = new ArrayList<>();

    /**
     * Settings for the active availability probe.
     *
     * <p>The probe is disabled by default on purpose: it issues real HTTP calls,
     * so it must only ever be pointed at side effect free reads.</p>
     */
    @Getter
    @Setter
    public static class Probe {

        /**
         * Whether the scheduled probe runs at all.
         */
        private boolean enabled = false;

        /**
         * Base URL the probe calls. Defaults to the pod's own listener so a probe
         * measures this instance rather than whatever the load balancer picks.
         */
        private String baseUrl = "http://localhost:8089";

        /**
         * How often each probe-enabled endpoint is called.
         */
        private Duration interval = Duration.ofSeconds(60);

        /**
         * Per-call timeout. A probe that exceeds it is recorded as unavailable.
         */
        private Duration timeout = Duration.ofSeconds(5);

        /**
         * Optional bearer token for endpoints behind authentication.
         */
        private String authorization;
    }
}
