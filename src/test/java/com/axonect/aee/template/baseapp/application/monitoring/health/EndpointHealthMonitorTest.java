package com.axonect.aee.template.baseapp.application.monitoring.health;

import com.axonect.aee.template.baseapp.application.monitoring.ApiEndpointRegistry;
import com.axonect.aee.template.baseapp.application.monitoring.MonitoredEndpoint;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The monitor end to end: real Micrometer timers in, published gauges out.
 *
 * <p>The evaluation window needs two samples to produce a delta, so every test here
 * calls {@code evaluate()} once to take a baseline, generates traffic, and calls it
 * again - which is exactly what the schedule does every 15 seconds in production.</p>
 */
class EndpointHealthMonitorTest {

    private static final String LIVE_GET_USER = "GET /api/user/{}";
    private static final String LIVE_CREATE_USER = "POST /api/user";

    private MeterRegistry registry;
    private EndpointHealthProperties properties;
    private Set<String> dependenciesDown;
    private Set<String> liveMappings;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        properties = new EndpointHealthProperties();
        properties.setMinimumRequests(20);
        dependenciesDown = new HashSet<>();
        liveMappings = new HashSet<>(Set.of(LIVE_GET_USER, LIVE_CREATE_USER));
    }

    private EndpointHealthMonitor monitor() {
        MonitoredEndpoint getUser = endpoint("get_user", "Get user", "GET", "/api/user/{user_name}", "database");
        MonitoredEndpoint createUser =
                endpoint("create_user", "Create user", "POST", "/api/user", "database", "kafka");
        return new EndpointHealthMonitor(registry, new ApiEndpointRegistry(List.of(getUser, createUser)),
                properties, dependency -> !dependenciesDown.contains(dependency), () -> liveMappings);
    }

    private static MonitoredEndpoint endpoint(String name, String title, String method, String uri,
                                              String... dependencies) {
        MonitoredEndpoint endpoint = new MonitoredEndpoint();
        endpoint.setName(name);
        endpoint.setTitle(title);
        endpoint.setMethod(method);
        endpoint.setUris(List.of(uri));
        endpoint.setDependencies(List.of(dependencies));
        return endpoint;
    }

    private void request(String api, String status, int times) {
        for (int i = 0; i < times; i++) {
            registry.timer("http.server.requests", "api", api, "status", status, "method", "GET")
                    .record(Duration.ofMillis(5));
        }
    }

    private double gauge(String name, String api) {
        return registry.get(name).tag("api", api).gauge().value();
    }

    private double reason(String api, EndpointHealthReason reason) {
        return registry.get("api.endpoint.health.reason")
                .tag("api", api).tag("reason", reason.label()).gauge().value();
    }

    @Test
    void everyEndpointIsPublishedBeforeItHasSeenATingleRequest() {
        monitor();

        assertEquals(EndpointHealth.UNKNOWN.code(), gauge("api.endpoint.health", "get_user"));
        assertEquals(EndpointHealth.UNKNOWN.code(), gauge("api.endpoint.health", "create_user"));
        assertEquals(1, reason("get_user", EndpointHealthReason.UNKNOWN));
    }

    @Test
    void anIdleEndpointWithHealthyDependenciesIsHealthy() {
        EndpointHealthMonitor monitor = monitor();
        monitor.evaluate();
        monitor.evaluate();

        assertEquals(EndpointHealth.HEALTHY.code(), gauge("api.endpoint.health", "get_user"));
        assertEquals(1, reason("get_user", EndpointHealthReason.IDLE));
        assertEquals(0, gauge("api.endpoint.requests.window", "get_user"));
    }

    @Test
    void cleanTrafficReadsHealthyAndIsCounted() {
        EndpointHealthMonitor monitor = monitor();
        monitor.evaluate();

        request("get_user", "200", 50);
        monitor.evaluate();

        assertEquals(EndpointHealth.HEALTHY.code(), gauge("api.endpoint.health", "get_user"));
        assertEquals(1, reason("get_user", EndpointHealthReason.OK));
        assertEquals(50, gauge("api.endpoint.requests.window", "get_user"));
        assertEquals(0, gauge("api.endpoint.errors.window", "get_user"));
        assertEquals(0, gauge("api.endpoint.error.ratio", "get_user"));
    }

    @Test
    void serverErrorsAboveTheRatioTurnTheEndpointUnhealthy() {
        EndpointHealthMonitor monitor = monitor();
        monitor.evaluate();

        request("get_user", "200", 50);
        request("get_user", "500", 50);
        monitor.evaluate();

        assertEquals(EndpointHealth.UNHEALTHY.code(), gauge("api.endpoint.health", "get_user"));
        assertEquals(1, reason("get_user", EndpointHealthReason.ERRORS));
        assertEquals(100, gauge("api.endpoint.requests.window", "get_user"));
        assertEquals(50, gauge("api.endpoint.errors.window", "get_user"));
        assertEquals(0.5, gauge("api.endpoint.error.ratio", "get_user"), 1e-9);
    }

    /** 4xx is the endpoint rejecting a bad request, which is it working. */
    @Test
    void clientErrorsDoNotCountAgainstHealth() {
        EndpointHealthMonitor monitor = monitor();
        monitor.evaluate();

        request("get_user", "400", 50);
        request("get_user", "404", 50);
        monitor.evaluate();

        assertEquals(EndpointHealth.HEALTHY.code(), gauge("api.endpoint.health", "get_user"));
        assertEquals(0, gauge("api.endpoint.errors.window", "get_user"));
    }

    /**
     * The gap availability cannot cover: no traffic at all, and the database gone.
     * Availability would report 100% for this endpoint.
     */
    @Test
    void anIdleEndpointGoesUnhealthyWhenARequiredDependencyGoesDown() {
        EndpointHealthMonitor monitor = monitor();
        monitor.evaluate();

        dependenciesDown.add("database");
        monitor.evaluate();

        assertEquals(EndpointHealth.UNHEALTHY.code(), gauge("api.endpoint.health", "get_user"));
        assertEquals(1, reason("get_user", EndpointHealthReason.DEPENDENCY_DOWN));
        assertTrue(monitor.snapshot().get("get_user").dependenciesDown().contains("database"));
    }

    /** kafka is declared by create_user only, so only create_user reacts to it. */
    @Test
    void anEndpointIsOnlyAffectedByTheDependenciesItDeclares() {
        EndpointHealthMonitor monitor = monitor();
        monitor.evaluate();

        dependenciesDown.add("kafka");
        monitor.evaluate();

        assertEquals(EndpointHealth.UNHEALTHY.code(), gauge("api.endpoint.health", "create_user"));
        assertEquals(EndpointHealth.HEALTHY.code(), gauge("api.endpoint.health", "get_user"));
    }

    @Test
    void anEndpointWithNoHandlerInThisBuildIsUnhealthyAndFlagged() {
        liveMappings.remove(LIVE_GET_USER);

        EndpointHealthMonitor monitor = monitor();
        monitor.evaluate();

        assertEquals(0, gauge("api.endpoint.mapped", "get_user"));
        assertEquals(1, gauge("api.endpoint.mapped", "create_user"));
        assertEquals(EndpointHealth.UNHEALTHY.code(), gauge("api.endpoint.health", "get_user"));
        assertEquals(1, reason("get_user", EndpointHealthReason.NOT_MAPPED));
    }

    /** A check that cannot run must not invent an outage. */
    @Test
    void everyEndpointIsTreatedAsMappedWhenTheMappingsCannotBeRead() {
        liveMappings.clear();

        EndpointHealthMonitor monitor = monitor();
        monitor.evaluate();

        assertEquals(1, gauge("api.endpoint.mapped", "get_user"));
        assertEquals(1, gauge("api.endpoint.mapped", "create_user"));
    }

    @Test
    void mappingVerificationCanBeTurnedOff() {
        properties.setVerifyMappings(false);
        liveMappings.clear();

        EndpointHealthMonitor monitor = monitor();
        monitor.evaluate();

        assertEquals(1, gauge("api.endpoint.mapped", "get_user"));
    }

    @Test
    void exactlyOneReasonIsEverSetForAnEndpoint() {
        EndpointHealthMonitor monitor = monitor();
        monitor.evaluate();

        request("get_user", "500", 100);
        monitor.evaluate();

        double active = 0;
        for (EndpointHealthReason candidate : EndpointHealthReason.values()) {
            active += reason("get_user", candidate);
        }
        assertEquals(1, active, "exactly one reason series must be 1");
    }

    @Test
    void stateChangesAreCountedAndOnlyOnceEach() {
        EndpointHealthMonitor monitor = monitor();
        monitor.evaluate();
        monitor.evaluate();

        dependenciesDown.add("database");
        monitor.evaluate();
        monitor.evaluate();

        assertEquals(1, registry.get("api.endpoint.health.transitions")
                .tag("api", "get_user").tag("to", "unhealthy").counter().count());

        dependenciesDown.clear();
        monitor.evaluate();

        assertEquals(1, registry.get("api.endpoint.health.transitions")
                .tag("api", "get_user").tag("to", "unhealthy").counter().count());
        assertEquals(2, registry.get("api.endpoint.health.transitions")
                .tag("api", "get_user").tag("to", "healthy").counter().count(),
                "one transition into healthy on the first verdict, one on recovery");
    }

    @Test
    void theUnhealthyStretchIsMeasuredAndResetOnRecovery() throws InterruptedException {
        EndpointHealthMonitor monitor = monitor();
        monitor.evaluate();

        dependenciesDown.add("database");
        monitor.evaluate();
        Thread.sleep(1100);
        monitor.evaluate();

        assertTrue(gauge("api.endpoint.unhealthy.seconds", "get_user") >= 1,
                "the stretch should have been running for at least a second");

        dependenciesDown.clear();
        monitor.evaluate();

        assertEquals(0, gauge("api.endpoint.unhealthy.seconds", "get_user"));
    }

    @Test
    void theLastFailureTimestampIsPublishedOnlyOnceSomethingHasFailed() {
        EndpointHealthMonitor monitor = monitor();
        monitor.evaluate();
        monitor.evaluate();

        assertEquals(0, gauge("api.endpoint.last.failure.timestamp.seconds", "get_user"));
        assertNull(monitor.snapshot().get("get_user").lastFailureEpochSeconds());

        request("get_user", "500", 5);
        monitor.evaluate();

        assertTrue(gauge("api.endpoint.last.failure.timestamp.seconds", "get_user") > 0);
        assertNotNull(monitor.snapshot().get("get_user").lastFailureEpochSeconds());
    }

    @Test
    void theDeclaredDependenciesArePublishedAsTheirOwnSeries() {
        monitor();

        assertEquals(1, registry.get("api.endpoint.dependency.required")
                .tag("api", "create_user").tag("dependency", "kafka").gauge().value());
        assertEquals(1, registry.get("api.endpoint.dependency.required")
                .tag("api", "get_user").tag("dependency", "database").gauge().value());
    }

    @Test
    void theWorstEndpointIsWhatTheServiceLevelViewReports() {
        EndpointHealthMonitor monitor = monitor();
        monitor.evaluate();
        monitor.evaluate();

        assertEquals(EndpointHealth.HEALTHY, monitor.worstHealth());
        assertTrue(monitor.allHealthy());

        dependenciesDown.add("kafka");
        monitor.evaluate();

        assertEquals(EndpointHealth.UNHEALTHY, monitor.worstHealth());
        assertFalse(monitor.allHealthy());
    }

    @Test
    void theSnapshotCarriesTheDetailNoMetricLabelCould() {
        EndpointHealthMonitor monitor = monitor();
        monitor.evaluate();

        request("get_user", "500", 40);
        request("get_user", "200", 60);
        monitor.evaluate();

        Map<String, EndpointHealthStatus> snapshot = monitor.snapshot();
        EndpointHealthStatus status = snapshot.get("get_user");

        assertEquals("Get user", status.title());
        assertEquals("GET", status.method());
        assertEquals("unhealthy", status.health());
        assertEquals("errors", status.reason());
        assertTrue(status.mapped());
        assertEquals(100, status.requestsInWindow());
        assertEquals(40, status.errorsInWindow());
        assertEquals(0.4, status.errorRatio(), 1e-9);
        assertEquals(List.of("database"), status.dependencies());
        assertTrue(status.detail().contains("40 of 100"), status.detail());
    }

    /** Traffic to a path outside the catalog is tagged `other` and must not leak in. */
    @Test
    void trafficThatIsNotInTheCatalogIsIgnored() {
        EndpointHealthMonitor monitor = monitor();
        monitor.evaluate();

        request("other", "500", 500);
        monitor.evaluate();

        assertEquals(EndpointHealth.HEALTHY.code(), gauge("api.endpoint.health", "get_user"));
        assertEquals(0, gauge("api.endpoint.requests.window", "get_user"));
    }
}
