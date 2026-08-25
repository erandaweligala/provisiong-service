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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Counts requests that exceed the response time budget configured for their
 * endpoint.
 *
 * <p>The latency histogram alone cannot answer "what share of calls met their
 * budget?" on a table covering every endpoint, because each endpoint has its own
 * budget and PromQL cannot select a different histogram bucket per series. This
 * interceptor turns the question into a plain ratio instead:</p>
 *
 * <pre>
 * 1 - rate(api_requests_over_threshold_total[5m])
 *   / rate(http_server_requests_seconds_count[5m])
 * </pre>
 */
@Slf4j
public class ApiLatencyThresholdInterceptor implements HandlerInterceptor {

    static final String COUNTER_NAME = "api.requests.over.threshold";

    private static final String START_NANOS = ApiLatencyThresholdInterceptor.class.getName() + ".startNanos";

    private final ApiEndpointRegistry registry;
    private final MeterRegistry meterRegistry;

    public ApiLatencyThresholdInterceptor(ApiEndpointRegistry registry, MeterRegistry meterRegistry) {
        this.registry = registry;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_NANOS, System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        Object startNanos = request.getAttribute(START_NANOS);
        if (!(startNanos instanceof Long start)) {
            return;
        }

        String templatedUri = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (templatedUri == null) {
            return;
        }

        registry.find(request.getMethod(), templatedUri).ifPresent(endpoint -> {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            long thresholdMs = registry.thresholdMsFor(endpoint);
            if (elapsedMs <= thresholdMs) {
                return;
            }
            Counter.builder(COUNTER_NAME)
                    .description("Requests that took longer than the endpoint's configured response time threshold")
                    .tag(ApiNameObservationConvention.API_TAG, endpoint.getName())
                    .tag("method", request.getMethod().toUpperCase(Locale.ROOT))
                    .register(meterRegistry)
                    .increment();
            log.warn("Endpoint '{}' ({} {}) responded in {}ms, over its {}ms threshold",
                    endpoint.getName(), request.getMethod(), templatedUri, elapsedMs, thresholdMs);
        });
    }
}
