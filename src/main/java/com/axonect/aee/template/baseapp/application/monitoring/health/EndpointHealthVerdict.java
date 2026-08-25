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
 * The outcome of one evaluation of one endpoint: what the state is and which check
 * decided it.
 *
 * @param health the state published as {@code api_endpoint_health}
 * @param reason the check that decided it, published as {@code api_endpoint_health_reason}
 * @param detail free text for the JSON view and the log line; never a metric label
 */
public record EndpointHealthVerdict(EndpointHealth health, EndpointHealthReason reason, String detail) {

    public static EndpointHealthVerdict of(EndpointHealth health, EndpointHealthReason reason, String detail) {
        return new EndpointHealthVerdict(health, reason, detail);
    }

    static final EndpointHealthVerdict UNKNOWN =
            new EndpointHealthVerdict(EndpointHealth.UNKNOWN, EndpointHealthReason.UNKNOWN, "not evaluated yet");
}
