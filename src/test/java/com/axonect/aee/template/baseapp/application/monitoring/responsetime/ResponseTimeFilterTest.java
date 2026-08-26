package com.axonect.aee.template.baseapp.application.monitoring.responsetime;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The filter's whole job is that the request id is on the thread at the one
 * moment Micrometer records the duration, and off it again immediately after.
 * Both halves are tested here because both fail silently: without the first the
 * exemplars are simply absent, and without the second a pooled container thread
 * labels the next request with the previous request's id, which is worse than no
 * label at all.
 */
class ResponseTimeFilterTest {

    private static final String HEADER = "UUID";

    private final ResponseTimeFilter filter = new ResponseTimeFilter(HEADER, false, 1_000L);

    @AfterEach
    void clearRequestId() {
        RequestIdHolder.clear();
    }

    @Test
    void publishesTheSuppliedRequestIdForTheDurationOfTheChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/user/alice");
        request.addHeader(HEADER, "caller-supplied-id");
        String[] seenInsideChain = new String[1];

        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seenInsideChain[0] = RequestIdHolder.get());

        assertEquals("caller-supplied-id", seenInsideChain[0]);
        assertNull(RequestIdHolder.get(), "the id must not outlive the request");
    }

    @Test
    void generatesAnIdWhenTheCallerSuppliesNone() throws Exception {
        String[] seenInsideChain = new String[1];

        filter.doFilter(new MockHttpServletRequest("GET", "/api/user/alice"), new MockHttpServletResponse(),
                (req, res) -> seenInsideChain[0] = RequestIdHolder.get());

        assertNotNull(seenInsideChain[0]);
        assertEquals(36, seenInsideChain[0].length(), "a generated id is a UUID");
    }

    /**
     * The id is cleared even when the handler blows up. A container thread that
     * kept it would mislabel whatever request it picked up next.
     */
    @Test
    void clearsTheRequestIdWhenTheChainThrows() {
        FilterChain exploding = (req, res) -> {
            throw new IOException("downstream failure");
        };

        assertThrows(IOException.class, () -> filter.doFilter(new MockHttpServletRequest("GET", "/api/user/alice"),
                new MockHttpServletResponse(), exploding));
        assertNull(RequestIdHolder.get());
    }

    /**
     * An async request passes through this filter twice - once on the container
     * thread, once on the dispatch that completes it - and the timer is stopped on
     * the second. Both passes have to name the same request or the exemplar points
     * at an id that appears nowhere in the logs.
     */
    @Test
    void reusesTheSameIdAcrossAnAsyncDispatch() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/services/activate");
        String[] firstPass = new String[1];
        String[] secondPass = new String[1];

        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> firstPass[0] = RequestIdHolder.get());
        // OncePerRequestFilter clears its own guard attribute as it unwinds, so the
        // second call models the container dispatching the same request again.
        request.setDispatcherType(DispatcherType.ASYNC);
        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> secondPass[0] = RequestIdHolder.get());

        assertNotNull(firstPass[0]);
        assertEquals(firstPass[0], secondPass[0]);
        assertEquals(firstPass[0], request.getAttribute(ResponseTimeFilter.REQUEST_ID_ATTRIBUTE));
    }

    @Test
    void asyncDispatchIsNotSkipped() {
        assertFalse(filter.shouldNotFilterAsyncDispatch(),
                "the timer is stopped on the dispatch thread, so the id must be set there too");
    }

    /**
     * The header is caller-controlled and its value is written straight into the
     * scrape output. A quote or a newline reaching that output would break the
     * parse of everything after it.
     */
    @Test
    void stripsCharactersThatWouldCorruptTheScrapeOutput() {
        assertEquals("evilinjected1", ResponseTimeFilter.sanitize("evil\" ,injected=\"1\n"));
        assertEquals("a.b_c:d-1", ResponseTimeFilter.sanitize("a.b_c:d-1"));
        assertNull(ResponseTimeFilter.sanitize("\"\"\n"), "nothing usable left means fall back to a UUID");
        assertNull(ResponseTimeFilter.sanitize(null));
    }

    @Test
    void capsTheIdLengthSoAnExemplarStaysInsideItsLabelBudget() {
        String oversized = "x".repeat(500);

        String sanitized = ResponseTimeFilter.sanitize(oversized);

        assertEquals(48, sanitized.length());
    }

    @Test
    void aHostileHeaderIsReplacedRatherThanUsed() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/user/alice");
        request.addHeader(HEADER, "\"\n");
        String[] seenInsideChain = new String[1];

        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seenInsideChain[0] = RequestIdHolder.get());

        assertEquals(36, seenInsideChain[0].length());
        assertFalse(seenInsideChain[0].contains("\""));
    }

    @Test
    void servesEveryPathRatherThanOnlyTheLoggedOnes() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertTrue(request.getAttribute(ResponseTimeFilter.REQUEST_ID_ATTRIBUTE) instanceof String);
    }
}
