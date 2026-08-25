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
 * One REST endpoint on the availability dashboard.
 *
 * <p>Each entry maps one or more Spring request mappings onto a stable metric
 * label, so the dashboard keeps working when a controller path is refactored -
 * only {@code uris} has to be updated, the {@code name} (and therefore the time
 * series) stays the same.</p>
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
}
