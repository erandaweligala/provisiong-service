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
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Calls the read-only endpoints in the catalog on a schedule and reports whether
 * each one answered.
 *
 * <p>Request metrics only exist once somebody makes a request, so on a quiet
 * night a broken endpoint looks exactly like an idle one. This probe closes that
 * gap: it drives {@code api_endpoint_up}, which is 1 or 0 for every probed
 * endpoint on every interval regardless of live traffic.</p>
 *
 * <p><strong>Only side effect free reads may be probed.</strong> An endpoint
 * takes part solely when it declares a {@code probe-path}, and a probe is always
 * issued as a {@code GET} - a catalog entry for a POST, PATCH or DELETE that
 * declares a probe path is refused at startup rather than being called.</p>
 */
@Slf4j
public class EndpointAvailabilityProbe {

    static final String UP_GAUGE = "api.endpoint.up";
    static final String DURATION_TIMER = "api.endpoint.probe.duration";
    static final String FAILURE_COUNTER = "api.endpoint.probe.failures";

    private static final int DEFAULT_EXPECTED_STATUS = 200;

    private final ApiMonitoringProperties.Probe settings;
    private final MeterRegistry meterRegistry;
    private final List<ProbeTarget> targets;
    private final HttpClient httpClient;

    private ScheduledExecutorService scheduler;

    public EndpointAvailabilityProbe(ApiMonitoringProperties properties,
                                     ApiEndpointRegistry registry,
                                     MeterRegistry meterRegistry) {
        this.settings = properties.getProbe();
        this.meterRegistry = meterRegistry;
        this.targets = buildTargets(registry);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(settings.getTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @PostConstruct
    void start() {
        if (targets.isEmpty()) {
            log.warn("Endpoint availability probe is enabled but no catalog entry declares a probe-path; "
                    + "no synthetic checks will run");
            return;
        }
        long intervalSeconds = Math.max(1L, settings.getInterval().getSeconds());
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "api-availability-probe");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::probeAll, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("Endpoint availability probe started for {} endpoint(s) against {} every {}s",
                targets.size(), settings.getBaseUrl(), intervalSeconds);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * Runs one round of checks. Package private so a test can drive it without
     * waiting for the scheduler.
     */
    void probeAll() {
        for (ProbeTarget target : targets) {
            try {
                probe(target);
            } catch (Exception ex) {
                // A probe must never kill the scheduler thread, or every later
                // round would silently stop reporting.
                log.error("Availability probe for '{}' failed unexpectedly", target.endpoint().getName(), ex);
                target.up().set(0);
            }
        }
    }

    private void probe(ProbeTarget target) {
        long startNanos = System.nanoTime();
        String outcome;
        boolean up = false;
        try {
            HttpResponse<Void> response = httpClient.send(target.request(), HttpResponse.BodyHandlers.discarding());
            up = target.expectedStatuses().contains(response.statusCode());
            outcome = up ? "ok" : "status_" + response.statusCode();
            if (!up) {
                log.warn("Availability probe for '{}' got HTTP {} from {}, expected one of {}",
                        target.endpoint().getName(), response.statusCode(), target.request().uri(),
                        target.expectedStatuses());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            outcome = "interrupted";
        } catch (Exception ex) {
            outcome = "error";
            log.warn("Availability probe for '{}' could not reach {}: {}",
                    target.endpoint().getName(), target.request().uri(), ex.toString());
        }

        target.up().set(up ? 1 : 0);
        target.duration().record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        if (!up) {
            Counter.builder(FAILURE_COUNTER)
                    .description("Synthetic availability probes that did not get the expected response")
                    .tag(ApiNameObservationConvention.API_TAG, target.endpoint().getName())
                    .tag("reason", outcome)
                    .register(meterRegistry)
                    .increment();
        }
    }

    private List<ProbeTarget> buildTargets(ApiEndpointRegistry registry) {
        List<ProbeTarget> built = new ArrayList<>();
        for (MonitoredEndpoint endpoint : registry.endpoints()) {
            String probePath = endpoint.getProbePath();
            if (probePath == null || probePath.isBlank()) {
                continue;
            }
            String method = endpoint.getMethod() == null ? "" : endpoint.getMethod().trim().toUpperCase(Locale.ROOT);
            if (!"GET".equals(method)) {
                log.error("Refusing to probe '{}': probe-path is set but the endpoint is a {}, and probes must not "
                        + "change state. Remove the probe-path from this entry.", endpoint.getName(), method);
                continue;
            }
            built.add(newTarget(endpoint, probePath));
        }
        return List.copyOf(built);
    }

    private ProbeTarget newTarget(MonitoredEndpoint endpoint, String probePath) {
        String base = settings.getBaseUrl().endsWith("/")
                ? settings.getBaseUrl().substring(0, settings.getBaseUrl().length() - 1)
                : settings.getBaseUrl();
        String path = probePath.startsWith("/") ? probePath : "/" + probePath;

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(settings.getTimeout())
                .header(HttpHeaders.ACCEPT, "application/json")
                .GET();
        if (settings.getAuthorization() != null && !settings.getAuthorization().isBlank()) {
            builder.header(HttpHeaders.AUTHORIZATION, settings.getAuthorization());
        }

        List<Integer> expected = endpoint.getProbeExpectedStatuses().isEmpty()
                ? List.of(DEFAULT_EXPECTED_STATUS)
                : List.copyOf(endpoint.getProbeExpectedStatuses());

        AtomicInteger up = new AtomicInteger(0);
        Gauge.builder(UP_GAUGE, up, AtomicInteger::get)
                .description("1 when the last synthetic probe of the endpoint succeeded, 0 otherwise")
                .tag(ApiNameObservationConvention.API_TAG, endpoint.getName())
                .tag("title", endpoint.getTitle() == null ? endpoint.getName() : endpoint.getTitle())
                .register(meterRegistry);

        Timer duration = Timer.builder(DURATION_TIMER)
                .description("Round trip time of the synthetic availability probe")
                .tag(ApiNameObservationConvention.API_TAG, endpoint.getName())
                .publishPercentileHistogram()
                .register(meterRegistry);

        return new ProbeTarget(endpoint, builder.build(), expected, up, duration);
    }

    /**
     * One prepared check: an immutable request plus the meters it feeds.
     */
    private record ProbeTarget(MonitoredEndpoint endpoint,
                               HttpRequest request,
                               List<Integer> expectedStatuses,
                               AtomicInteger up,
                               Timer duration) {
    }

    /**
     * Exposed for tests: the endpoints this probe will actually call.
     */
    Map<String, URI> plannedProbes() {
        Map<String, URI> planned = new LinkedHashMap<>();
        targets.forEach(target -> planned.put(target.endpoint().getName(), target.request().uri()));
        return planned;
    }
}
