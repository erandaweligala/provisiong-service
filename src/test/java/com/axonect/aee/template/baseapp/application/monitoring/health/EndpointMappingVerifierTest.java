package com.axonect.aee.template.baseapp.application.monitoring.health;

import com.axonect.aee.template.baseapp.application.monitoring.ApiEndpointRegistry;
import com.axonect.aee.template.baseapp.application.monitoring.MonitoredEndpoint;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The check that catches a catalog and a build that were changed apart.
 */
class EndpointMappingVerifierTest {

    private static MonitoredEndpoint endpoint(String name, String method, String... uris) {
        MonitoredEndpoint endpoint = new MonitoredEndpoint();
        endpoint.setName(name);
        endpoint.setTitle(name);
        endpoint.setMethod(method);
        endpoint.setUris(List.of(uris));
        return endpoint;
    }

    private static ApiEndpointRegistry catalogOf(MonitoredEndpoint... endpoints) {
        return new ApiEndpointRegistry(List.of(endpoints));
    }

    @Test
    void anEndpointTheApplicationServesIsMapped() {
        Set<String> mapped = new EndpointMappingVerifier(Set.of("GET /api/user/{}"))
                .mappedEndpoints(catalogOf(endpoint("get_user", "GET", "/api/user/{user_name}")));

        assertTrue(mapped.contains("get_user"));
    }

    @Test
    void anEndpointNothingIsMappedToIsReported() {
        Set<String> mapped = new EndpointMappingVerifier(Set.of("GET /api/user/{}"))
                .mappedEndpoints(catalogOf(endpoint("get_user", "GET", "/api/users/{user_name}")));

        assertFalse(mapped.contains("get_user"));
    }

    /** The same path under a different verb is a different endpoint. */
    @Test
    void theMethodHasToMatch() {
        Set<String> mapped = new EndpointMappingVerifier(Set.of("GET /api/user/{}"))
                .mappedEndpoints(catalogOf(endpoint("delete_user", "DELETE", "/api/user/{user_name}")));

        assertFalse(mapped.contains("delete_user"));
    }

    @Test
    void theMethodIsMatchedWithoutRegardToCase() {
        Set<String> mapped = new EndpointMappingVerifier(Set.of("PATCH /api/user/{}"))
                .mappedEndpoints(catalogOf(endpoint("update_user", "patch", "/api/user/{user_name}")));

        assertTrue(mapped.contains("update_user"));
    }

    /** Renaming a {@code @PathVariable} must not read as an endpoint disappearing. */
    @Test
    void pathVariableNamesAreIgnored() {
        Set<String> mapped = new EndpointMappingVerifier(Set.of("GET /api/user/{}"))
                .mappedEndpoints(catalogOf(endpoint("get_user", "GET", "/api/user/{userName}")));

        assertTrue(mapped.contains("get_user"));
    }

    /**
     * updateService and deleteService are each mapped to two paths. Retiring one
     * alias is not an outage as long as the handler is still reachable.
     */
    @Test
    void anEndpointWithSeveralPathsCountsAsMappedWhenAnyOfThemIsServed() {
        MonitoredEndpoint updateService = endpoint("update_service", "PATCH",
                "/api/user/{user_id}/services/{plan_id}/{request_id}",
                "/api/user/services/{user_id}/{plan_id}/{request_id}");

        Set<String> mapped = new EndpointMappingVerifier(
                Set.of("PATCH /api/user/services/{}/{}/{}")).mappedEndpoints(catalogOf(updateService));

        assertTrue(mapped.contains("update_service"));
    }

    @Test
    void reportsEveryMappedEndpointAndNoOthers() {
        Set<String> mapped = new EndpointMappingVerifier(
                Set.of("GET /api/user/{}", "POST /api/user"))
                .mappedEndpoints(catalogOf(
                        endpoint("get_user", "GET", "/api/user/{user_name}"),
                        endpoint("create_user", "POST", "/api/user"),
                        endpoint("gone", "DELETE", "/api/gone")));

        assertEquals(Set.of("get_user", "create_user"), mapped);
    }
}
