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

/**
 * The health of a single REST endpoint, as published by {@code api_endpoint_health}.
 *
 * <p>The numeric code is what reaches Prometheus - a gauge cannot carry a string -
 * and the order matters: higher is better, so a panel can chart the worst endpoint
 * with {@code min by (...)} and an alert can fire on {@code <= 1} without listing
 * states. The codes leave room below {@link #UNKNOWN} and above {@link #HEALTHY}
 * only in the sense that they should never be renumbered: dashboards and alert
 * rules are written against these values.</p>
 */
public enum EndpointHealth {

    /** No verdict yet - the first evaluation has not run, or health monitoring is off. */
    UNKNOWN(0, "unknown"),

    /** Serving badly enough to page: a required dependency is down, the handler is
     *  missing, or the endpoint is failing above the unhealthy error ratio. */
    UNHEALTHY(1, "unhealthy"),

    /** Serving, but with errors above the degraded ratio, or with too little traffic
     *  to judge a failure that has been seen. */
    DEGRADED(2, "degraded"),

    /** Serving cleanly, or idle with every required dependency reachable. */
    HEALTHY(3, "healthy");

    private final int code;
    private final String label;

    EndpointHealth(int code, String label) {
        this.code = code;
        this.label = label;
    }

    /** The value published to Prometheus. Never renumber these. */
    public int code() {
        return code;
    }

    /** Lower-case name used in JSON and in the {@code to} tag of the transition counter. */
    public String label() {
        return label;
    }

    /** @return true when this state is worse than {@code other}. */
    public boolean isWorseThan(EndpointHealth other) {
        return code < other.code;
    }
}
