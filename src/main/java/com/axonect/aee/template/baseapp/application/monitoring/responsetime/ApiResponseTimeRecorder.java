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

import com.axonect.aee.template.baseapp.application.monitoring.ApiEndpointRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Measures how long each individual request actually took, and reports it under
 * the API that served it.
 *
 * <p>{@code http_server_requests} already times every request, but Prometheus can
 * only ever show what a scrape can carry: a count, a total, and the histogram
 * buckets requests fell into. That is enough for a percentile and a heatmap - the
 * distribution of individual response times - and it is not enough to answer
 * "which request took 4 seconds, and when". This filter closes that gap without
 * duplicating the timer:</p>
 *
 * <ul>
 *   <li>every request over {@code slow-request-threshold-ms} is logged at WARN
 *       with its exact duration, the API it hit, the status it answered and the
 *       request identifier, so the individual request can be found in the
 *       application log beside its own request and response lines;</li>
 *   <li>the same requests are counted in {@code api_slow_requests_total}, tagged
 *       {@code api}, {@code method}, {@code status} and {@code severity}, so how
 *       many there were is a chart rather than a log search;</li>
 *   <li>{@code api_request_duration_last_seconds} holds the duration of the most
 *       recent request per API, which is one real request's response time rather
 *       than an aggregate of many. It is a gauge and keeps that value until the
 *       next request replaces it, so every scrape carries it whether or not the
 *       API was called since the last one; the dashboard draws it only while it
 *       is still changing, so an API that goes quiet leaves a gap rather than a
 *       flat line at its last request.</li>
 * </ul>
 *
 * <p>The duration measured here is the time this filter is on the stack. It is a
 * few hundred microseconds wider than the timer's, which starts inside the
 * dispatcher servlet, and it includes the filters registered after this one. That
 * is deliberate - it is closer to what the caller waited for - and it is why the
 * dashboard reads its percentiles from the timer and its individual requests from
 * here, rather than mixing the two in one panel.</p>
 */
@Slf4j
public class ApiResponseTimeRecorder extends OncePerRequestFilter {

    static final String SLOW_REQUESTS_METRIC = "api.slow.requests";
    static final String LAST_DURATION_METRIC = "api.request.duration.last";

    private static final String START_ATTRIBUTE = ApiResponseTimeRecorder.class.getName() + ".start";
    private static final String UNMATCHED_API = ApiEndpointRegistry.UNMATCHED;

    private static final String TAG_API = "api";
    private static final String TAG_METHOD = "method";
    private static final String TAG_STATUS = "status";
    private static final String TAG_SEVERITY = "severity";

    private final MeterRegistry registry;
    private final ApiEndpointRegistry endpoints;
    private final ResponseTimeProperties properties;
    private final String requestIdMdcKey;

    /** Last observed duration per API, in nanoseconds, behind the gauge of the same. */
    private final Map<String, AtomicLong> lastDurationNanos = new ConcurrentHashMap<>();

    public ApiResponseTimeRecorder(MeterRegistry registry,
                                   ApiEndpointRegistry endpoints,
                                   ResponseTimeProperties properties,
                                   String requestIdMdcKey) {
        this.registry = registry;
        this.endpoints = endpoints;
        this.properties = properties;
        this.requestIdMdcKey = requestIdMdcKey;
    }

    /**
     * The request is timed across the async dispatch as well, so an endpoint that
     * returns a {@code CompletableFuture} is measured until its response is
     * written rather than until its handler returned.
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        long start = startedAt(request);
        Throwable failure = null;
        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            // An async request has not been answered yet; it is recorded when the
            // ASYNC dispatch comes back through here with the same start time.
            if (!request.isAsyncStarted()) {
                record(request, response, System.nanoTime() - start, failure);
            }
        }
    }

    private long startedAt(HttpServletRequest request) {
        Object existing = request.getAttribute(START_ATTRIBUTE);
        if (existing instanceof Long started) {
            return started;
        }
        long start = System.nanoTime();
        request.setAttribute(START_ATTRIBUTE, start);
        return start;
    }

    /**
     * Attributes one finished request to its API and reports it.
     *
     * <p>Never throws: a request that has already been answered must not fail
     * because monitoring could not describe it.</p>
     */
    private void record(HttpServletRequest request, HttpServletResponse response, long durationNanos,
                        Throwable failure) {
        try {
            String api = apiOf(request);
            if (UNMATCHED_API.equals(api) && !properties.isIncludeUncatalogued()) {
                return;
            }

            long durationMs = durationNanos / 1_000_000L;
            RequestSpeed speed = RequestSpeed.of(durationMs,
                    properties.getSlowRequestThresholdMs(), properties.getVerySlowRequestThresholdMs());
            String status = statusOf(response, failure);

            lastDuration(api).set(durationNanos);
            if (speed.isSlow()) {
                countSlow(api, request.getMethod(), status, speed);
            }
            logRequest(api, request, status, durationMs, speed);
        } catch (RuntimeException e) {
            log.warn("Could not record the response time of {} {}: {}",
                    request.getMethod(), request.getRequestURI(), e.toString());
        }
    }

