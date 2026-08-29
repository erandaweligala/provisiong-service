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
import org.springframework.util.AntPathMatcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
 *
 * <p>A request that never reached a handler has no template to look up - see
 * {@link #tagForRequestPath(String, String)}, which matches the raw path
 * against the catalog instead so that a rejected request is still attributed to
 * the API it was aimed at.</p>
 */
@Slf4j
public class ApiEndpointRegistry {

    /** Matches a single Spring path variable, e.g. {@code {user_name}} or {@code {id:\\d+}}. */
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{[^/]*}");

    /** The {@code api} tag given to traffic that is not in the catalog. */
    public static final String UNMATCHED = "other";

    /**
     * Matches a raw request path against a catalog URI template. Only ever asked
     * about the handful of templates in the catalog, and only for requests that
     * carry no template of their own.
     */
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final Map<String, MonitoredEndpoint> byMethodAndUri;
    private final Map<String, MonitoredEndpoint> byName;
    private final List<CatalogPath> paths;

    public ApiEndpointRegistry(List<MonitoredEndpoint> endpoints) {
        Map<String, MonitoredEndpoint> uriIndex = new HashMap<>();
        Map<String, MonitoredEndpoint> nameIndex = new LinkedHashMap<>();
        List<CatalogPath> pathPatterns = new ArrayList<>();

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
                    continue;
                }
                pathPatterns.add(new CatalogPath(method(endpoint.getMethod()), normalizePath(uri), endpoint));
            }
        }

        this.byMethodAndUri = Collections.unmodifiableMap(uriIndex);
        this.byName = Collections.unmodifiableMap(nameIndex);
        this.paths = List.copyOf(pathPatterns);
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
     * Resolves an API from the path the caller actually asked for, for requests
     * that never got as far as a handler.
     *
     * <p>{@link #tagFor(String, String)} needs the templated URI Spring matched,
     * and a request rejected in the filter chain - a missing {@code channel}
     * header, credentials that did not check out, the rate limiter, the request
     * firewall - never gets one. Micrometer reports those as {@code uri=UNKNOWN},
     * so tagging from the template alone files every one of them under
     * {@value #UNMATCHED} and the API they were aimed at reads as though nothing
     * had failed. Matching the raw path against the catalog puts the rejection
     * back on the API that was called.</p>
     *
     * <p>Cardinality is unchanged: the path is only ever used to pick a catalog
     * entry, and anything it does not match is still {@value #UNMATCHED}. The
     * method has to match as well, so a 405 on a catalogued path does not borrow
     * the series of the verb that <em>is</em> mapped there.</p>
     *
     * @param requestUri the URI the container saw, context path and matrix
     *                   variables included - both are stripped here, so callers
     *                   can pass {@code HttpServletRequest.getRequestURI()}
     *                   straight through
     * @param contextPath the prefix the container is serving this application
     *                    under, or empty when it is serving the root
     * @return the value of the {@code api} metric tag for the given request.
     */
    public String tagForRequestPath(String method, String requestUri, String contextPath) {
        if (method == null || requestUri == null) {
            return UNMATCHED;
        }
        String wanted = method(method);
        String path = normalizePath(strip(requestUri, contextPath));
        Comparator<String> mostSpecificFirst = PATH_MATCHER.getPatternComparator(path);

        MonitoredEndpoint match = null;
        String matchedPattern = null;
        for (CatalogPath candidate : paths) {
            if (!candidate.method().equals(wanted) || !PATH_MATCHER.match(candidate.pattern(), path)) {
                continue;
            }
            if (matchedPattern == null || mostSpecificFirst.compare(candidate.pattern(), matchedPattern) < 0) {
                matchedPattern = candidate.pattern();
                match = candidate.endpoint();
            }
        }
        return match == null ? UNMATCHED : match.getName();
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
        return normalizePath(PATH_VARIABLE.matcher(uri).replaceAll("{}"));
    }

    /**
     * The request path as the catalog spells it: without the context path the
     * container prefixed, and without any matrix variables appended to a segment.
     */
    private static String strip(String requestUri, String contextPath) {
        String path = requestUri;
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        int matrixVariable = path.indexOf(';');
        return matrixVariable < 0 ? path : path.substring(0, matrixVariable);
    }

    /**
     * Trims and squares up a path or URI template without touching its path
     * variables - {@link #normalizeUri(String)} erases their names, which is what
     * the lookup index wants and what {@link AntPathMatcher} cannot match on.
     */
    private static String normalizePath(String path) {
        String normalized = path.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String method(String method) {
        return method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
    }

    private static String key(String method, String uri) {
        return method(method) + " " + normalizeUri(uri);
    }

    /** One catalog URI template, kept spelled as configured so it can be matched against a live path. */
    private record CatalogPath(String method, String pattern, MonitoredEndpoint endpoint) {
    }
}
