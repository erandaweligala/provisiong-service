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
 * One endpoint's health as served by {@code GET /monitoring/endpoints}.
 *
 * <p>Everything here is also in Prometheus. This exists for the cases a metrics
 * scrape is the wrong tool: a smoke test after a deploy that wants one JSON
 * document, an on-call engineer on a pod with no Grafana in front of them, and
 * {@code detail}, which is a sentence rather than a label and so has nowhere to
 * live in a time series.</p>
 */
public record EndpointHealthStatus(
        String title,
        String method,
        List<String> uris,
        String health,
        String reason,
        String detail,
        boolean mapped,
        long requestsInWindow,
        long errorsInWindow,
        double errorRatio,
        List<String> dependencies,
        List<String> dependenciesDown,
        long unhealthySeconds,
        Long lastFailureEpochSeconds) {
}
