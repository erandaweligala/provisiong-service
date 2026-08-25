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
package com.axonect.aee.template.baseapp.application.monitoring.connectivity;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks reachability of the three pieces of infrastructure this service cannot run
 * without - the Oracle database, Redis and Kafka - and publishes that state to
 * Prometheus so Grafana can chart and alert on it.
 *
 * <p>Two independent signals feed the same state machine:</p>
 * <ul>
 *   <li><b>Live traffic.</b> Failures seen while serving requests are attributed to a
 *       dependency by {@link DependencyResolver}, classified by
 *       {@link ConnectivityFailureClassifier}, and - when the classifier calls it a
 *       transport failure rather than an application error - counted towards that
 *       dependency's consecutive-failure streak.</li>
 *   <li><b>Active probes.</b> Every {@code connectivity.probe-interval-ms} each
 *       dependency is pinged, so an outage is visible even when no traffic is flowing
 *       and recovery is picked up without waiting for a successful request.</li>
 * </ul>
 *
 * <p>A dependency flips to DOWN after {@code connectivity.failure-threshold}
 * consecutive connectivity failures and back to UP on the first success from either
 * signal. Both transitions are logged.</p>
 *
 * <h2>Metrics</h2>
 * <table>
 *   <caption>Meters registered by this service, all tagged {@code service} and {@code dependency}</caption>
 *   <tr><td>{@code dependency_up}</td><td>gauge, 1 = reachable, 0 = down</td></tr>
 *   <tr><td>{@code dependency_connectivity_failure_count_total}</td><td>counter by {@code reason}</td></tr>
 *   <tr><td>{@code dependency_error_count_total}</td><td>counter of all failures, connectivity or not</td></tr>
 *   <tr><td>{@code dependency_connectivity_failure_daily_count}</td><td>gauge, resets at 00:00</td></tr>
 *   <tr><td>{@code dependency_consecutive_failure_count}</td><td>gauge, current failure streak</td></tr>
 *   <tr><td>{@code dependency_outage_count_total}</td><td>counter of UP&rarr;DOWN transitions</td></tr>
 *   <tr><td>{@code dependency_downtime_seconds}</td><td>gauge, length of the outage in progress</td></tr>
 *   <tr><td>{@code dependency_last_failure_timestamp_seconds}</td><td>gauge, epoch seconds</td></tr>
 *   <tr><td>{@code dependency_last_success_timestamp_seconds}</td><td>gauge, epoch seconds</td></tr>
 *   <tr><td>{@code dependency_outage_duration_seconds}</td><td>timer, one record per recovery</td></tr>
 *   <tr><td>{@code dependency_probe_latency_seconds}</td><td>timer by {@code outcome}</td></tr>
 * </table>
 *
 * <p>The names are the ones the DB write service publishes, so a single Grafana
 * dashboard covers both; the {@code service} tag is what tells them apart.</p>
 */
@Slf4j
public class ConnectivityMonitoringService {

    private static final String METRIC_UP = "dependency.up";
    private static final String METRIC_CONNECTIVITY_FAILURE = "dependency.connectivity.failure.count";
    private static final String METRIC_ERROR = "dependency.error.count";
    private static final String METRIC_FAILURE_DAILY = "dependency.connectivity.failure.daily.count";
    private static final String METRIC_CONSECUTIVE = "dependency.consecutive.failure.count";
    private static final String METRIC_OUTAGE = "dependency.outage.count";
    private static final String METRIC_DOWNTIME = "dependency.downtime.seconds";
    private static final String METRIC_LAST_FAILURE = "dependency.last.failure.timestamp.seconds";
    private static final String METRIC_LAST_SUCCESS = "dependency.last.success.timestamp.seconds";
    private static final String METRIC_OUTAGE_DURATION = "dependency.outage.duration";
    private static final String METRIC_PROBE_LATENCY = "dependency.probe.latency";

    private static final String TAG_SERVICE = "service";
    private static final String TAG_DEPENDENCY = "dependency";
    private static final String TAG_REASON = "reason";
    private static final String TAG_OUTCOME = "outcome";
    private static final String OUTCOME_SUCCESS = "success";
    private static final String OUTCOME_FAILURE = "failure";

    private static final int MILLIS_PER_SECOND = 1000;
    private static final long EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 5;

