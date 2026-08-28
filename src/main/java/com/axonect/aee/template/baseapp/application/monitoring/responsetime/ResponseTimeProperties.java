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
 * Binds the {@code monitoring.api.response-time} block - what counts as a slow
 * request, and how loudly one is reported.
 *
 * <p>The response time <em>distribution</em> needs no configuration: it comes off
 * the histogram buckets {@code management.metrics.distribution.slo} already
 * publishes. These settings only govern the per-request half of the feature -
 * the log line that names an individual slow request, and the counter that
 * charts how many there were.</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "monitoring.api.response-time")
public class ResponseTimeProperties {

    /**
     * Master switch for per-request response time recording. When false no
     * {@code api_slow_requests_total} or {@code api_request_duration_last_seconds}
     * series are published and no request is logged for its duration; the
     * {@code http_server_requests} timer and its histogram are unaffected, so the
     * response time dashboard keeps working on percentiles alone.
     */
    private boolean enabled = true;

    /**
     * At or above this many milliseconds a request is counted as slow and logged at
     * WARN with its exact duration.
     *
     * <p>Matches the amber threshold on the dashboard's response time panels. It is
     * a reporting threshold and not a latency budget: nothing alerts on it, and no
     * request is treated differently for crossing it.</p>
     */
    private long slowRequestThresholdMs = 1_000;

    /**
     * At or above this many milliseconds a request is counted as very slow -
     * the same WARN line, tagged {@code severity="very_slow"} so the two can be
     * charted apart. Below {@link #slowRequestThresholdMs} it has no effect,
     * because a request has to be slow before it can be very slow.
     */
    private long verySlowRequestThresholdMs = 3_000;

    /**
     * Whether every request is logged with its duration, not just the slow ones.
     *
     * <p>This is the only place an individual fast request's response time is
     * visible at all - Prometheus can only ever show the distribution they fall
     * into. It is off by default because it is a log line per request; turn it on
     * in an environment where that is affordable and the duration of one named
     * request is worth more than the log volume.</p>
     */
    private boolean logEveryRequest = false;

    /**
     * Whether traffic that is not in the endpoint catalog - everything tagged
     * {@code api="other"}, including the actuator endpoints Prometheus itself
     * scrapes - is recorded too.
     *
     * <p>Off by default: this feature reports response time per API, and a slow
     * request that belongs to no API has no row to appear in.</p>
     */
    private boolean includeUncatalogued = false;
}
