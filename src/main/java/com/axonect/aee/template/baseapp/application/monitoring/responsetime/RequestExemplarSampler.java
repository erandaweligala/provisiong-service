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
package com.axonect.aee.template.baseapp.application.monitoring.responsetime;

import io.prometheus.client.exemplars.Exemplar;
import io.prometheus.client.exemplars.ExemplarSampler;

import java.util.function.LongSupplier;

/**
 * Attaches individual requests to the response time histogram as exemplars.
 *
 * <p>A Prometheus histogram is a set of counters: it can say that eleven
 * requests took between 500ms and 750ms, but the durations themselves are gone
 * the moment they are counted. An exemplar is the exception - a single observed
 * value, its timestamp and a label set, carried alongside a bucket. That is what
 * makes the actual response time of a real request readable in Grafana rather
 * than a percentile interpolated out of bucket boundaries.</p>
 *
 * <p>The label is the request id from {@link RequestIdHolder}, so a slow point on
 * the graph names the request that produced it and can be followed into the logs
 * and into {@code ACTION_LOG}. The names {@code trace_id} and {@code span_id}
 * are the ones Prometheus and Grafana already expect, and are what a datasource
 * configured for exemplars looks for; this service issues no distributed trace,
 * so the request id stands as both.</p>
 *
 * <p>This replaces Prometheus's {@code DefaultExemplarSampler}, whose fixed
 * ~7s retention keeps one exemplar per bucket per seven seconds. That is aimed
 * at trace sampling; here the point is to see requests, so the retention is
 * configurable and defaults to zero - every request replaces the exemplar for
 * its bucket, and each scrape carries the most recent real request in each
 * latency band.</p>
 *
 * <p>What this still cannot do is carry <em>every</em> request: the exposition
 * format allows one exemplar per bucket per scrape, so the ceiling is one real
 * duration per bucket per endpoint per scrape interval. Requests that share a
 * bucket between two scrapes are counted but only the last is named.</p>
 */
public class RequestExemplarSampler implements ExemplarSampler {

    static final String TRACE_ID = "trace_id";
    static final String SPAN_ID = "span_id";

    private final long minRetentionMs;
    private final LongSupplier clock;

    public RequestExemplarSampler(long minRetentionMs) {
        this(minRetentionMs, System::currentTimeMillis);
    }

    RequestExemplarSampler(long minRetentionMs, LongSupplier clock) {
        this.minRetentionMs = Math.max(0L, minRetentionMs);
        this.clock = clock;
    }

    /** Called for the {@code _count} counter of a timer. */
    @Override
    public Exemplar sample(double increment, Exemplar previous) {
        return sampleRequest(increment, previous);
    }

    /** Called for the one histogram bucket the observed duration falls into. */
    @Override
    public Exemplar sample(double value, double bucketFrom, double bucketTo, Exemplar previous) {
        return sampleRequest(value, previous);
    }

    /**
     * @return a new exemplar carrying {@code value} and the id of the request on
     * this thread, or {@code null} to keep whatever exemplar the bucket already
     * holds. {@code null} is the answer for anything that is not an in-flight
     * HTTP request - a scheduled probe, a background task - because there is no
     * request for such a point to name.
     */
    private Exemplar sampleRequest(double value, Exemplar previous) {
        String requestId = RequestIdHolder.get();
        if (requestId == null) {
            return null;
        }
        long now = clock.getAsLong();
        if (!retentionElapsed(previous, now)) {
            return null;
        }
        return new Exemplar(value, now, SPAN_ID, requestId, TRACE_ID, requestId);
    }

    private boolean retentionElapsed(Exemplar previous, long now) {
        if (minRetentionMs == 0L || previous == null || previous.getTimestampMs() == null) {
            return true;
        }
        return now - previous.getTimestampMs() > minRetentionMs;
    }
}
