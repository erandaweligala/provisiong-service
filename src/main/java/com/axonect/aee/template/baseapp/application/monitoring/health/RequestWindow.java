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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * A sliding window over the cumulative request and 5xx counts of one endpoint.
 *
 * <p>Micrometer's timers only ever count up, so "how many requests failed in the
 * last five minutes" is the difference between the counts now and the counts as
 * they were five minutes ago. This keeps just enough samples to answer that: the
 * latest one, and the oldest one still at or before the start of the window.</p>
 *
 * <p>Not thread safe. One instance per endpoint, touched only from the evaluation
 * schedule.</p>
 */
final class RequestWindow {

    private record Sample(long at, long requests, long errors) {
    }

    private final long windowMs;
    private final Deque<Sample> samples = new ArrayDeque<>();

    RequestWindow(long windowMs) {
        this.windowMs = windowMs;
    }

    /**
     * Records the cumulative counts as of {@code now} and discards samples that have
     * fallen out of the window.
     *
     * <p>The sample that has just left the window is dropped only once a newer one is
     * also old enough to replace it as the baseline, so the window always spans at
     * least {@code windowMs} rather than briefly collapsing to nothing.</p>
     */
    void observe(long now, long cumulativeRequests, long cumulativeErrors) {
        samples.addLast(new Sample(now, cumulativeRequests, cumulativeErrors));

        long cutoff = now - windowMs;
        while (samples.size() > 2) {
            Iterator<Sample> iterator = samples.iterator();
            iterator.next();
            if (iterator.next().at() > cutoff) {
                break;
            }
            samples.removeFirst();
        }
    }

    /** Requests served over the window. Zero until a second sample has been taken. */
    long requests() {
        return delta(Sample::requests);
    }

    /** Of those, how many answered 5xx. */
    long errors() {
        return delta(Sample::errors);
    }

    /** The 5xx share of the window, 0 when there was no traffic. */
    double errorRatio() {
        long requests = requests();
        return requests <= 0 ? 0 : (double) errors() / requests;
    }

    /** How long the window currently spans, in milliseconds. */
    long spanMs() {
        return samples.size() < 2 ? 0 : samples.getLast().at() - samples.getFirst().at();
    }

    /**
     * A meter that disappears and comes back - a registry cleared by a
     * {@code MeterFilter}, a re-registration - would make the counts go backwards.
     * There is no sensible delta to report in that case, so it reads as no traffic
     * until the window has refilled.
     */
    private long delta(java.util.function.ToLongFunction<Sample> field) {
        if (samples.size() < 2) {
            return 0;
        }
        long difference = field.applyAsLong(samples.getLast()) - field.applyAsLong(samples.getFirst());
        return Math.max(difference, 0);
    }
}
