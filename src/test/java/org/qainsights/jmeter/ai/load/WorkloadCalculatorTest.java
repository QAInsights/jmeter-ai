package org.qainsights.jmeter.ai.load;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkloadCalculatorTest {

    @Test
    void calculatesRateTargetUsingHeadroomAndParsesRequestedDefaults() {
        WorkloadSpec spec = WorkloadParser.parse("@load 200 rps, 10 min ramp-up, 1 hour soak");
        WorkloadPlan plan = WorkloadCalculator.calculate(new WorkloadInputs(
                WorkloadInputs.TargetType.REQUEST_RATE, spec.requestsPerSecond(), null,
                spec.responseMillis(), spec.thinkMillis(), spec.rampSeconds(), spec.holdSeconds(),
                spec.agents(), 4, "load", "checkout.jmx"));

        assertEquals(150, plan.threads());
        assertEquals(12_000.0, plan.rpm());
        assertEquals(600, plan.rampSeconds());
        assertEquals(4200, plan.durationSeconds());
        assertEquals(1, plan.assumptions().size());
        assertTrue(plan.assumptions().get(0).contains("Assumed 500 ms average response time"));
        assertEquals("jmeter -n -t checkout.jmx -Jload.threads=150 -Jload.rampup=600 "
                + "-Jload.duration=4200 -Jload.rpm=12000 -l results.jtl -e -o report", plan.cliCommand());
    }

    @Test
    void calculatesConcurrentUsersAndExpectedRateWithoutThroughputTimer() {
        WorkloadSpec spec = WorkloadParser.parse("@load 500 users, think time 3 s, response time 400 ms");
        WorkloadPlan plan = WorkloadCalculator.calculate(new WorkloadInputs(
                WorkloadInputs.TargetType.USERS, spec.users(), null, spec.responseMillis(),
                spec.thinkMillis(), spec.rampSeconds(), spec.holdSeconds(), spec.agents(),
                5, "load", "plan.jmx"));

        assertEquals(500, plan.threads());
        assertEquals(147.06, plan.expectedRequestsPerSecond());
        assertNull(plan.rpm());
        assertEquals(600, plan.holdSeconds());
        assertEquals(60, plan.rampSeconds());
        assertEquals(2, plan.assumptions().size());
        assertFalse(plan.cliCommand().contains(".rpm="));
        assertTrue(plan.cliCommand().contains("-Jload.think_ms=3000"));
    }

    @Test
    void warnsWhenUserCountCannotReachTheRequestedRateCap() {
        WorkloadSpec spec = WorkloadParser.parse("@load 50 users, 200 rps");
        WorkloadPlan plan = WorkloadCalculator.calculate(new WorkloadInputs(
                WorkloadInputs.TargetType.USERS, spec.users(), spec.requestsPerSecond(),
                null, null, null, null, null, 1, "load", "plan.jmx"));

        assertEquals(100.0, plan.expectedRequestsPerSecond());
        assertEquals(12_000.0, plan.rpm());
        assertEquals(1, plan.warnings().size());
        assertTrue(plan.warnings().get(0).contains("at most 100 requests/s"));
        assertTrue(plan.warnings().get(0).contains("Use at least 150 users."));
    }

    @Test
    void roundsThreadsUpAndDistributesThePlanAcrossAgents() {
        WorkloadSpec spec = WorkloadParser.parse("@load 200 rps, 10 min ramp-up, 1 hour soak across 4 agents");
        WorkloadPlan plan = WorkloadCalculator.calculate(new WorkloadInputs(
                WorkloadInputs.TargetType.REQUEST_RATE, spec.requestsPerSecond(), null,
                spec.responseMillis(), spec.thinkMillis(), spec.rampSeconds(), spec.holdSeconds(),
                spec.agents(), 4, "load", "shop.jmx"));

        assertEquals(150, plan.threads());
        assertEquals(38, plan.perAgentThreads());
        assertEquals(3000.0, plan.perAgentRpm());
        assertTrue(plan.cliCommand().contains(" -R agent1,agent2,agent3,agent4 "));
        assertTrue(plan.cliCommand().contains("-Gload.threads=38"));
        assertTrue(plan.cliCommand().contains("-Gload.rpm=3000"));
    }

    @Test
    void convertsIterationRateToRequestRateUsingSamplerCount() {
        WorkloadSpec spec = WorkloadParser.parse("@load 10 journeys per second");
        WorkloadPlan plan = WorkloadCalculator.calculate(new WorkloadInputs(
                WorkloadInputs.TargetType.ITERATION_RATE, spec.iterationsPerSecond(), null,
                null, null, null, null, null, 6, "load", "plan.jmx"));

        assertEquals(45, plan.threads());
        assertEquals(60.0, plan.targetRequestsPerSecond());
        assertEquals(3600.0, plan.rpm());
    }

    @Test
    void emitsWarningsForVeryLargeAndSteepWorkloads() {
        WorkloadPlan plan = WorkloadCalculator.calculate(new WorkloadInputs(
                WorkloadInputs.TargetType.REQUEST_RATE, 2000, null, 500L,
                null, 10L, 600L, null, 1, "load", "plan.jmx"));

        assertTrue(plan.warnings().stream().anyMatch(value -> value.contains("Above 1,000 threads")));
        assertTrue(plan.warnings().stream().anyMatch(value -> value.contains("Steep ramp-up")));
    }

    @Test
    void formatsWholeAndFractionalCliValuesWithoutUnnecessaryZeros() {
        WorkloadPlan plan = WorkloadCalculator.calculate(new WorkloadInputs(
                WorkloadInputs.TargetType.REQUEST_RATE, 1.2345, null, 500L,
                null, 60L, 600L, null, 1, "load", null));

        assertTrue(plan.cliCommand().contains("-t plan.jmx"));
        assertTrue(plan.cliCommand().contains("-Jload.rpm=74.07"));
        assertFalse(plan.cliCommand().contains("74.070"));
    }

    @Test
    void rejectsInvalidOrUnrepresentableInputs() {
        assertThrows(IllegalArgumentException.class, () -> WorkloadCalculator.calculate(new WorkloadInputs(
                WorkloadInputs.TargetType.REQUEST_RATE, 0, null, null, null, null, null, null,
                1, "load", "plan.jmx")));
        assertThrows(IllegalArgumentException.class, () -> WorkloadCalculator.calculate(new WorkloadInputs(
                WorkloadInputs.TargetType.USERS, 1, null, null, null, null, null, null,
                0, "load", "plan.jmx")));
    }
}