    private final MeterRegistry registry;
    private final ConnectivityMonitoringProperties properties;
    private final List<DependencyProbe> probes;
    private final ExecutorService probeExecutor;
    private final Map<Dependency, DependencyState> states = new EnumMap<>(Dependency.class);

    /** Set once the context is closing, so a scheduled round in flight stops submitting. */
    private volatile boolean shuttingDown;

    public ConnectivityMonitoringService(MeterRegistry registry,
                                         ConnectivityMonitoringProperties properties,
                                         List<DependencyProbe> probes) {
        this.registry = registry;
        this.properties = properties;
        this.probes = List.copyOf(probes);
        this.probeExecutor = newProbeExecutor(this.probes.size());

        for (Dependency dependency : Dependency.values()) {
            states.put(dependency, new DependencyState(dependency, properties.getServiceName(), registry));
        }

        log.info("Connectivity monitoring initialised: service={}, probesEnabled={}, probes={}, "
                        + "failureThreshold={}, probeIntervalMs={}, probeTimeoutMs={}",
                properties.getServiceName(), properties.isEnabled(), probeLabels(),
                properties.getFailureThreshold(), properties.getProbeIntervalMs(), properties.getProbeTimeoutMs());
    }

    /**
     * Probes run off the scheduler thread so that a dependency that has stopped
     * answering cannot hold up the ones that are still healthy, and so that a probe
     * stuck past its deadline can be abandoned rather than waited out.
     */
    private static ExecutorService newProbeExecutor(int probeCount) {
        if (probeCount == 0) {
            return null;
        }
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "connectivity-probe-" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
        return Executors.newFixedThreadPool(probeCount, threadFactory);
    }

    private List<String> probeLabels() {
        List<String> labels = new ArrayList<>(probes.size());
        for (DependencyProbe probe : probes) {
            labels.add(probe.dependency().label());
        }
        return labels;
    }

    // ---- Failure / success recording ----

    /**
     * Records a failure observed while talking to {@code dependency}.
     *
     * <p>Every failure increments {@code dependency_error_count}. Only failures the
     * classifier recognises as transport problems increment the connectivity counters
     * and the consecutive-failure streak that can flip the dependency to DOWN.</p>
     *
     * @param dependency the dependency the call was made against; {@code null} is ignored
     * @param throwable  the failure; {@code null} is ignored
     * @return the classified reason, {@link ConnectivityFailureReason#APPLICATION_ERROR}
     * when it is not a connectivity problem
     */
    public ConnectivityFailureReason recordFailure(Dependency dependency, Throwable throwable) {
        if (dependency == null || throwable == null) {
            return ConnectivityFailureReason.APPLICATION_ERROR;
        }
        try {
            ConnectivityFailureReason reason = ConnectivityFailureClassifier.classify(throwable);
            DependencyState state = states.get(dependency);
            state.errorCounter(registry, reason).increment();

            if (!reason.isConnectivityFailure()) {
                return reason;
            }
            applyConnectivityFailure(state, reason, throwable);
            return reason;
        } catch (Exception e) {
            log.warn("Failed to record connectivity failure for {}: {}", dependency.label(), e.getMessage());
            return ConnectivityFailureReason.APPLICATION_ERROR;
        }
    }

    /**
     * Records a failure whose dependency is not known to the caller, attributing it by
     * the packages its exception chain passes through. This is how failures seen while
     * serving requests reach connectivity monitoring without every call site having to
     * name the dependency it was talking to.
     *
     * @param throwable the failure; {@code null}, or one that points at no dependency, is ignored
     * @return the dependency the failure was attributed to, or {@code null} if none
     */
    public Dependency recordThrowable(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        Dependency dependency = DependencyResolver.resolve(throwable);
        if (dependency != null) {
            recordFailure(dependency, throwable);
        }
        return dependency;
    }

    /**
     * Records a successful interaction with {@code dependency}: clears the failure
     * streak and, if the dependency was DOWN, marks it recovered.
     *
     * @param dependency the dependency that answered; {@code null} is ignored
     */
    public void recordSuccess(Dependency dependency) {
        if (dependency == null) {
            return;
        }
        try {
            DependencyState state = states.get(dependency);
            long nowMillis = System.currentTimeMillis();
            state.consecutiveFailures.set(0);
            state.lastSuccessEpochSeconds.set(nowMillis / MILLIS_PER_SECOND);

            if (state.up.compareAndSet(0L, 1L)) {
                long downSince = state.downSinceEpochMillis.getAndSet(0L);
                long outageMillis = downSince > 0 ? nowMillis - downSince : 0L;
                state.outageDurationTimer.record(outageMillis, TimeUnit.MILLISECONDS);
                log.info("Connectivity RESTORED for {} after {} ms of downtime", dependency.label(), outageMillis);
            }
        } catch (Exception e) {
            log.warn("Failed to record connectivity success for {}: {}", dependency.label(), e.getMessage());
        }
    }

