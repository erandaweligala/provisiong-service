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
 * Why an endpoint is in the state it is in, published as the {@code reason} tag of
 * {@code api_endpoint_health_reason}.
 *
 * <p>The state alone says an endpoint is unhealthy; the reason says which of the
 * three independent checks decided that, which is the difference between paging
 * the database on-call and paging whoever last changed a controller path. The set
 * is closed and small on purpose - it is a metric label, so every value added here
 * is another time series per endpoint.</p>
 */
public enum EndpointHealthReason {

    /** Took traffic in the window and none of it failed. */
    OK("ok"),

    /** Took no traffic in the window. Everything that can be checked without a
     *  request - the handler, the dependencies - is fine, so this is healthy;
     *  it is simply not proven by traffic. */
    IDLE("idle"),

    /** Responses in the window included 5xx above the configured ratio. */
    ERRORS("errors"),

    /** A dependency the endpoint cannot serve without is DOWN. */
    DEPENDENCY_DOWN("dependency_down"),

    /** The catalogued method and path are not mapped to a handler in this instance,
     *  so every call to it answers 404 - which no availability figure would show,
     *  because a 404 is not a 5xx. */
    NOT_MAPPED("not_mapped"),

    /** No evaluation has produced a verdict yet. */
    UNKNOWN("unknown");

    private final String label;

    EndpointHealthReason(String label) {
        this.label = label;
    }

    /** The value of the {@code reason} tag. Never rename these. */
    public String label() {
        return label;
    }
}
