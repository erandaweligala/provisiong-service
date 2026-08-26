package com.axonect.aee.template.baseapp.application.monitoring.health;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The decision itself, case by case.
 *
 * <p>Every branch here is a judgement someone will eventually be woken by, so each
 * one is pinned down separately rather than through a couple of happy paths.</p>
 */
class EndpointHealthEvaluatorTest {

    private EndpointHealthProperties properties;
    private EndpointHealthEvaluator evaluator;

    @BeforeEach
    void setUp() {
        properties = new EndpointHealthProperties();
        properties.setDegradedErrorRatio(0.01);
        properties.setUnhealthyErrorRatio(0.10);
        properties.setMinimumRequests(20);
        evaluator = new EndpointHealthEvaluator(properties);
    }

    @Test
    void cleanTrafficIsHealthy() {
        EndpointHealthVerdict verdict = evaluator.evaluate(true, List.of(), 100, 0);

        assertEquals(EndpointHealth.HEALTHY, verdict.health());
        assertEquals(EndpointHealthReason.OK, verdict.reason());
    }

    @Test
    void noTrafficIsHealthyAndSaysSo() {
        EndpointHealthVerdict verdict = evaluator.evaluate(true, List.of(), 0, 0);

        assertEquals(EndpointHealth.HEALTHY, verdict.health());
        assertEquals(EndpointHealthReason.IDLE, verdict.reason());
    }

    /**
     * The case the whole feature exists for: an endpoint nobody has called, whose
     * database has gone. Availability reports 100% here, because no request failed -
     * no request happened. Health has to disagree.
     */
    @Test
    void idleEndpointWithADownDependencyIsUnhealthy() {
        EndpointHealthVerdict verdict = evaluator.evaluate(true, List.of("database"), 0, 0);

        assertEquals(EndpointHealth.UNHEALTHY, verdict.health());
        assertEquals(EndpointHealthReason.DEPENDENCY_DOWN, verdict.reason());
        assertTrue(verdict.detail().contains("database"), verdict.detail());
    }

    @Test
    void anUnmappedEndpointIsUnhealthyEvenWhileServingCleanly() {
        EndpointHealthVerdict verdict = evaluator.evaluate(false, List.of(), 100, 0);

        assertEquals(EndpointHealth.UNHEALTHY, verdict.health());
        assertEquals(EndpointHealthReason.NOT_MAPPED, verdict.reason());
    }

    /** A missing handler explains a dependency-shaped symptom; the reverse is not true. */
    @Test
    void aMissingHandlerOutranksADownDependency() {
        EndpointHealthVerdict verdict = evaluator.evaluate(false, List.of("database"), 0, 0);

        assertEquals(EndpointHealthReason.NOT_MAPPED, verdict.reason());
    }

    @Test
    void aDownDependencyOutranksTheErrorsItIsCausing() {
        EndpointHealthVerdict verdict = evaluator.evaluate(true, List.of("kafka"), 100, 100);

        assertEquals(EndpointHealth.UNHEALTHY, verdict.health());
        assertEquals(EndpointHealthReason.DEPENDENCY_DOWN, verdict.reason());
    }

    @Test
    void errorsAtOrAboveTheUnhealthyRatioAreUnhealthy() {
        EndpointHealthVerdict verdict = evaluator.evaluate(true, List.of(), 100, 10);

        assertEquals(EndpointHealth.UNHEALTHY, verdict.health());
        assertEquals(EndpointHealthReason.ERRORS, verdict.reason());
    }

    @Test
    void errorsBetweenTheTwoRatiosAreDegraded() {
        EndpointHealthVerdict verdict = evaluator.evaluate(true, List.of(), 100, 5);

        assertEquals(EndpointHealth.DEGRADED, verdict.health());
        assertEquals(EndpointHealthReason.ERRORS, verdict.reason());
    }

    @Test
    void errorsBelowTheDegradedRatioStayHealthy() {
        EndpointHealthVerdict verdict = evaluator.evaluate(true, List.of(), 1000, 5);

        assertEquals(EndpointHealth.HEALTHY, verdict.health());
        assertEquals(EndpointHealthReason.OK, verdict.reason());
    }

    /**
     * One failure out of two requests is a 50% error ratio and means nothing. It is
     * still a failure, so it shows - as DEGRADED, which charts without paging.
     */
    @Test
    void aFailureUnderTheRequestFloorIsDegradedRatherThanCritical() {
        EndpointHealthVerdict verdict = evaluator.evaluate(true, List.of(), 2, 1);

        assertEquals(EndpointHealth.DEGRADED, verdict.health());
        assertEquals(EndpointHealthReason.ERRORS, verdict.reason());
        assertTrue(verdict.detail().contains("floor"), verdict.detail());
    }

    @Test
    void theRequestFloorDoesNotHideACleanEndpoint() {
        EndpointHealthVerdict verdict = evaluator.evaluate(true, List.of(), 2, 0);

        assertEquals(EndpointHealth.HEALTHY, verdict.health());
        assertEquals(EndpointHealthReason.OK, verdict.reason());
    }

    @Test
    void dependencyStateIsIgnoredWhenTheCheckIsTurnedOff() {
        properties.setUseDependencyState(false);

        EndpointHealthVerdict verdict = evaluator.evaluate(true, List.of("database"), 0, 0);

        assertEquals(EndpointHealth.HEALTHY, verdict.health());
        assertEquals(EndpointHealthReason.IDLE, verdict.reason());
    }
}