    private void applyConnectivityFailure(DependencyState state, ConnectivityFailureReason reason, Throwable throwable) {
        long nowMillis = System.currentTimeMillis();
        state.connectivityFailureCounter(registry, reason).increment();
        state.connectivityFailureTotal.incrementAndGet();
        state.dailyConnectivityFailureCount.incrementAndGet();
        state.lastFailureEpochSeconds.set(nowMillis / MILLIS_PER_SECOND);
        state.lastReason = reason;

        int consecutive = state.consecutiveFailures.incrementAndGet();
        if (consecutive >= properties.getFailureThreshold() && state.up.compareAndSet(1L, 0L)) {
            state.downSinceEpochMillis.set(nowMillis);
            state.outageCounter.increment();
            log.error("Connectivity DOWN for {} after {} consecutive failures, reason={}",
                    state.dependency.label(), consecutive, reason.label(), throwable);
        } else {
            log.warn("Connectivity failure for {}, reason={}, consecutiveFailures={}, cause={}",
                    state.dependency.label(), reason.label(), consecutive, throwable.toString());
        }
    }

    // ---- Active probes ----

    /**
     * Pings every configured dependency so outages and recoveries surface in Grafana
     * regardless of traffic.
     *
     * <p>All probes are started together and then collected, each against its own
     * deadline, so one dead dependency delays neither the others nor the next run.
     * {@code fixedDelay} means the next round starts a full interval after this one
     * finishes, which is what keeps probes from stacking up behind an outage.</p>
     */
    @Scheduled(fixedDelayString = "${connectivity.probe-interval-ms:15000}",
            initialDelayString = "${connectivity.probe-interval-ms:15000}")
    public void probeDependencies() {
        if (!properties.isEnabled() || probes.isEmpty() || shuttingDown) {
            return;
        }
        List<ProbeRun> runs = new ArrayList<>(probes.size());
        for (DependencyProbe probe : probes) {
            long startNanos = System.nanoTime();
            runs.add(new ProbeRun(probe, startNanos, probeExecutor.submit(() -> {
                probe.probe();
                return null;
            })));
        }
        for (ProbeRun run : runs) {
            collect(run);
        }
    }

