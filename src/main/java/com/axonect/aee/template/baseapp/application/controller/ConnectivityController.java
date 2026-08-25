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

import com.axonect.aee.template.baseapp.application.monitoring.connectivity.ConnectivityMonitoringService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only view of database, Redis and Kafka connectivity as tracked by
 * {@link ConnectivityMonitoringService}.
 *
 * <p>Grafana reads the same state from Prometheus ({@code dependency_up} and friends);
 * this endpoint is for humans and for uptime checks that want a single yes/no answer
 * without parsing a metrics scrape. It is the same path and the same payload the DB
 * write service serves.</p>
 */
@RestController
@RequestMapping("/monitoring/connectivity")
@RequiredArgsConstructor
public class ConnectivityController {

    private final ConnectivityMonitoringService connectivityMonitoringService;

    /**
     * Current connectivity state of every tracked dependency. Answers 503 when at least
     * one dependency is down, so an uptime check can key off the status code alone.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> status() {
        boolean allUp = connectivityMonitoringService.allUp();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", allUp ? "UP" : "DOWN");
        body.put("dependencies", connectivityMonitoringService.snapshot());
        return ResponseEntity.status(allUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
