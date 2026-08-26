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

import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Resolves an observed HTTP request onto the catalog entry it belongs to.
 *
 * <p>Micrometer already reports the <em>templated</em> URI of a request (for
 * example {@code /api/user/{user_name}} rather than
 * {@code /api/user/40-e1-e4-bc-d8-30}), so matching is a lookup rather than a
 * path match. Path variable names are erased first, which keeps the catalog
 * working when a controller renames a {@code @PathVariable}.</p>
 */
@Slf4j
public class ApiEndpointRegistry {

    /** Matches a single Spring path variable, e.g. {@code {user_name}} or {@code {id:\\d+}}. */
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{[^/]*}");

    /** The {@code api} tag given to traffic that is not in the catalog. */
    private static final String UNMATCHED = "other";

    private final Map<String, MonitoredEndpoint> byMethodAndUri;
    private final Map<String, MonitoredEndpoint> byName;

    public ApiEndpointRegistry(List<MonitoredEndpoint> endpoints) {
        Map<String, MonitoredEndpoint> uriIndex = new HashMap<>();
        Map<String, MonitoredEndpoint> nameIndex = new LinkedHashMap<>();

        for (MonitoredEndpoint endpoint : endpoints) {
            if (endpoint.getName() == null || endpoint.getName().isBlank()) {
                log.warn("Ignoring monitored endpoint without a name: method={}, uris={}",
                        endpoint.getMethod(), endpoint.getUris());
                continue;
            }
            MonitoredEndpoint clash = nameIndex.putIfAbsent(endpoint.getName(), endpoint);
            if (clash != null) {
                log.warn("Duplicate monitored endpoint name '{}' - the later definition is ignored",
                        endpoint.getName());
                continue;
            }
            for (String uri : endpoint.getUris()) {
                String key = key(endpoint.getMethod(), uri);
                MonitoredEndpoint previous = uriIndex.putIfAbsent(key, endpoint);
                if (previous != null) {
                    log.warn("Endpoints '{}' and '{}' both claim {} - '{}' wins",
                            previous.getName(), endpoint.getName(), key, previous.getName());
                }
            }
        }

        this.byMethodAndUri = Collections.unmodifiableMap(uriIndex);
        this.byName = Collections.unmodifiableMap(nameIndex);
        log.info("REST availability monitoring catalog loaded: {} endpoint(s), {} request mapping(s)",
                byName.size(), byMethodAndUri.size());
    }

    /**
     * @return the value of the {@code api} metric tag for the given request:
     * the catalog slug, or {@value #UNMATCHED} for traffic that is not tracked.
     * Returning a constant instead of the raw URI keeps the tag's cardinality
     * bounded by the size of the catalog.
     */
    public String tagFor(String method, String templatedUri) {
        if (method == null || templatedUri == null) {
            return UNMATCHED;
        }
        return Optional.ofNullable(byMethodAndUri.get(key(method, templatedUri)))
                .map(MonitoredEndpoint::getName)
                .orElse(UNMATCHED);
    }

    /**
     * @return every catalog entry, in declaration order.
     */
    public List<MonitoredEndpoint> endpoints() {
        return List.copyOf(byName.values());
    }

    /**
     * Erases path variable names and trailing slashes so that catalog entries and
     * Micrometer's {@code uri} tag agree on a single spelling.
     *
     * <p>Public because it is that single spelling: the endpoint health check
     * compares the catalog against Spring's live handler mappings, and would find
     * nothing if it normalised paths even slightly differently from this.</p>
     */
    public static String normalizeUri(String uri) {
        String normalized = PATH_VARIABLE.matcher(uri.trim()).replaceAll("{}");
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String key(String method, String uri) {
        String normalizedMethod = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
        return normalizedMethod + " " + normalizeUri(uri);
    }
}
