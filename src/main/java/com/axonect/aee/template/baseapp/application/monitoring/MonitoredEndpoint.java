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
 * One REST endpoint in the monitoring catalog.
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

    /**
     * The dependencies this endpoint cannot serve correctly without, by the label
     * dependency connectivity monitoring uses: {@code database}, {@code redis},
     * {@code kafka}.
     *
     * <p>This is what lets an endpoint that has taken no traffic still be given a
     * verdict - if the database it reads from is unreachable, the endpoint is not
     * healthy however quiet it has been. List only what a failure of would make the
     * endpoint answer 5xx; a dependency it degrades gracefully without belongs
     * nowhere near this list, because everything here can mark the endpoint
     * UNHEALTHY on its own.</p>
     *
     * <p>Empty means health is judged from traffic alone.</p>
     */
    private List<String> dependencies = new ArrayList<>();
}
