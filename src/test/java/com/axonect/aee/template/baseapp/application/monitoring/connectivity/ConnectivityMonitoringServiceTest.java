package com.axonect.aee.template.baseapp.application.monitoring.connectivity;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.MeterNotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.net.ConnectException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectivityMonitoringServiceTest {

    private static final String SERVICE = "airtel-aaa-user-provisioning-service";

    private MeterRegistry registry;
    private ConnectivityMonitoringProperties properties;
    private ConnectivityMonitoringService service;

    /** A probe whose outcome the test decides, and which records how often it ran. */
    private static final class FakeProbe implements DependencyProbe {

        private final Dependency dependency;
        private final AtomicInteger runs = new AtomicInteger();
        private volatile Exception failure;
        private volatile CountDownLatch block;

        private FakeProbe(Dependency dependency) {
            this.dependency = dependency;
        }

        @Override
        public Dependency dependency() {
            return dependency;
        }

        @Override
        public void probe() throws Exception {
            runs.incrementAndGet();
            if (block != null) {
                block.await();
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        properties = new ConnectivityMonitoringProperties();
        properties.setServiceName(SERVICE);
        properties.setProbeTimeoutMs(200);
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    private ConnectivityMonitoringService serviceWith(DependencyProbe... probes) {
        service = new ConnectivityMonitoringService(registry, properties, List.of(probes));
        return service;
    }

    private double gauge(String name, Dependency dependency) {
        return registry.get(name).tags("service", SERVICE, "dependency", dependency.label()).gauge().value();
    }

    private double counter(String name, Dependency dependency, ConnectivityFailureReason reason) {
        return registry.get(name)
                .tags("service", SERVICE, "dependency", dependency.label(), "reason", reason.label())
                .counter().count();
    }

    private long probeLatencyCount(Dependency dependency, String outcome) {
        return registry.get("dependency.probe.latency")
                .tags("service", SERVICE, "dependency", dependency.label(), "outcome", outcome)
                .timer().count();
    }

    @Test
    void startsWithEveryDependencyUp() {
        serviceWith();

        assertTrue(service.allUp());
        assertEquals(1.0, gauge("dependency.up", Dependency.DATABASE));
        assertEquals(List.of("database", "redis", "kafka"), List.copyOf(service.snapshot().keySet()));
    }

    @Test
    void marksDependencyDownOnlyOnceTheThresholdIsReached() {
        serviceWith();

        service.recordFailure(Dependency.DATABASE, new ConnectException("Connection refused"));
        service.recordFailure(Dependency.DATABASE, new ConnectException("Connection refused"));
        assertTrue(service.isUp(Dependency.DATABASE), "two failures is still within the threshold of three");

        service.recordFailure(Dependency.DATABASE, new ConnectException("Connection refused"));

        assertFalse(service.isUp(Dependency.DATABASE));
        assertFalse(service.allUp());
        assertEquals(0.0, gauge("dependency.up", Dependency.DATABASE));
        assertEquals(3.0, gauge("dependency.consecutive.failure.count", Dependency.DATABASE));
        assertEquals(3.0, gauge("dependency.connectivity.failure.daily.count", Dependency.DATABASE));
        assertEquals(3.0, counter("dependency.connectivity.failure.count",
                Dependency.DATABASE, ConnectivityFailureReason.CONNECTION_REFUSED));
        assertEquals(1.0, registry.get("dependency.outage.count")
                .tags("service", SERVICE, "dependency", "database").counter().count());
        // One dependency down leaves the others alone.
        assertTrue(service.isUp(Dependency.REDIS));
    }

    @Test
    void applicationErrorsAreCountedButNeverMarkADependencyDown() {
        serviceWith();
        SQLException constraintViolation = new SQLException("ORA-00001: unique constraint violated");

        for (int i = 0; i < 5; i++) {
            assertEquals(ConnectivityFailureReason.APPLICATION_ERROR,
                    service.recordFailure(Dependency.DATABASE, constraintViolation));
        }

        assertTrue(service.isUp(Dependency.DATABASE));
        assertEquals(5.0, counter("dependency.error.count",
                Dependency.DATABASE, ConnectivityFailureReason.APPLICATION_ERROR));
        assertEquals(0.0, gauge("dependency.connectivity.failure.daily.count", Dependency.DATABASE));
        // No connectivity failure ever happened, so that series does not exist at all.
        assertThrows(MeterNotFoundException.class, () -> counter("dependency.connectivity.failure.count",
                Dependency.DATABASE, ConnectivityFailureReason.APPLICATION_ERROR));
    }

    @Test
    void recoversOnTheFirstSuccessAndRecordsTheOutage() {
        serviceWith();
        for (int i = 0; i < 3; i++) {
            service.recordFailure(Dependency.REDIS, new ConnectException("Connection refused"));
        }
        assertFalse(service.isUp(Dependency.REDIS));

        service.recordSuccess(Dependency.REDIS);

        assertTrue(service.isUp(Dependency.REDIS));
        assertEquals(0.0, gauge("dependency.consecutive.failure.count", Dependency.REDIS));
        assertEquals(0.0, gauge("dependency.downtime.seconds", Dependency.REDIS));
        assertEquals(1L, registry.get("dependency.outage.duration")
                .tags("service", SERVICE, "dependency", "redis").timer().count());
        // The lifetime failure count survives recovery; only the state resets.
        ConnectivityMonitoringService.DependencyStatus status = service.snapshot().get("redis");
        assertEquals(3, status.connectivityFailureCount());
        assertEquals(1, status.outageCount());
        assertEquals("connection_refused", status.lastFailureReason());
        assertTrue(status.up());
    }

    @Test
    void attributesFailuresThatDoNotSayWhichDependencyTheyCameFrom() {
        serviceWith();

        assertEquals(Dependency.REDIS, service.recordThrowable(
                new RedisConnectionFailureException("Unable to connect", new ConnectException("Connection refused"))));
        assertEquals(1.0, counter("dependency.connectivity.failure.count",
                Dependency.REDIS, ConnectivityFailureReason.CONNECTION_REFUSED));

        // A business failure points at no dependency and must not be counted against one.
        assertNull(service.recordThrowable(new IllegalArgumentException("user_name is required")));
    }

    @Test
    void successfulProbeKeepsTheDependencyUpAndTimesTheRoundTrip() {
        FakeProbe probe = new FakeProbe(Dependency.DATABASE);
        serviceWith(probe);

        service.probeDependencies();

        assertEquals(1, probe.runs.get());
        assertTrue(service.isUp(Dependency.DATABASE));
        assertEquals(1L, probeLatencyCount(Dependency.DATABASE, "success"));
    }

    @Test
    void failedProbeCountsAsAConnectivityFailureEvenWhenTheCauseIsUnrecognisable() {
        FakeProbe probe = new FakeProbe(Dependency.KAFKA);
        probe.failure = new IllegalStateException("something went wrong");
        serviceWith(probe);

        service.probeDependencies();

        // The classifier cannot name this one, but a probe that could not complete is
        // an outage by definition - otherwise a dependency could never be marked down.
        assertEquals(1.0, counter("dependency.connectivity.failure.count",
                Dependency.KAFKA, ConnectivityFailureReason.SERVICE_UNAVAILABLE));
        assertEquals(1L, probeLatencyCount(Dependency.KAFKA, "failure"));
    }

    @Test
    void probeThatStopsAnsweringIsAbandonedAtItsDeadline() throws Exception {
        FakeProbe hanging = new FakeProbe(Dependency.DATABASE);
        hanging.block = new CountDownLatch(1);
        FakeProbe healthy = new FakeProbe(Dependency.REDIS);
        serviceWith(hanging, healthy);

        long startMillis = System.currentTimeMillis();
        service.probeDependencies();
        long elapsedMillis = System.currentTimeMillis() - startMillis;

        try {
            // The round must not sit on the dead dependency: it gives up at the probe
            // timeout, and the healthy dependency alongside it is probed regardless.
            assertTrue(elapsedMillis < 2000, "probe round took " + elapsedMillis + "ms");
            assertEquals(1.0, counter("dependency.connectivity.failure.count",
                    Dependency.DATABASE, ConnectivityFailureReason.CONNECTION_TIMEOUT));
            assertEquals(1, healthy.runs.get());
            assertTrue(service.isUp(Dependency.REDIS));
        } finally {
            hanging.block.countDown();
        }
    }

    @Test
    void probesDoNotRunWhenMonitoringIsDisabled() {
        properties.setEnabled(false);
        FakeProbe probe = new FakeProbe(Dependency.DATABASE);
        serviceWith(probe);

        service.probeDependencies();

        assertEquals(0, probe.runs.get());
        // Live traffic failures still count, which is the point of the switch.
        service.recordFailure(Dependency.DATABASE, new ConnectException("Connection refused"));
        assertEquals(1.0, gauge("dependency.consecutive.failure.count", Dependency.DATABASE));
    }

    @Test
    void dailyResetClearsTheDailyWindowOnly() {
        serviceWith();
        service.recordFailure(Dependency.KAFKA, new ConnectException("Connection refused"));

        service.resetDailyCounters();

        assertEquals(0.0, gauge("dependency.connectivity.failure.daily.count", Dependency.KAFKA));
        assertEquals(1.0, counter("dependency.connectivity.failure.count",
                Dependency.KAFKA, ConnectivityFailureReason.CONNECTION_REFUSED));
        assertEquals(1, service.snapshot().get("kafka").connectivityFailureCount());
    }

    @Test
    void snapshotIsAReadOnlyViewOfEveryDependency() {
        serviceWith();

        Map<String, ConnectivityMonitoringService.DependencyStatus> snapshot = service.snapshot();

        assertEquals(3, snapshot.size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.remove("redis"));
        assertNull(snapshot.get("database").lastFailureReason());
    }

    @Test
    void ignoresNullArguments() {
        serviceWith();

        assertEquals(ConnectivityFailureReason.APPLICATION_ERROR, service.recordFailure(null, new RuntimeException()));
        assertEquals(ConnectivityFailureReason.APPLICATION_ERROR,
                service.recordFailure(Dependency.DATABASE, null));
        service.recordSuccess(null);
        assertNull(service.recordThrowable(null));
        assertTrue(service.allUp());
    }

    @Test
    void shutdownIsSafeToCallWhenNoProbeEverRan() {
        serviceWith();

        service.shutdown();

        assertTrue(service.allUp());
    }

    @Test
    void probeRoundStartsEveryProbeBeforeCollectingAnyOfThem() throws Exception {
        // Two hanging probes must not add up: they are started together, so the round
        // costs one timeout, not one per dependency.
        FakeProbe first = new FakeProbe(Dependency.DATABASE);
        first.block = new CountDownLatch(1);
        FakeProbe second = new FakeProbe(Dependency.REDIS);
        second.block = new CountDownLatch(1);
        serviceWith(first, second);

        long startMillis = System.currentTimeMillis();
        service.probeDependencies();
        long elapsedMillis = System.currentTimeMillis() - startMillis;

        try {
            assertTrue(elapsedMillis < 2 * properties.getProbeTimeoutMs() + 1000,
                    "probe round took " + elapsedMillis + "ms");
            assertEquals(1, first.runs.get());
            assertEquals(1, second.runs.get());
        } finally {
            first.block.countDown();
            second.block.countDown();
        }
    }

    @Test
    void closesProbesThatHoldResourcesOnShutdown() {
        AtomicInteger closed = new AtomicInteger();
        class CloseableProbe implements DependencyProbe, AutoCloseable {
            @Override
            public Dependency dependency() {
                return Dependency.KAFKA;
            }

            @Override
            public void probe() {
                // nothing to do
            }

            @Override
            public void close() {
                closed.incrementAndGet();
            }
        }
        serviceWith(new CloseableProbe());

        service.shutdown();

        assertEquals(1, closed.get(), "the Kafka AdminClient has to be closed with the context");
    }

    @Test
    void probeRoundAfterShutdownIsASilentNoOp() {
        FakeProbe probe = new FakeProbe(Dependency.DATABASE);
        serviceWith(probe);
        service.probeDependencies();

        service.shutdown();

        // The scheduler can fire once more while the context is closing; submitting to
        // a stopped executor would throw and be logged as an unrelated failure.
        service.probeDependencies();
        assertEquals(1, probe.runs.get());
    }
}
