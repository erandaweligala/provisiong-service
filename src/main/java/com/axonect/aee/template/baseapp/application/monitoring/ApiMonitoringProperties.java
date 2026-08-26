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
package com.axonect.aee.template.baseapp.application.monitoring;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Binds the {@code monitoring.api} block that drives the REST endpoint
 * monitoring catalog. Three settings, no thresholds and nothing to tune.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "monitoring.api")
public class ApiMonitoringProperties {

    /**
     * Master switch. When false no {@code api} tag is added to
     * {@code http_server_requests} and the endpoint catalog is not published.
     */
    private boolean enabled = true;

    /**
     * Value published as the {@code microservice} common tag on every meter, so a
     * shared Prometheus/Grafana instance can separate this service from the rest
     * of the AAA stack.
     */
    private String microservice = "airtel-aaa-user-provisioning-service";

    /**
     * The endpoints the monitoring catalog publishes.
     */
    private List<MonitoredEndpoint> endpoints = new ArrayList<>();
}
