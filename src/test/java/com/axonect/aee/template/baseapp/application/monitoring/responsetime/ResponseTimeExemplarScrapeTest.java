package com.axonect.aee.template.baseapp.application.monitoring.responsetime;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.common.TextFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End to end, against a real {@link PrometheusMeterRegistry}: that a single
 * request's duration and identity survive all the way into the text Prometheus
 * scrapes.
 *
 * <p>Everything else about this feature can be correct and still produce nothing
 * visible. The sampler can return a perfectly good exemplar that Micrometer
 * discards because the timer has no histogram; the exemplar can be attached and
 * then dropped because the scrape was rendered in the plain text format, which
 * has no syntax for exemplars at all. Both are silent. This test is the one that
 * would catch either, by reading the exposition itself.</p>
 */
class ResponseTimeExemplarScrapeTest {

    /** The content type Prometheus negotiates when exemplar storage is enabled. */
    private static final String OPENMETRICS = TextFormat.CONTENT_TYPE_OPENMETRICS_100;

    /** The older format, which Prometheus falls back to and which has no exemplars. */
    private static final String PLAIN_TEXT = TextFormat.CONTENT_TYPE_004;

    private static final String REQUEST_ID = "11111111-2222-3333-4444-555555555555";

    private PrometheusMeterRegistry registry;

    @BeforeEach
    void createRegistry() {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT, new CollectorRegistry(),
                Clock.SYSTEM, new RequestExemplarSampler(0L));
        // The same buckets application.yml configures via management.metrics.distribution.slo.
        // Exemplars hang off histogram buckets, so a timer without them has nowhere
        // to put one - it would fall into +Inf and name a request as "slower than
        // every bucket", which is true of nothing useful. Note the unit: a Timer's
        // objectives are nanoseconds here, where the yml property takes Durations
        // and converts them.
        registry.config().meterFilter(new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(io.micrometer.core.instrument.Meter.Id id,
                                                         DistributionStatisticConfig config) {
                if (!"http.server.requests".equals(id.getName())) {
                    return config;
                }
                return DistributionStatisticConfig.builder()
                        .serviceLevelObjectives(1e8, 3e8, 5e8, 1e9)
                        .build()
                        .merge(config);
            }
        });
    }

    @AfterEach
    void clearRequestId() {
        RequestIdHolder.clear();
    }

    @Test
    void aRequestsOwnDurationAndIdAppearOnTheHistogramBucket() {
        RequestIdHolder.set(REQUEST_ID);

        timer("get_user").record(Duration.ofMillis(420));

        String scrape = registry.scrape(OPENMETRICS);
        assertTrue(scrape.contains("trace_id=\"" + REQUEST_ID + "\""),
                "the request that produced the duration should be named on the bucket:\n" + scrape);
        assertTrue(scrape.contains("span_id=\"" + REQUEST_ID + "\""), scrape);
        // 420ms lands in the 0.5s bucket, and the exemplar carries the real
        // duration rather than the bucket boundary.
        assertTrue(scrape.contains("le=\"0.5\"} 1.0 # {"), scrape);
        assertTrue(scrape.contains("} 0.42 "), "the exemplar should carry the observed 0.42s:\n" + scrape);
    }

    /**
     * The plain text format Prometheus falls back to has no exemplar syntax. The
     * histogram still scrapes cleanly - which is exactly why a misconfigured
     * scrape looks like a working one until somebody goes looking for a request.
     */
    @Test
    void plainTextScrapeCarriesTheHistogramButNoExemplars() {
        RequestIdHolder.set(REQUEST_ID);

        timer("get_user").record(Duration.ofMillis(420));

        String scrape = registry.scrape(PLAIN_TEXT);
        assertTrue(scrape.contains("http_server_requests_seconds_bucket"));
        assertFalse(scrape.contains(REQUEST_ID),
                "plain text exposition has no exemplars - this is what the OpenMetrics scrape is for");
    }

    /**
     * A timer recorded outside a request - a scheduled probe, a background task -
     * has no request to name, and must not borrow one.
     */
    @Test
    void aDurationRecordedOutsideARequestIsCountedButNotNamed() {
        timer("get_user").record(Duration.ofMillis(420));

        String scrape = registry.scrape(OPENMETRICS);
        assertTrue(scrape.contains("http_server_requests_seconds_bucket"));
        assertFalse(scrape.contains("trace_id="), scrape);
    }

    /** With zero retention the latest request in a bucket is the one reported. */
    @Test
    void theMostRecentRequestInABucketIsTheOneReported() {
        Timer timer = timer("get_user");

        RequestIdHolder.set("earlier-request");
        timer.record(Duration.ofMillis(410));
        RequestIdHolder.set("later-request");
        timer.record(Duration.ofMillis(430));

        String scrape = registry.scrape(OPENMETRICS);
        assertTrue(scrape.contains("trace_id=\"later-request\""), scrape);
        assertFalse(scrape.contains("trace_id=\"earlier-request\""), scrape);
    }

    private Timer timer(String api) {
        return Timer.builder("http.server.requests")
                .tag("api", api)
                .tag("method", "GET")
                .tag("uri", "/api/user/{user_name}")
                .tag("status", "200")
                .tag("outcome", "SUCCESS")
                .register(registry);
    }
}
