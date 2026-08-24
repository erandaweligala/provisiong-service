package com.axonect.aee.template.baseapp.application.monitoring;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiEndpointRegistryTest {

    private static MonitoredEndpoint endpoint(String name, String method, String... uris) {
        MonitoredEndpoint endpoint = new MonitoredEndpoint();
        endpoint.setName(name);
        endpoint.setTitle(name);
        endpoint.setMethod(method);
        endpoint.setUris(List.of(uris));
        return endpoint;
    }

    private static ApiEndpointRegistry registry(MonitoredEndpoint... endpoints) {
        return new ApiEndpointRegistry(List.of(endpoints));
    }

    @Test
    void matchesTemplatedUriExactly() {
        ApiEndpointRegistry registry = registry(endpoint("get_user", "GET", "/api/user/{user_name}"));

        assertEquals("get_user", registry.tagFor("GET", "/api/user/{user_name}"));
    }

    @Test
    void ignoresPathVariableNames() {
        ApiEndpointRegistry registry = registry(endpoint("get_user", "GET", "/api/user/{user_name}"));

        // A controller renaming its @PathVariable must not silently drop the
        // endpoint off the dashboard.
        assertEquals("get_user", registry.tagFor("GET", "/api/user/{userName}"));
        assertEquals("get_user", registry.tagFor("GET", "/api/user/{id}"));
    }

    @Test
    void separatesEndpointsByMethodOnTheSamePath() {
        ApiEndpointRegistry registry = registry(
                endpoint("get_user", "GET", "/api/user/{user_name}"),
                endpoint("update_user", "PATCH", "/api/user/{user_name}"),
                endpoint("delete_user", "DELETE", "/api/user/{user_name}"));

        assertEquals("get_user", registry.tagFor("GET", "/api/user/{user_name}"));
        assertEquals("update_user", registry.tagFor("PATCH", "/api/user/{user_name}"));
        assertEquals("delete_user", registry.tagFor("DELETE", "/api/user/{user_name}"));
    }

    @Test
    void mapsEveryPathOfAMultiMappedHandlerOntoOneEndpoint() {
        ApiEndpointRegistry registry = registry(endpoint("update_service", "PATCH",
                "/api/user/{user_id}/services/{plan_id}/{request_id}",
                "/api/user/services/{user_id}/{plan_id}/{request_id}"));

        assertEquals("update_service",
                registry.tagFor("PATCH", "/api/user/{user_id}/services/{plan_id}/{request_id}"));
        assertEquals("update_service",
                registry.tagFor("PATCH", "/api/user/services/{user_id}/{plan_id}/{request_id}"));
    }

    @Test
    void doesNotConfuseLiteralSegmentsWithPathVariables() {
        ApiEndpointRegistry registry = registry(
                endpoint("get_user", "GET", "/api/user/{user_name}"),
                endpoint("list_users", "GET", "/api/user/list"));

        assertEquals("list_users", registry.tagFor("GET", "/api/user/list"));
        assertEquals("get_user", registry.tagFor("GET", "/api/user/{user_name}"));
    }

    @Test
    void collapsesUncataloguedTrafficIntoASingleSeries() {
        ApiEndpointRegistry registry = registry(endpoint("get_user", "GET", "/api/user/{user_name}"));

        // Bounding the tag is the whole point: an unknown path must never mint a
        // new label value in Prometheus.
        assertEquals("other", registry.tagFor("GET", "/api/bng/{bng_id}"));
        assertEquals("other", registry.tagFor("GET", "/actuator/prometheus"));
        assertEquals("other", registry.tagFor("POST", "/api/user/{user_name}"));
        assertEquals("other", registry.tagFor(null, null));
    }

    @Test
    void toleratesMethodCaseAndTrailingSlashes() {
        ApiEndpointRegistry registry = registry(endpoint("get_users", "get", "/api/user/"));

        assertEquals("get_users", registry.tagFor("GET", "/api/user"));
    }

    @Test
    void skipsUnnamedEntriesAndKeepsTheFirstOfADuplicateName() {
        MonitoredEndpoint unnamed = endpoint(null, "GET", "/api/unnamed");
        MonitoredEndpoint first = endpoint("get_user", "GET", "/api/user/{user_name}");
        MonitoredEndpoint duplicate = endpoint("get_user", "GET", "/api/other");
        ApiEndpointRegistry registry = registry(unnamed, first, duplicate);

        assertEquals(1, registry.endpoints().size());
        assertEquals("get_user", registry.endpoints().get(0).getName());
        assertEquals("other", registry.tagFor("GET", "/api/unnamed"));
        assertEquals("other", registry.tagFor("GET", "/api/other"));
    }

    @Test
    void exposesTheCatalogInDeclarationOrder() {
        ApiEndpointRegistry registry = registry(
                endpoint("get_user", "GET", "/api/user/{user_name}"),
                endpoint("get_users", "GET", "/api/user"));

        assertEquals(List.of("get_user", "get_users"),
                registry.endpoints().stream().map(MonitoredEndpoint::getName).toList());
    }

    @Test
    void normalizesUrisForComparison() {
        assertEquals("/api/user/{}", ApiEndpointRegistry.normalizeUri("/api/user/{user_name}"));
        assertEquals("/api/user/{}", ApiEndpointRegistry.normalizeUri("api/user/{userName}/"));
        assertEquals("/api/user/{}", ApiEndpointRegistry.normalizeUri("/api/user/{id:[0-9]+}"));
        assertEquals("/", ApiEndpointRegistry.normalizeUri("/"));
    }
}
