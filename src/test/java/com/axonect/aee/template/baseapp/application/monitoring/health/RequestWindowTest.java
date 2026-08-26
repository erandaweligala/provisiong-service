package com.axonect.aee.template.baseapp.application.monitoring.health;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sliding window that turns Micrometer's ever-climbing counters into "what
 * happened in the last five minutes".
 */
class RequestWindowTest {

    private static final long WINDOW_MS = 300_000;

    @Test
    void oneSampleIsNotAWindow() {
        RequestWindow window = new RequestWindow(WINDOW_MS);
        window.observe(0, 100, 5);

        assertEquals(0, window.requests());
        assertEquals(0, window.errors());
        assertEquals(0, window.errorRatio());
    }

    @Test
    void reportsTheDifferenceBetweenTheEndsOfTheWindow() {
        RequestWindow window = new RequestWindow(WINDOW_MS);
        window.observe(0, 100, 5);
        window.observe(60_000, 160, 11);

        assertEquals(60, window.requests());
        assertEquals(6, window.errors());
        assertEquals(0.1, window.errorRatio(), 1e-9);
    }

    /** Traffic that has aged out stops counting. */
    @Test
    void samplesOlderThanTheWindowAreDropped() {
        RequestWindow window = new RequestWindow(WINDOW_MS);
        window.observe(0, 0, 0);
        window.observe(200_000, 1000, 1000);
        window.observe(400_000, 1100, 1000);
        window.observe(600_000, 1200, 1000);

        // The burst of 1000 errors at t=200s has aged out of the window at t=600s.
        assertEquals(0, window.errors());
        // The baseline is the newest sample at or before the cutoff - t=200s, not
        // t=400s - so the window covers 400s of traffic rather than 200s. Erring
        // long is deliberate: the other way round undercounts a window that has
        // just rolled, which reads as an endpoint quietly going idle.
        assertEquals(200, window.requests());
        assertTrue(window.spanMs() >= WINDOW_MS,
                "the window must still span at least windowMs, was " + window.spanMs());
    }

    /**
     * The window is allowed to be longer than windowMs, never shorter: dropping the
     * baseline the moment it ages out would leave nothing to measure against.
     */
    @Test
    void theWindowNeverCollapsesBelowItsConfiguredLength() {
        RequestWindow window = new RequestWindow(WINDOW_MS);
        for (int minute = 0; minute <= 20; minute++) {
            window.observe(minute * 60_000L, minute * 10L, 0);
            if (minute > 0) {
                assertTrue(window.spanMs() > 0, "window collapsed at minute " + minute);
            }
        }
        assertTrue(window.spanMs() >= WINDOW_MS, "was " + window.spanMs());
    }

    /** A re-registered meter restarts at zero; there is no honest delta to report. */
    @Test
    void countersThatGoBackwardsReadAsNoTraffic() {
        RequestWindow window = new RequestWindow(WINDOW_MS);
        window.observe(0, 500, 20);
        window.observe(60_000, 3, 0);

        assertEquals(0, window.requests());
        assertEquals(0, window.errors());
    }
}
