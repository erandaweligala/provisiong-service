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

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code monitoring.api.health} block - the thresholds that turn observed
 * requests into a health verdict.
 *
 * <p>These are the only numbers in endpoint monitoring that are a judgement call
 * rather than a measurement, which is why they are configuration and why the
 * defaults are deliberately loose: a health signal that flips on one unlucky
 * request is a health signal people learn to ignore.</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "monitoring.api.health")
public class EndpointHealthProperties {

    /**
     * Master switch for per-endpoint health. When false no {@code api_endpoint_health}
     * series are published; the request metrics themselves are unaffected.
     */
    private boolean enabled = true;

    /** How often the verdict is recomputed. Also the resolution of every health series. */
    private long evaluationIntervalMs = 15_000;

    /**
     * How far back the error ratio looks. Kept at five minutes: long enough that
     * a single failure does not swing the ratio, short enough that a recovery
     * shows up promptly.
     */
    private long windowMs = 300_000;

    /** Error ratio in the window at or above which an endpoint is DEGRADED. */
    private double degradedErrorRatio = 0.01;

    /** Error ratio in the window at or above which an endpoint is UNHEALTHY. */
    private double unhealthyErrorRatio = 0.10;

    /**
     * Requests needed in the window before the ratios above are trusted.
     *
     * <p>Below this, a single failure would produce a ratio of 0.5 or 1.0 and page
     * on nothing. Failures under the floor still show: the endpoint goes DEGRADED
     * with reason {@code errors}, which charts and does not page.</p>
     */
    private long minimumRequests = 20;

    /**
     * Whether a required dependency being DOWN makes an endpoint UNHEALTHY.
     *
     * <p>This is what gives an endpoint with no traffic a real verdict instead of a
     * shrug. Turn it off if connectivity monitoring is disabled, or if dependency
     * outages here are known to be survivable.</p>
     */
    private boolean useDependencyState = true;

    /**
     * Whether to check at startup that every catalogued endpoint is actually mapped
     * to a handler in this instance.
     */
    private boolean verifyMappings = true;
}
