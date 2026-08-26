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
package com.axonect.aee.template.baseapp.application.monitoring.health;

import com.axonect.aee.template.baseapp.application.monitoring.ApiEndpointRegistry;
import com.axonect.aee.template.baseapp.application.monitoring.MonitoredEndpoint;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Decides, every {@code monitoring.api.health.evaluation-interval-ms}, whether each
 * catalogued REST endpoint is healthy, and publishes that verdict to Prometheus.
 *
 * <p>This is the answer to the question traffic-derived availability cannot answer.
 * Availability is a ratio of requests, so an endpoint nobody called reads 100% -
 * true, and useless: it says nothing failed, not that anything works. Health starts
 * from what can be checked without a request, and only then looks at traffic:</p>
 *
 * <pre>
 *   handler mapped here?  --\
 *   required deps up?     ---&gt;  EndpointHealthEvaluator  --&gt;  api_endpoint_health
 *   5xx ratio in window?  --/
 * </pre>
 *
 * <p>None of the three costs anything new. The mapping check reads Spring's own
 * handler mapping once at startup, the dependency check reuses the connectivity
 * probes that already run, and the error ratio is read off the same
 * {@code http.server.requests} timer Spring Boot publishes - sampled here
 * as a sliding window rather than a Prometheus rate, so the verdict is available
 * inside the JVM and on {@code GET /monitoring/endpoints} without a round trip to
 * Prometheus.</p>
 *
 * <h2>Metrics</h2>
 * <table>
 *   <caption>Meters registered per endpoint, all tagged {@code api} (and {@code microservice} from the common tags)</caption>
 *   <tr><td>{@code api_endpoint_health}</td><td>gauge, 3 healthy / 2 degraded / 1 unhealthy / 0 unknown</td></tr>
 *   <tr><td>{@code api_endpoint_health_reason}</td><td>gauge by {@code reason}, 1 for the active reason</td></tr>
 *   <tr><td>{@code api_endpoint_mapped}</td><td>gauge, 1 when a handler exists in this instance</td></tr>
 *   <tr><td>{@code api_endpoint_requests_window}</td><td>gauge, requests over the health window</td></tr>
 *   <tr><td>{@code api_endpoint_errors_window}</td><td>gauge, of those, 5xx</td></tr>
 *   <tr><td>{@code api_endpoint_error_ratio}</td><td>gauge, 0..1 over the window</td></tr>
 *   <tr><td>{@code api_endpoint_unhealthy_seconds}</td><td>gauge, length of the outage in progress</td></tr>
 *   <tr><td>{@code api_endpoint_health_transitions_total}</td><td>counter by {@code to}, one per state change</td></tr>
 *   <tr><td>{@code api_endpoint_last_failure_timestamp_seconds}</td><td>gauge, epoch seconds of the last 5xx</td></tr>
 *   <tr><td>{@code api_endpoint_dependency_required}</td><td>gauge, constant 1 per declared dependency</td></tr>
 * </table>
 */
@Slf4j
public class EndpointHealthMonitor {

    private static final String METRIC_HEALTH = "api.endpoint.health";
    private static final String METRIC_REASON = "api.endpoint.health.reason";
    private static final String METRIC_MAPPED = "api.endpoint.mapped";
    private static final String METRIC_REQUESTS = "api.endpoint.requests.window";
    private static final String METRIC_ERRORS = "api.endpoint.errors.window";
    private static final String METRIC_ERROR_RATIO = "api.endpoint.error.ratio";
    private static final String METRIC_UNHEALTHY_SECONDS = "api.endpoint.unhealthy.seconds";
    private static final String METRIC_TRANSITIONS = "api.endpoint.health.transitions";
    private static final String METRIC_LAST_FAILURE = "api.endpoint.last.failure.timestamp.seconds";
    private static final String METRIC_DEPENDENCY = "api.endpoint.dependency.required";

    private static final String REQUEST_METER = "http.server.requests";
    private static final String TAG_API = "api";
    private static final String TAG_REASON = "reason";
    private static final String TAG_DEPENDENCY = "dependency";
    private static final String TAG_TO = "to";
    private static final String TAG_STATUS = "status";
    private static final String SERVER_ERROR_PREFIX = "5";
    private static final int MILLIS_PER_SECOND = 1000;

