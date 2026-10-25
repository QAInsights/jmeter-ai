package org.qainsights.jmeter.ai.fix;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
        AtomicInteger discards = new AtomicInteger();
        FixLoop.BackupStore backups = new FixLoop.BackupStore() {
            private int count;
            @Override public File backup() { return new File("backup-" + (++count) + ".jmx"); }
            @Override public File roundBackup() { return new File("round-" + (++count) + ".jmx"); }
            @Override public void restore(File file) { restores.incrementAndGet(); }
            @Override public void discard(File file) { discards.incrementAndGet(); }
        };
        FixLoop fixLoop = new FixLoop(timeout -> summary(1, 1), backups,
                () -> censusCalls.incrementAndGet() == 1 ? before : after,
                ignored -> null, () -> false);

        FixResult result = fixLoop.run(new FixRequest(1, 120), null);

        assertEquals(1, result.guardRollbacks());
        assertEquals(1, restores.get());
        assertEquals(1, discards.get());
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
    void firstCheckTimedOutWithNoFailuresIsNotPassing() {
        FixResult result = loop(List.of(summary(2, 0, true)), new AtomicInteger(), stableCensus(), () -> false)
                .run(new FixRequest(5, 17), null);

        assertEquals(FixResult.Status.NOT_FIXED, result.status());
        assertEquals(0, result.afterFailed());
        assertEquals(2, result.afterTotal());
        assertEquals("The check run timed out after 17 s before every request ran. "
                + "Raise jmeter.ai.fix.timeout.seconds.", result.reason());
    }

    @Test
    void firstCheckTimeoutKeepsPartialFailures() {
        FixResult result = loop(List.of(summary(3, 1, true)), new AtomicInteger(), stableCensus(), () -> false)
                .run(new FixRequest(5, 19), null);

        assertEquals(FixResult.Status.NOT_FIXED, result.status());
        assertEquals(1, result.afterFailed());
        assertEquals(1, result.remainingFailures().size());
        assertTrue(result.reason().contains("timed out after 19 s"));
    }

    @Test
    void timedOutPostRoundCheckIsNotProgress() {
        FixResult result = loop(List.of(summary(3, 3), summary(2, 1, true)),
                new AtomicInteger(), stableCensus(), () -> false).run(new FixRequest(1, 120), null);

        assertEquals(FixResult.Status.NOT_FIXED, result.status());
        assertEquals(3, result.afterFailed());
        assertEquals(3, result.afterTotal());
    }

    @Test
    void zeroSamplePostRoundCheckIsNotProgress() {
        FixResult result = loop(List.of(summary(3, 3), summary(0, 0)),
                new AtomicInteger(), stableCensus(), () -> false).run(new FixRequest(1, 120), null);

        assertEquals(FixResult.Status.NOT_FIXED, result.status());
        assertEquals(3, result.afterFailed());
        assertEquals(3, result.afterTotal());
    }

    @Test
    void zeroSampleAfterProgressComparesWithLastCheckThatHadSamples() {
        FixResult result = loop(List.of(summary(3, 3), summary(3, 1), summary(0, 0)),
                new AtomicInteger(), stableCensus(), () -> false).run(new FixRequest(2, 120), null);

        assertEquals(FixResult.Status.PARTLY_FIXED, result.status());
        assertEquals(1, result.afterFailed());
        assertEquals(3, result.afterTotal());
    }

    @Test
    void failedPageAssertionAfterInitialFixRequiresAnotherRepairRound() {
        Deque<TestRunSummary> runs = new ArrayDeque<>(List.of(
                summary(2, 1), summary(2, 0), summary(2, 1), summary(2, 0)));
        AtomicInteger rounds = new AtomicInteger();
        AtomicInteger assertionCalls = new AtomicInteger();
        FixLoop fixLoop = new FixLoop(timeout -> runs.removeFirst(), noOpBackups(), stableCensus(),
                ignored -> {
                    rounds.incrementAndGet();
                    return null;
                }, () -> false, new FixPrompt(), observations ->
                        assertionCalls.getAndIncrement() == 1 ? 1 : 0);

        FixResult result = fixLoop.run(new FixRequest(3, 120), null);

        assertEquals(FixResult.Status.FIXED, result.status());
        assertEquals(2, rounds.get());
        assertEquals(3, assertionCalls.get());
        assertEquals(0, result.afterFailed());
    }

    @Test
    void repairsMultiUserFailuresAndRequiresBothChecksToPass() {
        AtomicInteger rounds = new AtomicInteger();
        FixLoop loop = multiUserLoop(List.of(summary(2, 0), summary(2, 0)),
                List.of(summary(4, 2), summary(4, 0)), rounds, new AtomicInteger());

        FixResult result = loop.run(new FixRequest(3, 120, true), null);

        assertEquals(FixResult.Status.FIXED, result.status());
        assertEquals(1, rounds.get());
        assertEquals(2, result.concurrencyBeforeFailed());
        assertEquals(0, result.concurrencyAfterFailed());
    }

    @Test
    void stagnantMultiUserFailuresReturnPartlyFixed() {
        AtomicInteger rounds = new AtomicInteger();
        FixLoop loop = multiUserLoop(List.of(summary(2, 0), summary(2, 0), summary(2, 0)),
                List.of(summary(4, 2), summary(4, 2), summary(4, 2)), rounds, new AtomicInteger());

        FixResult result = loop.run(new FixRequest(5, 120, true), null);

        assertEquals(FixResult.Status.PARTLY_FIXED, result.status());
        assertEquals(FixLoop.CONCURRENCY_PARTIAL_REASON, result.reason());
        assertEquals(2, rounds.get());
        assertEquals(2, result.afterFailed());
    }

    @Test
    void multiUserRepairThatRegressesSingleUserIsRolledBack() {
        AtomicInteger rounds = new AtomicInteger();
        AtomicInteger restores = new AtomicInteger();
        FixLoop loop = multiUserLoop(List.of(summary(2, 0), summary(2, 1)),
                List.of(summary(4, 2)), rounds, restores);

        FixResult result = loop.run(new FixRequest(1, 120, true), null);

        assertEquals(FixResult.Status.PARTLY_FIXED, result.status());
        assertEquals(1, result.guardRollbacks());
        assertEquals(1, restores.get());
    }

    @Test
    void disablingConcurrencyCheckPreservesSingleUserBehavior() {
        AtomicInteger multiRuns = new AtomicInteger();
        FixLoop.SingleIterationRunner runner = new FixLoop.SingleIterationRunner() {
            @Override public TestRunSummary run(long timeout) { return summary(1, 0); }
            @Override public TestRunSummary runIterations(long timeout, int threads, int loops) {
                multiRuns.incrementAndGet();
                return summary(4, 4);
            }
        };
        FixLoop loop = new FixLoop(runner, noOpBackups(), stableCensus(), ignored -> null, () -> false);

        FixResult result = loop.run(new FixRequest(1, 120, false), null);

        assertEquals(FixResult.Status.ALREADY_PASSING, result.status());
        assertEquals(0, multiRuns.get());
    }

    @Test
    void requestClampsRoundAndTimeoutLimits() {
        FixRequest request = new FixRequest(99, 1);
        assertEquals(10, request.maxRounds());
        assertEquals(10, request.timeoutSeconds());
    }

    @Test
    void firstCheckFailureReturnsNothingRanWithReason() {
        FixLoop fixLoop = new FixLoop(timeout -> {
            throw new org.qainsights.jmeter.ai.agent.jmeter.TestPlanRunner.TestExecutionException(
                    "engine unavailable");
        }, noOpBackups(), stableCensus(), ignored -> null, () -> false);

        FixResult result = fixLoop.run(new FixRequest(1, 120), null);

        assertEquals(FixResult.Status.NOTHING_RAN, result.status());
        assertTrue(result.reason().contains("engine unavailable"));
    }

    @Test
    void providerFailureRestoresRoundAndReturnsReason() {
        AtomicInteger restores = new AtomicInteger();
        AtomicInteger discards = new AtomicInteger();
        FixLoop.BackupStore backups = new FixLoop.BackupStore() {
            private int count;
            @Override public File backup() { return new File("backup-" + (++count) + ".jmx"); }
            @Override public File roundBackup() { return new File("round-" + (++count) + ".jmx"); }
            @Override public void restore(File file) { restores.incrementAndGet(); }
            @Override public void discard(File file) { discards.incrementAndGet(); }
        };
        FixLoop fixLoop = new FixLoop(timeout -> summary(2, 1), backups, stableCensus(),
                ignored -> { throw new IllegalStateException("provider unavailable"); }, () -> false);

        FixResult result = fixLoop.run(new FixRequest(1, 120), null);

        assertEquals(FixResult.Status.NOT_FIXED, result.status());
        assertTrue(result.reason().contains("provider unavailable"));
        assertEquals(1, restores.get());
        assertEquals(1, discards.get());
    }

    @Test
    void checkFailureAfterRoundRestoresAndReturnsReason() {
        AtomicInteger runs = new AtomicInteger();
        AtomicInteger restores = new AtomicInteger();
        AtomicInteger discards = new AtomicInteger();
        FixLoop.BackupStore backups = new FixLoop.BackupStore() {
            private int count;
            @Override public File backup() { return new File("backup-" + (++count) + ".jmx"); }
            @Override public File roundBackup() { return new File("round-" + (++count) + ".jmx"); }
            @Override public void restore(File file) { restores.incrementAndGet(); }
            @Override public void discard(File file) { discards.incrementAndGet(); }
        };
        FixLoop fixLoop = new FixLoop(timeout -> {
            if (runs.incrementAndGet() == 1) {
                return summary(2, 1);
            }
            throw new org.qainsights.jmeter.ai.agent.jmeter.TestPlanRunner.TestExecutionException(
                    "engine failed");
        }, backups, stableCensus(), ignored -> null, () -> false);

        FixResult result = fixLoop.run(new FixRequest(1, 120), null);

        assertEquals(FixResult.Status.NOT_FIXED, result.status());
        assertTrue(result.reason().contains("engine failed"));
        assertEquals(1, restores.get());
        assertEquals(1, discards.get());
    }

    @Test
    void interruptionDuringRoundRestoresAndReturnsCancelled() throws Exception {
        CountDownLatch enteredRound = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger restores = new AtomicInteger();
        AtomicInteger discards = new AtomicInteger();
        AtomicReference<FixResult> result = new AtomicReference<>();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        FixLoop.BackupStore backups = new FixLoop.BackupStore() {
            private int count;
            @Override public File backup() { return new File("backup-" + (++count) + ".jmx"); }
            @Override public File roundBackup() { return new File("round-" + (++count) + ".jmx"); }
            @Override public void restore(File file) { restores.incrementAndGet(); }
            @Override public void discard(File file) { discards.incrementAndGet(); }
        };
        FixLoop fixLoop = new FixLoop(timeout -> summary(2, 1), backups, stableCensus(), ignored -> {
            enteredRound.countDown();
            try {
                new CountDownLatch(1).await();
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }, cancelled::get);
        Thread worker = new Thread(() -> {
            result.set(fixLoop.run(new FixRequest(1, 120), null));
            interruptRestored.set(Thread.currentThread().isInterrupted());
        });

        worker.start();
        assertTrue(enteredRound.await(2, TimeUnit.SECONDS));
        cancelled.set(true);
        worker.interrupt();
        worker.join(2000);

        assertFalse(worker.isAlive());
        assertEquals(FixResult.Status.CANCELLED, result.get().status());
        assertTrue(result.get().hasUndoableChanges());
        assertTrue(interruptRestored.get());
        assertEquals(1, restores.get());
        assertEquals(1, discards.get());
    }

    private static FixLoop loop(List<TestRunSummary> runs, AtomicInteger rounds,
                                java.util.function.Supplier<PlanCensus> census, java.util.function.BooleanSupplier cancel) {
        Deque<TestRunSummary> pending = new ArrayDeque<>(runs);
        return new FixLoop(timeout -> pending.removeFirst(), new FixLoop.BackupStore() {
            private int backups;
            @Override public File backup() { return new File("backup-" + (++backups) + ".jmx"); }
            @Override public File roundBackup() { return new File("round-" + (++backups) + ".jmx"); }
            @Override public void restore(File file) { }
            @Override public void discard(File file) { }
        }, census, message -> { rounds.incrementAndGet(); return null; }, cancel);
    }

    private static java.util.function.Supplier<PlanCensus> stableCensus() {
        return () -> census(1);
    }

    private static FixLoop.BackupStore noOpBackups() {
        return new FixLoop.BackupStore() {
            private int count;
            @Override public File backup() { return new File("backup-" + (++count) + ".jmx"); }
            @Override public File roundBackup() { return new File("round-" + (++count) + ".jmx"); }
            @Override public void restore(File file) { }
            @Override public void discard(File file) { }
        };
    }

    private static FixLoop multiUserLoop(List<TestRunSummary> singles, List<TestRunSummary> multis,
                                         AtomicInteger rounds, AtomicInteger restores) {
        Deque<TestRunSummary> singleRuns = new ArrayDeque<>(singles);
        Deque<TestRunSummary> multiRuns = new ArrayDeque<>(multis);
        FixLoop.SingleIterationRunner runner = new FixLoop.SingleIterationRunner() {
            @Override public TestRunSummary run(long timeout) { return singleRuns.removeFirst(); }
            @Override public TestRunSummary runIterations(long timeout, int threads, int loops) {
                return multiRuns.removeFirst();
            }
        };
        FixLoop.BackupStore backups = new FixLoop.BackupStore() {
            private int count;
            @Override public File backup() { return new File("backup-" + (++count) + ".jmx"); }
            @Override public File roundBackup() { return new File("round-" + (++count) + ".jmx"); }
            @Override public void restore(File file) { restores.incrementAndGet(); }
            @Override public void discard(File file) { }
        };
        return new FixLoop(runner, backups, stableCensus(), ignored -> {
            rounds.incrementAndGet();
            return null;
        }, () -> false, new FixPrompt(), ignored -> 0);
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
        return summary(total, failed, false);
    }

    private static TestRunSummary summary(int total, int failed, boolean timedOut) {
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
        return TestRunSummary.of(samples, timedOut, 1);
    }
}
