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

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Makes a single request's response time attributable.
 *
 * <p>It does two things, and does them from the same place for the same reason.
 * It publishes the request id on {@link RequestIdHolder} so
 * {@link RequestExemplarSampler} can label the exemplar with the request that
 * produced the duration, and it times the request itself so one that is slow
 * enough to matter leaves a log line with its exact duration.</p>
 *
 * <p>The place matters. Spring starts and stops the
 * {@code http.server.requests} timer inside {@code ServerHttpObservationFilter},
 * registered at {@code HIGHEST_PRECEDENCE + 1}. Registered at
 * {@code HIGHEST_PRECEDENCE}, this filter wraps that one, so the id is still on
 * the thread when the duration is recorded, and the duration logged here spans
 * very nearly the same work the metric measures. A {@code HandlerInterceptor}
 * could do neither: it runs inside the handler, by which point the observation
 * has not been taken yet and the MDC is cleared before it is.</p>
 *
 * <p>The id is the same one {@code RequestUuidInterceptor} puts in the logging
 * MDC - read from the same request header, generated the same way when the
 * caller sent none - so an exemplar in Grafana, a log line and the
 * {@code ACTION_LOG} row all name the same request.</p>
 */
@Slf4j
public class ResponseTimeFilter extends OncePerRequestFilter {

    /**
     * Where the resolved id is remembered for the life of the request. An async
     * request runs this filter twice - once on the initial thread, once on the
     * async dispatch - and both passes must report the same id.
     */
    static final String REQUEST_ID_ATTRIBUTE = ResponseTimeFilter.class.getName() + ".requestId";

    /**
     * Exemplar labels are written verbatim into the OpenMetrics scrape and this id
     * can come from a caller-supplied header, so the value is capped. 48 is
     * comfortably more than the 36 a UUID needs while leaving the whole label set
     * far inside the 128 character ceiling OpenMetrics puts on an exemplar.
     */
    private static final int MAX_ID_LENGTH = 48;

    private final String headerName;
    private final boolean logSlowRequests;
    private final long slowRequestThresholdMs;

    public ResponseTimeFilter(String headerName, boolean logSlowRequests, long slowRequestThresholdMs) {
        this.headerName = headerName;
        this.logSlowRequests = logSlowRequests;
        this.slowRequestThresholdMs = slowRequestThresholdMs;
    }

    /**
     * Runs on the async dispatch too. The timer is stopped on whichever thread
     * completes the request, so on an async endpoint the id has to be present on
     * the dispatch thread or the exemplar is lost.
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        RequestIdHolder.set(requestId);
        long startNanos = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            RequestIdHolder.clear();
            logIfSlow(request, response, requestId, System.nanoTime() - startNanos);
        }
    }

    /**
     * Logs the exact duration of a request that crossed the threshold.
     *
     * <p>Deliberately not a metric. A counter of slow requests says how many there
     * were, which the histogram already says; what is missing at that point is
     * <em>which</em> request, and that is a log line's job.</p>
     */
    private void logIfSlow(HttpServletRequest request,
                           HttpServletResponse response,
                           String requestId,
                           long elapsedNanos) {
        if (!logSlowRequests) {
            return;
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        if (elapsedMs < slowRequestThresholdMs) {
            return;
        }
        log.warn("Slow request: requestId={} method={} uri={} status={} responseTimeMs={} thresholdMs={}",
                requestId, request.getMethod(), request.getRequestURI(),
                response.getStatus(), elapsedMs, slowRequestThresholdMs);
    }

    private String resolveRequestId(HttpServletRequest request) {
        Object remembered = request.getAttribute(REQUEST_ID_ATTRIBUTE);
        if (remembered instanceof String rememberedId) {
            return rememberedId;
        }
        String supplied = headerName == null ? null : request.getHeader(headerName);
        String requestId = sanitize(supplied);
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
        }
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        return requestId;
    }

    /**
     * Reduces a caller-supplied header to something safe to print inside a metrics
     * exposition.
     *
     * <p>Anything outside {@code [A-Za-z0-9._:-]} is dropped rather than escaped:
     * a quote or a newline reaching the scrape output would break the parse of
     * every line after it, and no legitimate correlation id needs those
     * characters. A header that is entirely unusable yields {@code null} and the
     * caller falls back to a generated UUID.</p>
     */
    static String sanitize(String candidate) {
        if (candidate == null) {
            return null;
        }
        StringBuilder cleaned = new StringBuilder(Math.min(candidate.length(), MAX_ID_LENGTH));
        for (int i = 0; i < candidate.length() && cleaned.length() < MAX_ID_LENGTH; i++) {
            char character = candidate.charAt(i);
            boolean allowed = (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '.' || character == '_' || character == ':' || character == '-';
            if (allowed) {
                cleaned.append(character);
            }
        }
        return cleaned.isEmpty() ? null : cleaned.toString();
    }
}