    private final MeterRegistry registry;
    private final EndpointHealthProperties properties;
    private final EndpointHealthEvaluator evaluator;
    private final DependencyHealthSource dependencyHealth;
    private final Supplier<Set<String>> liveMappings;
    private final ApiEndpointRegistry catalog;
    private final Map<String, EndpointState> states = new LinkedHashMap<>();

    /** Null until the first evaluation resolves the handler mappings. */
    private volatile Boolean mappingsVerified;

    public EndpointHealthMonitor(MeterRegistry registry,
                                 ApiEndpointRegistry catalog,
                                 EndpointHealthProperties properties,
                                 DependencyHealthSource dependencyHealth,
                                 Supplier<Set<String>> liveMappings) {
        this.registry = registry;
        this.properties = properties;
        this.evaluator = new EndpointHealthEvaluator(properties);
        this.dependencyHealth = dependencyHealth;
        this.liveMappings = liveMappings;
        this.catalog = catalog;

        for (MonitoredEndpoint endpoint : catalog.endpoints()) {
            states.put(endpoint.getName(), new EndpointState(endpoint, properties.getWindowMs(), registry));
        }
        log.info("REST endpoint health monitoring initialised: {} endpoint(s), intervalMs={}, windowMs={}, "
                        + "degradedRatio={}, unhealthyRatio={}, minimumRequests={}, useDependencyState={}",
                states.size(), properties.getEvaluationIntervalMs(), properties.getWindowMs(),
                properties.getDegradedErrorRatio(), properties.getUnhealthyErrorRatio(),
                properties.getMinimumRequests(), properties.isUseDependencyState());
    }

    // ---- Evaluation ----

    /**
     * Recomputes every endpoint's health.
     *
     * <p>Scheduled rather than computed on scrape so that the state machine behind
     * {@code api_endpoint_unhealthy_seconds} and the transition counter advances on a
     * clock of its own: a metric that only moves when Prometheus asks would stall the
     * moment scraping does, which is exactly when the state matters.</p>
     */
    @Scheduled(fixedDelayString = "${monitoring.api.health.evaluation-interval-ms:15000}",
            initialDelayString = "${monitoring.api.health.evaluation-interval-ms:15000}")
    public void evaluate() {
        try {
            verifyMappingsOnce();
            long now = System.currentTimeMillis();
            for (EndpointState state : states.values()) {
                evaluateOne(state, now);
            }
        } catch (Exception e) {
            log.warn("Endpoint health evaluation failed: {}", e.getMessage(), e);
        }
    }

    private void evaluateOne(EndpointState state, long now) {
        Counts counts = countsFor(state.name());
        state.window.observe(now, counts.requests(), counts.errors());

        List<String> down = dependenciesDown(state);
        EndpointHealthVerdict verdict = evaluator.evaluate(
                state.mapped.get() == 1, down, state.window.requests(), state.window.errors());

        state.apply(verdict, down, now, registry);
    }

    /**
     * Reads the cumulative request and 5xx counts of one endpoint straight off the
     * timers Spring Boot already publishes, rather than instrumenting the request path
     * a second time. Every {@code status} the endpoint has answered is a separate
     * timer, so this sums across them.
     */
    private Counts countsFor(String api) {
        Collection<Timer> timers = registry.find(REQUEST_METER).tag(TAG_API, api).timers();
        long requests = 0;
        long errors = 0;
        for (Timer timer : timers) {
            long count = timer.count();
            requests += count;
            String status = timer.getId().getTag(TAG_STATUS);
            if (status != null && status.startsWith(SERVER_ERROR_PREFIX)) {
                errors += count;
            }
        }
        return new Counts(requests, errors);
    }

    private List<String> dependenciesDown(EndpointState state) {
        if (!properties.isUseDependencyState() || state.dependencies.isEmpty()) {
            return List.of();
        }
        List<String> down = new ArrayList<>(1);
        for (String dependency : state.dependencies) {
            if (!dependencyHealth.isUp(dependency)) {
                down.add(dependency);
            }
        }
        return down;
    }

