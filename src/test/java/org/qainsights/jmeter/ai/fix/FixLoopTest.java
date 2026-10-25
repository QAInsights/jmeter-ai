package org.qainsights.jmeter.ai.fix;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.jmeter.sampler.DebugSampler;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.apache.jmeter.samplers.SampleResult;
import org.qainsights.jmeter.ai.agent.jmeter.TestRunSummary;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FixLoopTest {
    @Test
    void alreadyPassingReturnsWithoutAgentRounds() {
        AtomicInteger rounds = new AtomicInteger();
        FixResult result = loop(List.of(summary(1, 0)), rounds, stableCensus(), () -> false)
                .run(new FixRequest(5, 120), null);
        assertEquals(FixResult.Status.ALREADY_PASSING, result.status());
        assertEquals(0, rounds.get());
    }

    @Test
    void fixesInTwoRoundsWhenRealRunsPass() {
        AtomicInteger rounds = new AtomicInteger();
        FixResult result = loop(List.of(summary(3, 2), summary(3, 1), summary(3, 0)),
                rounds, stableCensus(), () -> false).run(new FixRequest(5, 120), null);
        assertEquals(FixResult.Status.FIXED, result.status());
        assertEquals(2, result.roundsUsed());
        assertEquals(0, result.afterFailed());
    }

    @Test
    void partialProgressContinuesThroughRoundLimit() {
        AtomicInteger rounds = new AtomicInteger();
        FixResult result = loop(List.of(summary(6, 6), summary(6, 5), summary(6, 4),
                summary(6, 3), summary(6, 2), summary(6, 1)),
                rounds, stableCensus(), () -> false).run(new FixRequest(5, 120), null);
        assertEquals(FixResult.Status.PARTLY_FIXED, result.status());
        assertEquals(5, result.roundsUsed());
    }

    @Test
    void stopsAfterTwoConsecutiveNonImprovingRounds() {
        AtomicInteger rounds = new AtomicInteger();
        FixResult result = loop(List.of(summary(2, 2), summary(2, 2), summary(2, 3)),
                rounds, stableCensus(), () -> false).run(new FixRequest(5, 120), null);
        assertEquals(FixResult.Status.NOT_FIXED, result.status());
        assertEquals(2, result.roundsUsed());
    }

    @Test
    void guardViolationRestoresRoundAndCountsRollback() {
        AtomicInteger censusCalls = new AtomicInteger();
        PlanCensus before = census(1);
        PlanCensus after = census(0);
        AtomicInteger restores = new AtomicInteger();
        FixLoop.BackupStore backups = new FixLoop.BackupStore() {
            private int count;
            @Override public File backup() { return new File("backup-" + (++count) + ".jmx"); }
            @Override public void restore(File file) { restores.incrementAndGet(); }
        };
        FixLoop fixLoop = new FixLoop(timeout -> summary(1, 1), backups,
                () -> censusCalls.incrementAndGet() == 1 ? before : after,
                ignored -> null, () -> false);

        FixResult result = fixLoop.run(new FixRequest(1, 120), null);

        assertEquals(1, result.guardRollbacks());
        assertEquals(1, restores.get());
    }

    @Test
    void cancellationBetweenRunsReturnsCancelled() {
        AtomicInteger checks = new AtomicInteger();
        FixResult result = loop(List.of(summary(2, 1)), new AtomicInteger(), stableCensus(),
                () -> checks.incrementAndGet() >= 2).run(new FixRequest(5, 120), null);
        assertEquals(FixResult.Status.CANCELLED, result.status());
    }

    @Test
    void zeroSamplesReturnsNothingRan() {
        FixResult result = loop(List.of(summary(0, 0)), new AtomicInteger(), stableCensus(), () -> false)
                .run(new FixRequest(5, 120), null);
        assertEquals(FixResult.Status.NOTHING_RAN, result.status());
        assertFalse(result.reason().isBlank());
    }

    @Test
    void requestClampsRoundAndTimeoutLimits() {
        FixRequest request = new FixRequest(99, 1);
        assertEquals(10, request.maxRounds());
        assertEquals(10, request.timeoutSeconds());
    }

    private static FixLoop loop(List<TestRunSummary> runs, AtomicInteger rounds,
                                java.util.function.Supplier<PlanCensus> census, java.util.function.BooleanSupplier cancel) {
        Deque<TestRunSummary> pending = new ArrayDeque<>(runs);
        return new FixLoop(timeout -> pending.removeFirst(), new FixLoop.BackupStore() {
            private int backups;
            @Override public File backup() { return new File("backup-" + (++backups) + ".jmx"); }
            @Override public void restore(File file) { }
        }, census, message -> { rounds.incrementAndGet(); return null; }, cancel);
    }

    private static java.util.function.Supplier<PlanCensus> stableCensus() {
        return () -> census(1);
    }

    private static PlanCensus census(int requests) {
        TestPlan plan = new TestPlan("Test Plan");
        HashTree tree = new ListedHashTree();
        HashTree planTree = tree.add(plan);
        if (requests > 0) {
            planTree.add(new DebugSampler());
        }
        return PlanCensus.capture(tree);
    }

    private static TestRunSummary summary(int total, int failed) {
        java.util.ArrayList<SampleResult> samples = new java.util.ArrayList<>();
        for (int i = 0; i < total; i++) {
            SampleResult result = new SampleResult();
            result.setSampleLabel("Request " + i);
            result.setSuccessful(i >= failed);
            if (i < failed) {
                result.setResponseCode("500");
                result.setResponseMessage("Failure");
            }
            samples.add(result);
        }
        return TestRunSummary.of(samples, false, 1);
    }
}
