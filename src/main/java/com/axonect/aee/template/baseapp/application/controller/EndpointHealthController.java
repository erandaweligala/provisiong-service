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
package com.axonect.aee.template.baseapp.application.controller;

import com.axonect.aee.template.baseapp.application.monitoring.health.EndpointHealth;
import com.axonect.aee.template.baseapp.application.monitoring.health.EndpointHealthMonitor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only view of every catalogued REST endpoint's health, as tracked by
 * {@link EndpointHealthMonitor}.
 *
 * <p>The same state Grafana reads from Prometheus, in one document: which endpoints
 * are healthy, why the others are not, and the sentence of detail behind each
 * verdict that no metric label could carry. Sits next to
 * {@code GET /monitoring/connectivity}, which answers the same question one layer
 * down.</p>
 */
@RestController
@RequestMapping("/monitoring/endpoints")
@RequiredArgsConstructor
public class EndpointHealthController {

    /**
     * Absent when {@code monitoring.api.enabled} or {@code monitoring.api.health.enabled}
     * is off. Held through a provider rather than gated with {@code @ConditionalOnBean}
     * because the condition on a scanned controller is evaluated against whatever has
     * been registered so far, which would make this endpoint's existence depend on bean
     * definition order.
     */
    private final ObjectProvider<EndpointHealthMonitor> endpointHealthMonitor;

    /**
     * Current health of every catalogued endpoint.
     *
     * <p>Answers 503 as soon as one endpoint is anything but healthy, so a deploy
     * smoke test can key off the status code alone. That is deliberately stricter
     * than a readiness probe should be - an endpoint degraded by a handful of errors
     * is not a reason to take a pod out of service - so this is a check to run
     * against a deployment, not to wire into one.</p>
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        EndpointHealthMonitor monitor = endpointHealthMonitor.getIfAvailable();
        Map<String, Object> body = new LinkedHashMap<>();

        if (monitor == null) {
            body.put("status", EndpointHealth.UNKNOWN.label());
            body.put("detail", "endpoint health monitoring is disabled");
            body.put("endpoints", Map.of());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }

        EndpointHealth worst = monitor.worstHealth();
        body.put("status", worst.label());
        body.put("endpoints", monitor.snapshot());
        return ResponseEntity
                .status(worst == EndpointHealth.HEALTHY ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(body);
    }
}
