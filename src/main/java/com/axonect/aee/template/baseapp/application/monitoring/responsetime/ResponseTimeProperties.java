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
package com.axonect.aee.template.baseapp.application.monitoring.responsetime;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code monitoring.api.response-time} block - what it takes to see the
 * duration of an individual request rather than a percentile over many.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "monitoring.api.response-time")
public class ResponseTimeProperties {

    /**
     * Master switch for per-request response time monitoring. When false the
     * response time histogram is still published and the dashboard's percentile
     * panels still work; only the individual request points disappear.
     */
    private boolean enabled = true;

    /**
     * Shortest gap between two exemplars on the same bucket, in milliseconds.
     *
     * <p>Zero - the default - means every request replaces the exemplar for its
     * bucket, so each scrape reports the most recent real request in each latency
     * band. Prometheus's own sampler uses about 7000 here because it is sampling
     * traces to keep; raise this only if the exposition proves measurably costly,
     * and accept that requests go unnamed for that long.</p>
     */
    private long minRetentionMs = 0L;

    /** Whether a request slower than the threshold below is logged. */
    private boolean logSlowRequests = true;

    /**
     * Requests at or above this many milliseconds get a WARN line naming the
     * request id and the exact duration.
     *
     * <p>An exemplar is bounded by what one scrape can carry; this is not. It is
     * the fallback for the case the dashboard cannot answer - a slow request that
     * shared a bucket with a later one and so was never named.</p>
     */
    private long slowRequestThresholdMs = 1_000L;
}