    /**
     * The status the caller will see. An exception on its way out of the chain has
     * not reached the container's error dispatch yet, so the response still carries
     * whatever status was set before it was thrown - 200, usually. Reporting a
     * request that ended in an exception as a success would make the slow request
     * counter disagree with the timer beside it, so an unanswered failure is
     * recorded as the 500 it is about to become.
     */
    private String statusOf(HttpServletResponse response, Throwable failure) {
        int status = response.getStatus();
        if (failure != null && status < 400) {
            return "500";
        }
        return String.valueOf(status);
    }

    /**
     * Resolves the API from the URI template Spring matched, which is the same
     * value the {@code api} tag on {@code http_server_requests} is derived from -
     * so a request appears under the same name on every panel of the dashboard.
     *
     * <p>A request rejected in the filter chain never reached a handler and so has
     * no template. It is matched on the path the caller asked for instead, for the
     * same reason and by the same rule the {@code api} tag uses - a rejected
     * request that lands on {@code other} here would drop out of this filter's
     * metrics entirely, because {@code include-uncatalogued} is off.</p>
     */
    private String apiOf(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern != null) {
            return endpoints.tagFor(request.getMethod(), pattern.toString());
        }
        return endpoints.tagForRequestPath(request.getMethod(), request.getRequestURI(), request.getContextPath());
    }

    private AtomicLong lastDuration(String api) {
        return lastDurationNanos.computeIfAbsent(api, name -> {
            AtomicLong holder = new AtomicLong();
            Gauge.builder(LAST_DURATION_METRIC, holder, value -> value.get() / 1_000_000_000d)
                    .description("Response time of the most recent request served by this API")
                    .baseUnit("seconds")
                    .tag(TAG_API, name)
                    .register(registry);
            return holder;
        });
    }

    private void countSlow(String api, String method, String status, RequestSpeed speed) {
        Counter.builder(SLOW_REQUESTS_METRIC)
                .description("Requests that took at least monitoring.api.response-time.slow-request-threshold-ms")
                .tag(TAG_API, api)
                .tag(TAG_METHOD, method == null ? "UNKNOWN" : method)
                .tag(TAG_STATUS, status)
                .tag(TAG_SEVERITY, speed.label())
                .register(registry)
                .increment();
    }

    /**
     * One line per slow request, and - when {@code log-every-request} is on - one
     * per request of any speed. The request identifier is carried so the line can
     * be tied back to the request and response the logging interceptor already
     * wrote for the same call.
     */
    private void logRequest(String api, HttpServletRequest request, String status, long durationMs,
                            RequestSpeed speed) {
        if (speed.isSlow()) {
            log.warn("{} response time: api={} {} {} status={} duration_ms={} {}={}",
                    speed == RequestSpeed.VERY_SLOW ? "Very slow" : "Slow",
                    api, request.getMethod(), request.getRequestURI(), status, durationMs,
                    requestIdMdcKey, requestId());
        } else if (properties.isLogEveryRequest()) {
            log.info("Response time: api={} {} {} status={} duration_ms={} {}={}",
                    api, request.getMethod(), request.getRequestURI(), status, durationMs,
                    requestIdMdcKey, requestId());
        } else if (log.isDebugEnabled()) {
            log.debug("Response time: api={} {} {} status={} duration_ms={}",
                    api, request.getMethod(), request.getRequestURI(), status, durationMs);
        }
    }

    private String requestId() {
        String id = MDC.get(requestIdMdcKey);
        return id == null ? "none" : id;
    }
}
