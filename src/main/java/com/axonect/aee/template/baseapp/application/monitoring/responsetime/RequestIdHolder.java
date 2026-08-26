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

/**
 * Carries the id of the request being served on the current thread.
 *
 * <p>This exists because of <em>where</em> a response time is recorded. Spring
 * stops the {@code http.server.requests} timer inside
 * {@code ServerHttpObservationFilter}, which sits outside the handler and
 * outside every {@code HandlerInterceptor} - so by the time the duration is
 * observed, {@code RequestUuidInterceptor} has already cleared the MDC and the
 * request id is gone. {@link ResponseTimeFilter} wraps that filter and
 * parks the id here instead, which is the one place the exemplar sampler can
 * still read it at the moment the observation is taken.</p>
 *
 * <p>A {@link ThreadLocal} is correct here rather than merely convenient: the
 * sampler is called by Micrometer with nothing but the observed value, so the
 * thread is the only channel that connects the measurement back to the request
 * that produced it.</p>
 */
public final class RequestIdHolder {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private RequestIdHolder() {
    }

    /** @return the current request's id, or {@code null} off a request thread. */
    public static String get() {
        return CURRENT.get();
    }

    public static void set(String requestId) {
        if (requestId == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(requestId);
        }
    }

    /**
     * Clears the id. Always call this in a {@code finally} - container threads are
     * pooled, and a leaked id would label the next request's exemplar with the
     * previous request's identity.
     */
    public static void clear() {
        CURRENT.remove();
    }
}
