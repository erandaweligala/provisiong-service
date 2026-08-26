package com.axonect.aee.template.baseapp.application.monitoring.responsetime;

import io.prometheus.client.exemplars.Exemplar;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The sampler is the only thing standing between a bucket count and a real
 * request, and every way it can fail is quiet: a null where an exemplar was
 * expected leaves the dashboard looking like a service nobody called, and a
 * stale request id points an investigation at the wrong request entirely.
 */
class RequestExemplarSamplerTest {

    private static final double THREE_HUNDRED_MS = 0.3d;

    @AfterEach
    void clearRequestId() {
        RequestIdHolder.clear();
    }

    @Test
    void carriesTheObservedDurationAndTheRequestThatProducedIt() {
        RequestIdHolder.set("11111111-2222-3333-4444-555555555555");
        RequestExemplarSampler sampler = new RequestExemplarSampler(0L, () -> 1_700_000_000_000L);

        Exemplar exemplar = sampler.sample(THREE_HUNDRED_MS, 0.2d, 0.5d, null);

        assertNotNull(exemplar);
        assertEquals(THREE_HUNDRED_MS, exemplar.getValue());
        assertEquals(1_700_000_000_000L, exemplar.getTimestampMs());
        assertEquals("11111111-2222-3333-4444-555555555555", labelValue(exemplar, "trace_id"));
        assertEquals("11111111-2222-3333-4444-555555555555", labelValue(exemplar, "span_id"));
    }

    /**
     * Connectivity probes and scheduled work record timers on their own threads.
     * There is no request behind those, so there is nothing for an exemplar to
     * name - and inventing one would attach a request id to a measurement that
     * did not come from that request.
     */
    @Test
    void declinesToSampleOffARequestThread() {
        RequestExemplarSampler sampler = new RequestExemplarSampler(0L);

        assertNull(sampler.sample(THREE_HUNDRED_MS, 0.2d, 0.5d, null));
        assertNull(sampler.sample(1.0d, null));
    }

    /**
     * The default: every request replaces the exemplar for its bucket, so a scrape
     * reports the most recent real request in each latency band rather than
     * whichever one happened to arrive first after the last sample.
     */
    @Test
    void zeroRetentionLetsEveryRequestReplaceThePrevious() {
        AtomicLong now = new AtomicLong(1_000L);
        RequestExemplarSampler sampler = new RequestExemplarSampler(0L, now::get);

        RequestIdHolder.set("first");
        Exemplar first = sampler.sample(THREE_HUNDRED_MS, 0.2d, 0.5d, null);

        now.set(1_001L);
        RequestIdHolder.set("second");
        Exemplar second = sampler.sample(0.31d, 0.2d, 0.5d, first);

        assertNotNull(second);
        assertEquals("second", labelValue(second, "trace_id"));
    }

    @Test
    void retentionKeepsThePreviousExemplarUntilTheIntervalHasPassed() {
        AtomicLong now = new AtomicLong(1_000L);
        RequestExemplarSampler sampler = new RequestExemplarSampler(7_000L, now::get);

        RequestIdHolder.set("first");
        Exemplar first = sampler.sample(THREE_HUNDRED_MS, 0.2d, 0.5d, null);
        assertNotNull(first);

        now.set(5_000L);
        RequestIdHolder.set("too-soon");
        assertNull(sampler.sample(0.31d, 0.2d, 0.5d, first),
                "null means the bucket keeps the exemplar it already has");

        now.set(8_001L);
        RequestIdHolder.set("late-enough");
        Exemplar third = sampler.sample(0.32d, 0.2d, 0.5d, first);
        assertNotNull(third);
        assertEquals("late-enough", labelValue(third, "trace_id"));
    }

    /** A negative interval is a configuration slip, not a licence to compare against it. */
    @Test
    void negativeRetentionIsTreatedAsZero() {
        RequestIdHolder.set("only-request");
        RequestExemplarSampler sampler = new RequestExemplarSampler(-1L, () -> 1_000L);

        Exemplar previous = new Exemplar(0.29d, 1_000L, "span_id", "older", "trace_id", "older");
        Exemplar sampled = sampler.sample(THREE_HUNDRED_MS, 0.2d, 0.5d, previous);

        assertNotNull(sampled);
        assertEquals("only-request", labelValue(sampled, "trace_id"));
    }

    /** The counter overload records the increment, and must not recurse into itself. */
    @Test
    void countersAreSampledWithTheirIncrement() {
        RequestIdHolder.set("counted");
        RequestExemplarSampler sampler = new RequestExemplarSampler(0L, () -> 1_000L);

        Exemplar exemplar = sampler.sample(1.0d, null);

        assertNotNull(exemplar);
        assertEquals(1.0d, exemplar.getValue());
        assertEquals("counted", labelValue(exemplar, "trace_id"));
    }

    /** The id is per thread: one request must never be labelled with another's. */
    @Test
    void requestIdDoesNotLeakBetweenThreads() throws Exception {
        RequestIdHolder.set("main-thread-request");
        RequestExemplarSampler sampler = new RequestExemplarSampler(0L, () -> 1_000L);

        Exemplar[] fromOtherThread = new Exemplar[1];
        Thread other = new Thread(() -> fromOtherThread[0] = sampler.sample(THREE_HUNDRED_MS, 0.2d, 0.5d, null));
        other.start();
        other.join();

        assertNull(fromOtherThread[0]);
        assertEquals("main-thread-request", RequestIdHolder.get());
    }

    private static String labelValue(Exemplar exemplar, String name) {
        for (int i = 0; i < exemplar.getNumberOfLabels(); i++) {
            if (name.equals(exemplar.getLabelName(i))) {
                return exemplar.getLabelValue(i);
            }
        }
        return null;
    }
}