    /**
     * Resolves which catalogued endpoints this instance actually serves, once.
     *
     * <p>Done on the first evaluation rather than at construction because the handler
     * mappings are not populated until the web context is refreshed, which happens
     * after this bean is built. When the check is turned off, or the mappings cannot
     * be read, every endpoint is treated as mapped - an unverifiable check must not
     * invent an outage.</p>
     */
    private void verifyMappingsOnce() {
        if (mappingsVerified != null) {
            return;
        }
        if (!properties.isVerifyMappings()) {
            markAllMapped("mapping verification is disabled");
            return;
        }
        Set<String> mappings;
        try {
            mappings = liveMappings.get();
        } catch (Exception e) {
            log.warn("Could not read the live handler mappings, treating every endpoint as mapped: {}",
                    e.getMessage());
            markAllMapped("handler mappings unavailable");
            return;
        }
        if (mappings == null || mappings.isEmpty()) {
            markAllMapped("no handler mappings were reported");
            return;
        }

        Set<String> mapped = new EndpointMappingVerifier(mappings).mappedEndpoints(catalog);
        for (EndpointState state : states.values()) {
            state.mapped.set(mapped.contains(state.name()) ? 1 : 0);
        }
        mappingsVerified = true;
        log.info("Endpoint mapping check: {} of {} catalogued endpoint(s) are mapped in this instance",
                mapped.size(), states.size());
    }

    private void markAllMapped(String why) {
        states.values().forEach(state -> state.mapped.set(1));
        mappingsVerified = true;
        log.info("Endpoint mapping check skipped ({}); every catalogued endpoint is treated as mapped", why);
    }

    // ---- Read side ----

    /** Current health of every catalogued endpoint, keyed by the {@code api} label. */
    public Map<String, EndpointHealthStatus> snapshot() {
        Map<String, EndpointHealthStatus> snapshot = new LinkedHashMap<>();
        states.forEach((name, state) -> snapshot.put(name, state.toStatus()));
        return snapshot;
    }

    /** The worst state any endpoint is in - what a single yes/no check should key off. */
    public EndpointHealth worstHealth() {
        EndpointHealth worst = EndpointHealth.HEALTHY;
        for (EndpointState state : states.values()) {
            EndpointHealth health = state.verdict.get().health();
            if (health.isWorseThan(worst)) {
                worst = health;
            }
        }
        return worst;
    }

    /** @return true when no endpoint is unhealthy or degraded. */
    public boolean allHealthy() {
        return worstHealth() == EndpointHealth.HEALTHY;
    }

    private record Counts(long requests, long errors) {
    }

    /**
     * Everything tracked for one endpoint, and the gauges that expose it.
     *
     * <p>The gauges are registered once here and read from these fields on every
     * scrape, which is the contract Micrometer expects: a gauge is a window onto a
     * value that something else maintains, not a value that is set. Registering them
     * up front is also what makes an endpoint appear on the dashboard before its
     * first request - and, for {@code api_endpoint_health_reason}, what makes every
     * reason a continuous 1/0 series instead of one that appears and vanishes as the
     * reason changes.</p>
     */
    private static final class EndpointState {

        private final MonitoredEndpoint endpoint;
        private final List<String> dependencies;
        private final RequestWindow window;

        private final AtomicReference<EndpointHealthVerdict> verdict =
                new AtomicReference<>(EndpointHealthVerdict.UNKNOWN);
        private final AtomicReference<List<String>> down = new AtomicReference<>(List.of());
        private final AtomicInteger mapped = new AtomicInteger(1);
        private final AtomicLong requests = new AtomicLong();
        private final AtomicLong errors = new AtomicLong();
        private final AtomicLong errorRatioMillis = new AtomicLong();
        private final AtomicLong unhealthySince = new AtomicLong();
        private final AtomicLong unhealthySeconds = new AtomicLong();
        private final AtomicLong lastFailure = new AtomicLong();

        private EndpointState(MonitoredEndpoint endpoint, long windowMs, MeterRegistry registry) {
            this.endpoint = endpoint;
            this.dependencies = List.copyOf(endpoint.getDependencies());
            this.window = new RequestWindow(windowMs);
            registerGauges(registry);
        }

        private String name() {
            return endpoint.getName();
        }

        private String title() {
            return endpoint.getTitle() == null ? endpoint.getName() : endpoint.getTitle();
        }

        private String method() {
            return endpoint.getMethod() == null ? "UNKNOWN" : endpoint.getMethod();
        }

