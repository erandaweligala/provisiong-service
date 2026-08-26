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

import java.util.List;

/**
 * Turns what has been observed about one endpoint into a health verdict.
 *
 * <p>Kept free of Micrometer, Spring and clocks so the decision itself can be
 * tested exhaustively: everything it needs is an argument, and it returns a value.
 * {@link EndpointHealthMonitor} does the observing.</p>
 *
 * <h2>The three checks, in order</h2>
 * <ol>
 *   <li><b>Is it there?</b> A catalogued endpoint with no handler in this instance
 *       answers 404 to every call. Availability would read a serene 100% - 4xx is
 *       not a failure - so nothing else in endpoint monitoring can catch a path
 *       that was renamed in a controller and not in the catalog.</li>
 *   <li><b>Can it work?</b> An endpoint whose database is unreachable is not
 *       healthy just because nobody has called it yet. This is the check that gives
 *       an idle endpoint a real answer.</li>
 *   <li><b>Is it working?</b> The 5xx ratio over the window. Last, because it is
 *       the one check that needs traffic to say anything at all.</li>
 * </ol>
 *
 * <p>The order is the point: all three failures produce UNHEALTHY, so what the
 * ordering picks is the <em>reason</em> - and a missing handler explains a
 * dependency-shaped error better than the error explains itself. Fix causes, not
 * symptoms.</p>
 */
public final class EndpointHealthEvaluator {

    private final EndpointHealthProperties properties;

    public EndpointHealthEvaluator(EndpointHealthProperties properties) {
        this.properties = properties;
    }

    /**
     * @param mapped           whether a handler for the catalogued mapping exists here
     * @param dependenciesDown required dependencies currently DOWN, empty when all are up
     * @param requests         requests served in the window
     * @param errors           of those, how many answered 5xx
     */
    public EndpointHealthVerdict evaluate(boolean mapped,
                                          List<String> dependenciesDown,
                                          long requests,
                                          long errors) {
        if (!mapped) {
            return EndpointHealthVerdict.of(EndpointHealth.UNHEALTHY, EndpointHealthReason.NOT_MAPPED,
                    "no handler is mapped to this method and path in this instance");
        }
        if (properties.isUseDependencyState() && !dependenciesDown.isEmpty()) {
            return EndpointHealthVerdict.of(EndpointHealth.UNHEALTHY, EndpointHealthReason.DEPENDENCY_DOWN,
                    "required dependency down: " + String.join(", ", dependenciesDown));
        }
        return fromTraffic(requests, errors);
    }

    /**
     * The traffic verdict on its own.
     *
     * <p>Below {@code minimum-requests} the ratio is not meaningful - one failure out
     * of two requests is 50%, which is a number about the sample size and not about
     * the endpoint. Rather than ignore the failure or page on it, a failure under the
     * floor reads DEGRADED: visible on the dashboard, quiet on the pager.</p>
     */
    private EndpointHealthVerdict fromTraffic(long requests, long errors) {
        if (requests <= 0) {
            return EndpointHealthVerdict.of(EndpointHealth.HEALTHY, EndpointHealthReason.IDLE,
                    "no requests in the window; every required dependency is reachable");
        }
        if (errors <= 0) {
            return EndpointHealthVerdict.of(EndpointHealth.HEALTHY, EndpointHealthReason.OK,
                    requests + " request(s) in the window, none failed");
        }

        double ratio = (double) errors / requests;
        String detail = errors + " of " + requests + " request(s) answered 5xx (" + percent(ratio) + ")";

        if (requests < properties.getMinimumRequests()) {
            return EndpointHealthVerdict.of(EndpointHealth.DEGRADED, EndpointHealthReason.ERRORS,
                    detail + ", below the " + properties.getMinimumRequests() + "-request floor to judge a ratio");
        }
        if (ratio >= properties.getUnhealthyErrorRatio()) {
            return EndpointHealthVerdict.of(EndpointHealth.UNHEALTHY, EndpointHealthReason.ERRORS, detail);
        }
        if (ratio >= properties.getDegradedErrorRatio()) {
            return EndpointHealthVerdict.of(EndpointHealth.DEGRADED, EndpointHealthReason.ERRORS, detail);
        }
        return EndpointHealthVerdict.of(EndpointHealth.HEALTHY, EndpointHealthReason.OK,
                detail + ", below the degraded threshold");
    }

    private static String percent(double ratio) {
        return String.format("%.2f%%", ratio * 100);
    }
}