    private void collect(ProbeRun run) {
        Dependency dependency = run.probe().dependency();
        long timeoutMs = properties.getProbeTimeoutMs();
        long waitedMs = (System.nanoTime() - run.startNanos()) / 1_000_000L;
        try {
            run.future().get(Math.max(0, timeoutMs - waitedMs), TimeUnit.MILLISECONDS);
            onProbeSuccess(dependency, run.startNanos());
        } catch (TimeoutException e) {
            run.future().cancel(true);
            onProbeFailure(dependency, run.startNanos(),
                    new TimeoutException("Connectivity probe for " + dependency.label()
                            + " timed out after " + timeoutMs + "ms"));
        } catch (ExecutionException e) {
            onProbeFailure(dependency, run.startNanos(), e.getCause() == null ? e : e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            run.future().cancel(true);
            onProbeFailure(dependency, run.startNanos(), e);
        }
    }

    private void onProbeSuccess(Dependency dependency, long startNanos) {
        recordProbeLatency(dependency, startNanos, OUTCOME_SUCCESS);
        recordSuccess(dependency);
        log.debug("Connectivity probe succeeded for {}", dependency.label());
    }

    private void onProbeFailure(Dependency dependency, long startNanos, Throwable error) {
        try {
            recordProbeLatency(dependency, startNanos, OUTCOME_FAILURE);
            // A probe that cannot complete is a connectivity failure by definition, even when
            // the classifier cannot name the transport fault behind it.
            ConnectivityFailureReason reason = ConnectivityFailureClassifier.classify(error);
            if (!reason.isConnectivityFailure()) {
                reason = ConnectivityFailureReason.SERVICE_UNAVAILABLE;
            }
            DependencyState state = states.get(dependency);
            state.errorCounter(registry, reason).increment();
            applyConnectivityFailure(state, reason, error);
        } catch (Exception e) {
            log.warn("Failed to record probe failure for {}: {}", dependency.label(), e.getMessage());
        }
    }

    private void recordProbeLatency(Dependency dependency, long startNanos, String outcome) {
        Timer.builder(METRIC_PROBE_LATENCY)
                .description("Latency of the connectivity probe against each dependency")
                .tag(TAG_SERVICE, properties.getServiceName())
                .tag(TAG_DEPENDENCY, dependency.label())
                .tag(TAG_OUTCOME, outcome)
                .register(registry)
                .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }

    /** Resets the 24-hour connectivity failure counters at midnight; lifetime counters are untouched. */
    @Scheduled(cron = "${connectivity.daily-reset-cron:0 0 0 * * *}")
    public void resetDailyCounters() {
        for (DependencyState state : states.values()) {
            log.info("Resetting daily connectivity failure count for {}. Previous count: {}",
                    state.dependency.label(), state.dailyConnectivityFailureCount.get());
            state.dailyConnectivityFailureCount.set(0);
        }
    }

    @PreDestroy
    public void shutdown() {
        shuttingDown = true;
        if (probeExecutor != null) {
            probeExecutor.shutdownNow();
            try {
                if (!probeExecutor.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    log.warn("Connectivity probe executor did not terminate within {}s",
                            EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        for (DependencyProbe probe : probes) {
            if (probe instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception e) {
                    log.warn("Failed to close connectivity probe for {}: {}",
                            probe.dependency().label(), e.getMessage());
                }
            }
        }
    }

    // ---- Read model ----

    /** Immutable view of one dependency's connectivity state, used by the REST endpoint and tests. */
    public record DependencyStatus(
            String dependency,
            boolean up,
            int consecutiveFailures,
            long connectivityFailureCount,
            long dailyConnectivityFailureCount,
            long outageCount,
            long downtimeSeconds,
            long lastFailureEpochSeconds,
            long lastSuccessEpochSeconds,
            String lastFailureReason) {}

    /** Snapshot of every tracked dependency, keyed by label, in declaration order. */
    public Map<String, DependencyStatus> snapshot() {
        Map<String, DependencyStatus> out = LinkedHashMap.newLinkedHashMap(states.size());
        for (DependencyState state : states.values()) {
            out.put(state.dependency.label(), state.toStatus());
        }
        return Collections.unmodifiableMap(out);
    }

    /** {@code true} when the dependency is currently considered reachable. */
    public boolean isUp(Dependency dependency) {
        return dependency != null && states.get(dependency).up.get() == 1L;
    }

    /** {@code true} when every tracked dependency is reachable. */
    public boolean allUp() {
        for (DependencyState state : states.values()) {
            if (state.up.get() != 1L) {
                return false;
            }
        }
        return true;
    }

    /** One probe in flight: which probe, when it started, and the task to collect. */
    private record ProbeRun(DependencyProbe probe, long startNanos, Future<Void> future) {}

    /**
     * Per-dependency state plus the meters that expose it. Counters are created lazily
     * per reason so Prometheus only carries series that actually occurred.
     */
    private static final class DependencyState {

        private final Dependency dependency;
        private final String serviceName;
        private final AtomicLong up = new AtomicLong(1L);
        private final AtomicInteger consecutiveFailures = new AtomicInteger();
        private final AtomicLong dailyConnectivityFailureCount = new AtomicLong();
        private final AtomicLong lastFailureEpochSeconds = new AtomicLong();
        private final AtomicLong lastSuccessEpochSeconds = new AtomicLong();
        private final AtomicLong downSinceEpochMillis = new AtomicLong();
        private final AtomicLong connectivityFailureTotal = new AtomicLong();

        private final Counter outageCounter;
        private final Timer outageDurationTimer;
        private final ConcurrentMap<ConnectivityFailureReason, Counter> connectivityFailureCounters =
                new ConcurrentHashMap<>();
        private final ConcurrentMap<ConnectivityFailureReason, Counter> errorCounters = new ConcurrentHashMap<>();

        private volatile ConnectivityFailureReason lastReason;

        private DependencyState(Dependency dependency, String serviceName, MeterRegistry registry) {
            this.dependency = dependency;
            this.serviceName = serviceName;
            String label = dependency.label();

            Gauge.builder(METRIC_UP, up, AtomicLong::get)
                    .description("Whether the dependency is currently reachable (1 = up, 0 = down)")
                    .tag(TAG_SERVICE, serviceName)
                    .tag(TAG_DEPENDENCY, label)
                    .register(registry);

            Gauge.builder(METRIC_CONSECUTIVE, consecutiveFailures, AtomicInteger::get)
                    .description("Consecutive connectivity failures since the last success")
                    .tag(TAG_SERVICE, serviceName)
                    .tag(TAG_DEPENDENCY, label)
                    .register(registry);

            Gauge.builder(METRIC_FAILURE_DAILY, dailyConnectivityFailureCount, AtomicLong::get)
                    .description("Connectivity failures in the current 24-hour window (resets at 00:00)")
                    .tag(TAG_SERVICE, serviceName)
                    .tag(TAG_DEPENDENCY, label)
                    .register(registry);

            Gauge.builder(METRIC_LAST_FAILURE, lastFailureEpochSeconds, AtomicLong::get)
                    .description("Epoch seconds of the most recent connectivity failure (0 = none since startup)")
                    .tag(TAG_SERVICE, serviceName)
                    .tag(TAG_DEPENDENCY, label)
                    .register(registry);

            Gauge.builder(METRIC_LAST_SUCCESS, lastSuccessEpochSeconds, AtomicLong::get)
                    .description("Epoch seconds of the most recent successful interaction (0 = none since startup)")
                    .tag(TAG_SERVICE, serviceName)
                    .tag(TAG_DEPENDENCY, label)
                    .register(registry);

            Gauge.builder(METRIC_DOWNTIME, this, DependencyState::currentDowntimeSeconds)
                    .description("Duration in seconds of the outage currently in progress (0 when up)")
                    .tag(TAG_SERVICE, serviceName)
                    .tag(TAG_DEPENDENCY, label)
                    .register(registry);

            this.outageCounter = Counter.builder(METRIC_OUTAGE)
                    .description("Number of times the dependency transitioned from up to down")
                    .tag(TAG_SERVICE, serviceName)
                    .tag(TAG_DEPENDENCY, label)
                    .register(registry);

            this.outageDurationTimer = Timer.builder(METRIC_OUTAGE_DURATION)
                    .description("Duration of each completed outage, recorded on recovery")
                    .tag(TAG_SERVICE, serviceName)
                    .tag(TAG_DEPENDENCY, label)
                    .register(registry);
        }

        private Counter connectivityFailureCounter(MeterRegistry registry, ConnectivityFailureReason reason) {
            return connectivityFailureCounters.computeIfAbsent(reason, r -> Counter.builder(METRIC_CONNECTIVITY_FAILURE)
                    .description("Connectivity failures against the dependency, broken down by reason")
                    .tag(TAG_SERVICE, serviceName)
                    .tag(TAG_DEPENDENCY, dependency.label())
                    .tag(TAG_REASON, r.label())
                    .register(registry));
        }

        private Counter errorCounter(MeterRegistry registry, ConnectivityFailureReason reason) {
            return errorCounters.computeIfAbsent(reason, r -> Counter.builder(METRIC_ERROR)
                    .description("All failures against the dependency, connectivity related or not")
                    .tag(TAG_SERVICE, serviceName)
                    .tag(TAG_DEPENDENCY, dependency.label())
                    .tag(TAG_REASON, r.label())
                    .register(registry));
        }

        private double currentDowntimeSeconds() {
            long downSince = downSinceEpochMillis.get();
            if (up.get() == 1L || downSince <= 0L) {
                return 0.0;
            }
            return (System.currentTimeMillis() - downSince) / (double) MILLIS_PER_SECOND;
        }

        private DependencyStatus toStatus() {
            ConnectivityFailureReason reason = lastReason;
            return new DependencyStatus(
                    dependency.label(),
                    up.get() == 1L,
                    consecutiveFailures.get(),
                    connectivityFailureTotal.get(),
                    dailyConnectivityFailureCount.get(),
                    (long) outageCounter.count(),
                    (long) currentDowntimeSeconds(),
                    lastFailureEpochSeconds.get(),
                    lastSuccessEpochSeconds.get(),
                    reason == null ? null : reason.label());
        }
    }
}
