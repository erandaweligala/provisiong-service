package com.axonect.aee.template.baseapp.application.monitoring.responsetime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestSpeedTest {

    private static final long SLOW = 1_000;
    private static final long VERY_SLOW = 3_000;

    @Test
    void aRequestUnderTheThresholdIsNormal() {
        assertEquals(RequestSpeed.NORMAL, RequestSpeed.of(999, SLOW, VERY_SLOW));
        assertFalse(RequestSpeed.NORMAL.isSlow());
    }

    @Test
    void theThresholdItselfCounts() {
        // At the threshold, not past it: a budget of one second is spent by a
        // request that took exactly one second.
        assertEquals(RequestSpeed.SLOW, RequestSpeed.of(1_000, SLOW, VERY_SLOW));
        assertEquals(RequestSpeed.VERY_SLOW, RequestSpeed.of(3_000, SLOW, VERY_SLOW));
    }

    @Test
    void bothSlowBandsAreReported() {
        assertTrue(RequestSpeed.SLOW.isSlow());
        assertTrue(RequestSpeed.VERY_SLOW.isSlow());
        assertEquals("slow", RequestSpeed.SLOW.label());
        assertEquals("very_slow", RequestSpeed.VERY_SLOW.label());
    }

    @Test
    void aVerySlowThresholdBelowTheSlowOneLosesDetailRatherThanInventingIt() {
        // Misconfigured: very-slow at 500ms, slow at 1s. A 600ms request is not
        // slow at all, so it must not come back as very slow.
        assertEquals(RequestSpeed.NORMAL, RequestSpeed.of(600, 1_000, 500));
        assertEquals(RequestSpeed.SLOW, RequestSpeed.of(1_200, 1_000, 500));
    }
}
