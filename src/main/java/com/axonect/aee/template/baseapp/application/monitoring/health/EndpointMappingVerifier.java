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

import com.axonect.aee.template.baseapp.application.monitoring.ApiEndpointRegistry;
import com.axonect.aee.template.baseapp.application.monitoring.MonitoredEndpoint;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Answers, for each catalogued endpoint, whether this running instance actually has
 * a handler for it.
 *
 * <p>{@code ApiMonitoringCatalogTest} asks the same question of the source tree at
 * build time. Asking it again of the live {@code RequestMappingHandlerMapping} costs
 * one pass at startup and catches what a build-time test cannot: a catalog and a war
 * that came from different commits, an endpoint behind a {@code @ConditionalOn...}
 * that did not switch on in this environment, a controller excluded by a profile.</p>
 *
 * <p>It matters because this failure is invisible everywhere else. A request to a
 * path nothing is mapped to answers 404, and 404 is a client error - availability
 * stays at 100% while every caller of that endpoint is broken.</p>
 */
@Slf4j
public final class EndpointMappingVerifier {

    /** Mappings the running application serves, as {@code "METHOD /normalised/{}/path"}. */
    private final Set<String> liveMappings;

    public EndpointMappingVerifier(Set<String> liveMappings) {
        this.liveMappings = Set.copyOf(liveMappings);
    }

    /**
     * @return the names of the catalogued endpoints that are mapped here. An endpoint
     * mapped to several paths counts as mapped when <em>any</em> of them is served, so
     * a spare alias that has been retired does not read as an outage.
     */
    public Set<String> mappedEndpoints(ApiEndpointRegistry registry) {
        Set<String> mapped = new LinkedHashSet<>();
        for (MonitoredEndpoint endpoint : registry.endpoints()) {
            if (isMapped(endpoint)) {
                mapped.add(endpoint.getName());
            } else {
                log.error("Catalogued endpoint '{}' ({} {}) is not mapped to any handler in this instance - "
                                + "calls to it will answer 404. Either the catalog in application.yml or the "
                                + "controller's request mapping is out of date.",
                        endpoint.getName(), endpoint.getMethod(), endpoint.getUris());
            }
        }
        return mapped;
    }

    private boolean isMapped(MonitoredEndpoint endpoint) {
        String method = endpoint.getMethod() == null ? "" : endpoint.getMethod().trim().toUpperCase(Locale.ROOT);
        for (String uri : endpoint.getUris()) {
            if (liveMappings.contains(method + " " + ApiEndpointRegistry.normalizeUri(uri))) {
                return true;
            }
        }
        return false;
    }
}
