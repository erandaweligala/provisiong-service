package com.axonect.aee.template.baseapp.application.monitoring.responsetime;

import com.axonect.aee.template.baseapp.application.monitoring.ApiEndpointRegistry;
import com.axonect.aee.template.baseapp.application.monitoring.MonitoredEndpoint;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiResponseTimeRecorderTest {

    private static final String CREATE_USER_URI = "/api/user";

    private SimpleMeterRegistry registry;
    private ResponseTimeProperties properties;
    private ApiResponseTimeRecorder recorder;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        properties = new ResponseTimeProperties();
        recorder = new ApiResponseTimeRecorder(registry, catalog(), properties, "UUID");
    }

    private static ApiEndpointRegistry catalog() {
        MonitoredEndpoint createUser = new MonitoredEndpoint();
        createUser.setName("create_user");
        createUser.setTitle("Create user");
        createUser.setMethod("POST");
        createUser.setUris(List.of(CREATE_USER_URI));
        return new ApiEndpointRegistry(List.of(createUser));
    }

    /** A request Spring matched to the catalogued handler. */
    private static MockHttpServletRequest catalogued() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", CREATE_USER_URI);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, CREATE_USER_URI);
        return request;
    }

    /**
     * Every request the filter sees is over the slow threshold. Timing a real
     * request would mean sleeping for a second; the thresholds are configuration,
     * so moving them is the same test without the wait. {@link RequestSpeedTest}
     * covers the arithmetic they drive.
     */
    private void everyRequestIsSlow() {
        properties.setSlowRequestThresholdMs(0);
        properties.setVerySlowRequestThresholdMs(Long.MAX_VALUE);
    }

    private Counter slowRequests(String api, String status, String severity) {
        return registry.find(ApiResponseTimeRecorder.SLOW_REQUESTS_METRIC)
                .tag("api", api).tag("status", status).tag("severity", severity).counter();
    }

    private Gauge lastDuration(String api) {
        return registry.find(ApiResponseTimeRecorder.LAST_DURATION_METRIC).tag("api", api).gauge();
    }

    @Test
    void recordsTheDurationOfARequestUnderTheApiThatServedIt() throws ServletException, IOException {
        recorder.doFilter(catalogued(), new MockHttpServletResponse(), new MockFilterChain());

        Gauge gauge = lastDuration("create_user");
        assertNotNull(gauge, "the request should be reported under its catalogue name");
        assertTrue(gauge.value() >= 0, "a duration is never negative");
        // Nothing was slow, so there is no slow request series at all.
        assertNull(registry.find(ApiResponseTimeRecorder.SLOW_REQUESTS_METRIC).counter());
    }

    @Test
    void countsASlowRequestOncePerApiStatusAndSeverity() throws ServletException, IOException {
        everyRequestIsSlow();

        recorder.doFilter(catalogued(), new MockHttpServletResponse(), new MockFilterChain());
        recorder.doFilter(catalogued(), new MockHttpServletResponse(), new MockFilterChain());

        assertEquals(2, slowRequests("create_user", "200", "slow").count());
    }

    @Test
    void separatesVerySlowRequestsFromMerelySlowOnes() throws ServletException, IOException {
        properties.setSlowRequestThresholdMs(0);
        properties.setVerySlowRequestThresholdMs(0);

        recorder.doFilter(catalogued(), new MockHttpServletResponse(), new MockFilterChain());

        assertEquals(1, slowRequests("create_user", "200", "very_slow").count());
        assertNull(slowRequests("create_user", "200", "slow"));
    }

    @Test
    void keepsTheStatusTheCallerSaw() throws ServletException, IOException {
        everyRequestIsSlow();
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(400);

        recorder.doFilter(catalogued(), response, new MockFilterChain());

        assertEquals(1, slowRequests("create_user", "400", "slow").count());
    }

    @Test
    void reportsARequestThatThrewAsTheServerErrorItIsAboutToBecome() {
        everyRequestIsSlow();
        MockFilterChain failing = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
                throw new IllegalStateException("handler blew up");
            }
        };

        // The exception still reaches the container - monitoring observes, it does
        // not swallow.
        assertThrows(IllegalStateException.class,
                () -> recorder.doFilter(catalogued(), new MockHttpServletResponse(), failing));

        assertEquals(1, slowRequests("create_user", "500", "slow").count());
    }

    @Test
    void ignoresTrafficOutsideTheCatalogUnlessAskedFor() throws ServletException, IOException {
        everyRequestIsSlow();
        MockHttpServletRequest actuator = new MockHttpServletRequest("GET", "/actuator/prometheus");

        recorder.doFilter(actuator, new MockHttpServletResponse(), new MockFilterChain());

        assertNull(lastDuration("other"), "uncatalogued traffic has no API to be reported under");
        assertNull(registry.find(ApiResponseTimeRecorder.SLOW_REQUESTS_METRIC).counter());

        properties.setIncludeUncatalogued(true);
        recorder.doFilter(actuator, new MockHttpServletResponse(), new MockFilterChain());

        assertEquals(1, slowRequests("other", "200", "slow").count());
    }

    @Test
    void reportsARequestRejectedBeforeTheHandlerUnderTheApiItAskedFor() throws ServletException, IOException {
        everyRequestIsSlow();
        // ChannelAuthFilter answers 401 without the dispatcher ever running, so
        // there is no best-matching pattern to read the API off.
        MockHttpServletRequest rejected = new MockHttpServletRequest("POST", CREATE_USER_URI);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(401);

        recorder.doFilter(rejected, response, new MockFilterChain());

        assertEquals(1, slowRequests("create_user", "401", "slow").count(),
                "a rejected request is still a request against that API");
    }

    @Test
    void waitsForTheAsyncDispatchBeforeRecordingAnAsyncRequest() throws ServletException, IOException {
        everyRequestIsSlow();
        MockHttpServletRequest request = catalogued();
        request.setAsyncSupported(true);
        request.startAsync();

        recorder.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertNull(registry.find(ApiResponseTimeRecorder.SLOW_REQUESTS_METRIC).counter(),
                "the response has not been written yet, so there is nothing to time");

        // The container comes back through the filter on the ASYNC dispatch, with
        // the same request and therefore the same start time.
        request.setAsyncStarted(false);
        recorder.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertEquals(1, slowRequests("create_user", "200", "slow").count());
    }
}
