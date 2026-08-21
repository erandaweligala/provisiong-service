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

import java.util.ArrayList;
import java.util.List;

/**
 * A single REST endpoint that is tracked on the availability dashboard.
 *
 * <p>Each entry maps one or more Spring request mappings onto a stable metric
 * label, so that the Grafana dashboard keeps working when a controller path is
 * refactored - only the {@code uris} list has to be updated, the {@code name}
 * (and therefore the time series) stays the same.</p>
 */
@Getter
@Setter
public class MonitoredEndpoint {

    /**
     * Stable slug published as the {@code api} metric tag, e.g. {@code create_user}.
     * Must be unique within the catalog and should never change once dashboards
     * and alert rules refer to it.
     */
    private String name;

    /**
     * Human readable name shown on the dashboard, e.g. {@code Create user}.
     * Not published as a metric tag.
     */
    private String title;

    /**
     * HTTP method of the endpoint. Matched case-insensitively.
     */
    private String method;

    /**
     * Spring URI templates that resolve to this endpoint, e.g.
     * {@code /api/user/{user_name}}. A handler mapped to several paths (such as
     * {@code updateService}) lists all of them here.
     *
     * <p>Path variable names are ignored while matching, so
     * {@code /api/user/{user_name}} and {@code /api/user/{userName}} are treated
     * as the same template.</p>
     */
    private List<String> uris = new ArrayList<>();

    /**
     * Response time budget in milliseconds. Requests slower than this count
     * against the endpoint's latency SLO. Falls back to
     * {@code monitoring.api.default-threshold-ms} when not set.
     */
    private Long thresholdMs;

    /**
     * Relative path used by the synthetic availability probe, e.g.
     * {@code /api/user/probe-user}. Only endpoints with a probe path set are
     * called by {@link EndpointAvailabilityProbe}, and only safe, side effect
     * free reads should ever be given one.
     */
    private String probePath;

    /**
     * HTTP status codes the probe accepts as "endpoint is serving". Defaults to
     * 200. A read that legitimately answers 404 for the probe identity can list
     * {@code [200, 404]} so that a missing test user is not reported as an outage.
     */
    private List<Integer> probeExpectedStatuses = new ArrayList<>();
}
