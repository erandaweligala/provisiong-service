package com.axonect.aee.template.baseapp.application.monitoring;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiNameObservationConventionTest {

    private static final ApiEndpointRegistry REGISTRY = new ApiEndpointRegistry(List.of(
            endpoint("get_user", "GET", "/api/user/{user_name}"),
            endpoint("create_user", "POST", "/api/user")));

    private final ApiNameObservationConvention convention = new ApiNameObservationConvention(REGISTRY);

    private static MonitoredEndpoint endpoint(String name, String method, String uri) {
        MonitoredEndpoint endpoint = new MonitoredEndpoint();
        endpoint.setName(name);
        endpoint.setMethod(method);
        endpoint.setUris(List.of(uri));
        return endpoint;
    }

    private ServerRequestObservationContext contextFor(String method, String path, String pathPattern, int status) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(status);
        ServerRequestObservationContext context = new ServerRequestObservationContext(request, response);
        context.setPathPattern(pathPattern);
        return context;
    }

    private KeyValues tagsFor(String method, String path, String pathPattern, int status) {
        return convention.getLowCardinalityKeyValues(contextFor(method, path, pathPattern, status));
    }

    private static String valueOf(KeyValues keyValues, String key) {
        for (KeyValue keyValue : keyValues) {
            if (key.equals(keyValue.getKey())) {
                return keyValue.getValue();
            }
        }
        return null;
    }

    @Test
    void tagsARequestWithItsCatalogName() {
        KeyValues tags = tagsFor("GET", "/api/user/40-e1-e4-bc-d8-30", "/api/user/{user_name}", 200);

        assertEquals("get_user", valueOf(tags, "api"));
    }

    @Test
    void keepsTheStandardSpringTags() {
        KeyValues tags = tagsFor("POST", "/api/user", "/api/user", 201);

        assertEquals("create_user", valueOf(tags, "api"));
        assertEquals("POST", valueOf(tags, "method"));
        assertEquals("/api/user", valueOf(tags, "uri"));
        assertEquals("201", valueOf(tags, "status"));
        assertEquals("SUCCESS", valueOf(tags, "outcome"));
    }

    @Test
    void distinguishesMethodsSharingAPath() {
        assertEquals("create_user", valueOf(tagsFor("POST", "/api/user", "/api/user", 200), "api"));
        // Only GET /api/user/{user_name} is catalogued, so a DELETE on the same
        // path must not borrow its series.
        assertEquals("other", valueOf(tagsFor("DELETE", "/api/user/x", "/api/user/{user_name}", 200), "api"));
    }

    @Test
    void tagsUncataloguedTrafficAsOther() {
        assertEquals("other", valueOf(tagsFor("GET", "/api/bng/1", "/api/bng/{bng_id}", 200), "api"));
        assertEquals("other", valueOf(tagsFor("GET", "/actuator/prometheus", "/actuator/prometheus", 200), "api"));
    }

    @Test
    void tagsUnmappedRequestsAsOtherRatherThanFailing() {
        // Spring reports uri=NOT_FOUND with no path pattern for a 404; the
        // convention must still produce a usable tag.
        KeyValues tags = tagsFor("GET", "/nope", null, 404);

        assertEquals("other", valueOf(tags, "api"));
        assertTrue(valueOf(tags, "uri") != null);
    }

    // ---------------------------------------------------------------------
    // Failures the request never got far enough for Spring to describe
    // ---------------------------------------------------------------------

    @Test
    void countsARequestRejectedBeforeTheHandlerAgainstTheApiItAskedFor() {
        // ChannelAuthFilter answers 401 without ever reaching the dispatcher, so
        // there is no path pattern and Spring reports uri=UNKNOWN. The 401 is
        // still a failure of get_user from the caller's side.
        KeyValues tags = tagsFor("GET", "/api/user/40-e1-e4-bc-d8-30", null, 401);

        assertEquals("UNKNOWN", valueOf(tags, "uri"), "the fixture has to be the case being fixed");
        assertEquals("get_user", valueOf(tags, "api"));
        assertEquals("CLIENT_ERROR", valueOf(tags, "outcome"));
        assertEquals("401", valueOf(tags, "status"));
    }

    @Test
    void countsAMissingChannelHeaderAndARateLimitedRequestTheSameWay() {
        assertEquals("create_user", valueOf(tagsFor("POST", "/api/user", null, 400), "api"));
        assertEquals("create_user", valueOf(tagsFor("POST", "/api/user", null, 429), "api"));
    }

    @Test
    void leavesAnUnknownPathOnOtherEvenWithoutATemplate() {
        assertEquals("other", valueOf(tagsFor("GET", "/api/bng/1", null, 401), "api"));
        // The verb was never mapped there, so nothing in the catalog failed.
        assertEquals("other", valueOf(tagsFor("DELETE", "/api/user", null, 401), "api"));
    }

    @Test
    void ignoresTheContextPathAndMatrixVariablesWhenMatchingThePath() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/aaa/api/user/bob;v=2");
        request.setContextPath("/aaa");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(401);

        KeyValues tags = convention.getLowCardinalityKeyValues(
                new ServerRequestObservationContext(request, response));

        assertEquals("get_user", valueOf(tags, "api"));
    }

    @Test
    void recordsAnExceptionThatEscapedTheChainAsTheServerErrorItBecomes() {
        // The observation stops before the container's error dispatch sets 500,
        // so the response still reads 200. Counting that as a success would take
        // the failure off the dashboard altogether.
        ServerRequestObservationContext context =
                contextFor("POST", "/api/user", "/api/user", 200);
        context.setError(new IllegalStateException("filter blew up"));

        KeyValues tags = convention.getLowCardinalityKeyValues(context);

        assertEquals("create_user", valueOf(tags, "api"));
        assertEquals("500", valueOf(tags, "status"));
        assertEquals("SERVER_ERROR", valueOf(tags, "outcome"));
    }

    @Test
    void leavesAFailureThatWasAnsweredProperlyAlone() {
        // GlobalExceptionHandler turned the exception into a 400 before the
        // response left the dispatcher; that is the status the caller saw.
        ServerRequestObservationContext context =
                contextFor("POST", "/api/user", "/api/user", 400);
        context.setError(new IllegalArgumentException("bad input"));

        KeyValues tags = convention.getLowCardinalityKeyValues(context);

        assertEquals("400", valueOf(tags, "status"));
        assertEquals("CLIENT_ERROR", valueOf(tags, "outcome"));
    }

    @Test
    void leavesASuccessfulRequestAlone() {
        KeyValues tags = tagsFor("GET", "/api/user/bob", "/api/user/{user_name}", 200);

        assertEquals("200", valueOf(tags, "status"));
        assertEquals("SUCCESS", valueOf(tags, "outcome"));
    }
}
