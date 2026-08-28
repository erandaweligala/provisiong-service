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

/**
 * How one individual request's response time compares with the two thresholds in
 * {@link ResponseTimeProperties}. Published as the {@code severity} tag of
 * {@code api_slow_requests_total}, so the dashboard can chart a request that was
 * merely slow apart from one that was very slow.
 */
public enum RequestSpeed {

    /** Under the slow threshold. Counted by the timer, and by nothing here. */
    NORMAL("normal"),

    /** At or over {@code slow-request-threshold-ms}. */
    SLOW("slow"),

    /** At or over {@code very-slow-request-threshold-ms}. */
    VERY_SLOW("very_slow");

    private final String label;

    RequestSpeed(String label) {
        this.label = label;
    }

    /** @return the value published as the {@code severity} metric tag. */
    public String label() {
        return label;
    }

    /** @return whether a request this speed is worth a counter and a WARN line. */
    public boolean isSlow() {
        return this != NORMAL;
    }

    /**
     * Classifies a measured duration.
     *
     * <p>A very slow threshold that has been configured below the slow one cannot
     * split the two apart, so the slow threshold wins and nothing reports as very
     * slow - a misconfiguration should lose detail, not invent it.</p>
     */
    public static RequestSpeed of(long durationMs, long slowMs, long verySlowMs) {
        if (durationMs >= verySlowMs && verySlowMs >= slowMs) {
            return VERY_SLOW;
        }
        return durationMs >= slowMs ? SLOW : NORMAL;
    }
}
