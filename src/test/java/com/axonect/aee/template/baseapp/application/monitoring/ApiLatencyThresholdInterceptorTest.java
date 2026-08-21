package com.axonect.aee.template.baseapp.application.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiLatencyThresholdInterceptorTest {

    private static final long THRESHOLD_MS = 50L;

    private SimpleMeterRegistry meterRegistry;
    private ApiLatencyThresholdInterceptor interceptor;

    @BeforeEach
    void setUp() {
        MonitoredEndpoint getUser = new MonitoredEndpoint();
        getUser.setName("get_user");
        getUser.setMethod("GET");
        getUser.setUris(List.of("/api/user/{user_name}"));
        getUser.setThresholdMs(THRESHOLD_MS);

        ApiEndpointRegistry registry = new ApiEndpointRegistry(List.of(getUser), 2000L);
        meterRegistry = new SimpleMeterRegistry();
        interceptor = new ApiLatencyThresholdInterceptor(registry, meterRegistry);
    }

    private MockHttpServletRequest request(String method, String pathPattern) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/user/abc");
        if (pathPattern != null) {
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pathPattern);
        }
        return request;
    }

    private void completeAfter(MockHttpServletRequest request, long elapsedMs) throws InterruptedException {
        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
        if (elapsedMs > 0) {
            TimeUnit.MILLISECONDS.sleep(elapsedMs);
        }
        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);
    }

    private Counter breachCounter() {
        return meterRegistry.find(ApiLatencyThresholdInterceptor.COUNTER_NAME).tag("api", "get_user").counter();
    }

    @Test
    void countsARequestThatExceedsItsThreshold() throws InterruptedException {
        completeAfter(request("GET", "/api/user/{user_name}"), THRESHOLD_MS + 60);

        Counter counter = breachCounter();
        assertTrue(counter != null, "expected a breach counter to be registered");
        assertEquals(1.0, counter.count());
        assertEquals("GET", counter.getId().getTag("method"));
    }

    @Test
    void ignoresARequestInsideItsThreshold() throws InterruptedException {
        completeAfter(request("GET", "/api/user/{user_name}"), 0);

        // No breach means no meter at all, so the series only ever appears for
        // endpoints that have actually been slow.
        assertNull(breachCounter());
    }

    @Test
    void ignoresUncataloguedEndpoints() throws InterruptedException {
        completeAfter(request("GET", "/api/bng/{bng_id}"), THRESHOLD_MS + 60);

        assertTrue(meterRegistry.find(ApiLatencyThresholdInterceptor.COUNTER_NAME).counters().isEmpty());
    }

    @Test
    void ignoresRequestsThatNeverReachedAHandler() {
        MockHttpServletRequest request = request("GET", null);
        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);

        assertTrue(meterRegistry.find(ApiLatencyThresholdInterceptor.COUNTER_NAME).counters().isEmpty());
    }

    @Test
    void ignoresACompletionWithoutAMatchingPreHandle() {
        // Another interceptor can reject the request before this one runs; the
        // missing start time must not blow up the response.
        interceptor.afterCompletion(request("GET", "/api/user/{user_name}"),
                new MockHttpServletResponse(), new Object(), null);

        assertTrue(meterRegistry.find(ApiLatencyThresholdInterceptor.COUNTER_NAME).counters().isEmpty());
    }
}
