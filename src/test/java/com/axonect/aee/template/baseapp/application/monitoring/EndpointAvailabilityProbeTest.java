package com.axonect.aee.template.baseapp.application.monitoring;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndpointAvailabilityProbeTest {

    private MockWebServer server;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        meterRegistry = new SimpleMeterRegistry();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private static MonitoredEndpoint endpoint(String name, String method, String probePath, Integer... expected) {
        MonitoredEndpoint endpoint = new MonitoredEndpoint();
        endpoint.setName(name);
        endpoint.setTitle(name);
        endpoint.setMethod(method);
        endpoint.setUris(List.of("/api/user/{user_name}"));
        endpoint.setProbePath(probePath);
        endpoint.setProbeExpectedStatuses(List.of(expected));
        return endpoint;
    }

    private EndpointAvailabilityProbe probeFor(MonitoredEndpoint... endpoints) {
        ApiMonitoringProperties properties = new ApiMonitoringProperties();
        properties.setEndpoints(List.of(endpoints));
        properties.getProbe().setEnabled(true);
        properties.getProbe().setBaseUrl(server.url("/").toString());
        properties.getProbe().setTimeout(Duration.ofSeconds(5));
        ApiEndpointRegistry registry = new ApiEndpointRegistry(properties.getEndpoints(), 2000L);
        return new EndpointAvailabilityProbe(properties, registry, meterRegistry);
    }

    private Double upValue(String api) {
        Gauge gauge = meterRegistry.find(EndpointAvailabilityProbe.UP_GAUGE).tag("api", api).gauge();
        return gauge == null ? null : gauge.value();
    }

    @Test
    void reportsUpWhenTheEndpointAnswersAsExpected() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(200));
        EndpointAvailabilityProbe probe = probeFor(endpoint("get_user", "GET", "/api/user/probe-user", 200));

        probe.probeAll();

        assertEquals(1.0, upValue("get_user"));
        RecordedRequest recorded = server.takeRequest();
        assertEquals("GET", recorded.getMethod());
        assertEquals("/api/user/probe-user", recorded.getPath());
        assertNotNull(meterRegistry.find(EndpointAvailabilityProbe.DURATION_TIMER).tag("api", "get_user").timer());
    }

    @Test
    void reportsDownOnAnUnexpectedStatus() {
        server.enqueue(new MockResponse().setResponseCode(500));
        EndpointAvailabilityProbe probe = probeFor(endpoint("get_user", "GET", "/api/user/probe-user", 200));

        probe.probeAll();

        assertEquals(0.0, upValue("get_user"));
        assertEquals(1.0, meterRegistry.find(EndpointAvailabilityProbe.FAILURE_COUNTER)
                .tag("api", "get_user").tag("reason", "status_500").counter().count());
    }

    @Test
    void acceptsEveryStatusTheEndpointDeclares() {
        server.enqueue(new MockResponse().setResponseCode(404));
        EndpointAvailabilityProbe probe = probeFor(endpoint("get_user", "GET", "/api/user/probe-user", 200, 404));

        probe.probeAll();

        // A missing probe identity is an answer, not an outage.
        assertEquals(1.0, upValue("get_user"));
    }

    @Test
    void defaultsToExpectingHttp200() {
        server.enqueue(new MockResponse().setResponseCode(204));
        MonitoredEndpoint endpoint = endpoint("get_user", "GET", "/api/user/probe-user");
        EndpointAvailabilityProbe probe = probeFor(endpoint);

        probe.probeAll();

        assertEquals(0.0, upValue("get_user"));
    }

    @Test
    void refusesToProbeAnythingThatChangesState() {
        EndpointAvailabilityProbe probe = probeFor(
                endpoint("create_user", "POST", "/api/user", 200),
                endpoint("delete_user", "DELETE", "/api/user/probe-user", 200),
                endpoint("update_service", "PATCH", "/api/user/probe/services/p/r", 200));

        probe.probeAll();

        assertTrue(probe.plannedProbes().isEmpty(), "no state changing endpoint may be probed");
        assertNull(upValue("create_user"));
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void onlyProbesEndpointsThatDeclareAProbePath() {
        EndpointAvailabilityProbe probe = probeFor(
                endpoint("get_user", "GET", "/api/user/probe-user", 200),
                endpoint("get_users", "GET", null, 200));

        assertEquals(List.of("get_user"), List.copyOf(probe.plannedProbes().keySet()));
    }

    @Test
    void reportsDownWhenTheEndpointCannotBeReached() throws IOException {
        EndpointAvailabilityProbe probe = probeFor(endpoint("get_user", "GET", "/api/user/probe-user", 200));
        server.shutdown();

        probe.probeAll();

        assertEquals(0.0, upValue("get_user"));
        assertEquals(1.0, meterRegistry.find(EndpointAvailabilityProbe.FAILURE_COUNTER)
                .tag("api", "get_user").tag("reason", "error").counter().count());
    }
}