        private void registerGauges(MeterRegistry registry) {
            Gauge.builder(METRIC_HEALTH, () -> verdict.get().health().code())
                    .description("Endpoint health: 3 healthy, 2 degraded, 1 unhealthy, 0 unknown")
                    .tag(TAG_API, name())
                    .tag("title", title())
                    .tag("method", method())
                    .register(registry);

            for (EndpointHealthReason reason : EndpointHealthReason.values()) {
                Gauge.builder(METRIC_REASON, () -> verdict.get().reason() == reason ? 1 : 0)
                        .description("1 for the reason the endpoint is in its current state, 0 for the others")
                        .tag(TAG_API, name())
                        .tag(TAG_REASON, reason.label())
                        .register(registry);
            }

            Gauge.builder(METRIC_MAPPED, mapped::get)
                    .description("1 when a handler for this endpoint is mapped in this instance")
                    .tag(TAG_API, name())
                    .register(registry);

            Gauge.builder(METRIC_REQUESTS, requests::get)
                    .description("Requests served by this endpoint over the health window")
                    .tag(TAG_API, name())
                    .register(registry);

            Gauge.builder(METRIC_ERRORS, errors::get)
                    .description("Of those, how many answered 5xx")
                    .tag(TAG_API, name())
                    .register(registry);

            Gauge.builder(METRIC_ERROR_RATIO, () -> errorRatioMillis.get() / 1000d)
                    .description("5xx share of this endpoint's traffic over the health window")
                    .tag(TAG_API, name())
                    .register(registry);

            Gauge.builder(METRIC_UNHEALTHY_SECONDS, unhealthySeconds::get)
                    .description("Length of the unhealthy stretch in progress, 0 when the endpoint is not unhealthy")
                    .baseUnit("seconds")
                    .tag(TAG_API, name())
                    .register(registry);

            Gauge.builder(METRIC_LAST_FAILURE, lastFailure::get)
                    .description("Epoch seconds when this endpoint last answered 5xx, 0 when it never has")
                    .baseUnit("seconds")
                    .tag(TAG_API, name())
                    .register(registry);

            for (String dependency : dependencies) {
                Gauge.builder(METRIC_DEPENDENCY, () -> 1)
                        .description("Constant 1 for each dependency this endpoint cannot serve without")
                        .tag(TAG_API, name())
                        .tag(TAG_DEPENDENCY, dependency)
                        .register(registry);
            }
        }

        /** Publishes one evaluation, logging and counting the state change if there is one. */
        private void apply(EndpointHealthVerdict next, List<String> dependenciesDown, long now,
                           MeterRegistry registry) {
            long windowRequests = window.requests();
            long windowErrors = window.errors();
            requests.set(windowRequests);
            errors.set(windowErrors);
            errorRatioMillis.set(Math.round(window.errorRatio() * 1000));
            down.set(List.copyOf(dependenciesDown));
            if (windowErrors > 0) {
                lastFailure.set(now / MILLIS_PER_SECOND);
            }

            EndpointHealthVerdict previous = verdict.getAndSet(next);
            trackUnhealthyStretch(next.health(), now);

            if (previous.health() != next.health()) {
                registry.counter(METRIC_TRANSITIONS, TAG_API, name(), TAG_TO, next.health().label()).increment();
                log(previous, next);
            }
        }

        private void trackUnhealthyStretch(EndpointHealth health, long now) {
            if (health == EndpointHealth.UNHEALTHY) {
                unhealthySince.compareAndSet(0, now);
                unhealthySeconds.set((now - unhealthySince.get()) / MILLIS_PER_SECOND);
            } else {
                unhealthySince.set(0);
                unhealthySeconds.set(0);
            }
        }

        private void log(EndpointHealthVerdict previous, EndpointHealthVerdict next) {
            String message = "Endpoint '{}' is {} (was {}): {} - {}";
            Object[] arguments = {name(), next.health().label(), previous.health().label(),
                    next.reason().label(), next.detail()};
            if (next.health() == EndpointHealth.UNHEALTHY) {
                log.error(message, arguments);
            } else if (next.health() == EndpointHealth.DEGRADED) {
                log.warn(message, arguments);
            } else {
                log.info(message, arguments);
            }
        }

        private EndpointHealthStatus toStatus() {
            EndpointHealthVerdict current = verdict.get();
            long failure = lastFailure.get();
            return new EndpointHealthStatus(
                    title(),
                    method(),
                    List.copyOf(endpoint.getUris()),
                    current.health().label(),
                    current.reason().label(),
                    current.detail(),
                    mapped.get() == 1,
                    requests.get(),
                    errors.get(),
                    errorRatioMillis.get() / 1000d,
                    dependencies,
                    down.get(),
                    unhealthySeconds.get(),
                    failure == 0 ? null : failure);
        }
    }
}
