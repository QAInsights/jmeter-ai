package org.qainsights.jmeter.ai.fix;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.qainsights.jmeter.ai.agent.loop.AgentLoop;
import org.qainsights.jmeter.ai.agent.jmeter.TestPlanRunner;
import org.qainsights.jmeter.ai.agent.jmeter.TestRunSummary;
import org.qainsights.jmeter.ai.agent.jmeter.TestRunSummary.Failure;
import org.qainsights.jmeter.ai.agent.jmeter.TestRunSummary.Observation;
import org.qainsights.jmeter.ai.utils.ProviderErrors;
import org.qainsights.jmeter.ai.telemetry.Telemetry;
import org.qainsights.jmeter.ai.telemetry.TelemetryFeature;

/** GUI-free orchestration for Make it pass. Every repair is checked by a real JMeter run. */
public final class FixLoop {
    public static final String CONCURRENCY_PARTIAL_REASON =
            "Passes with 1 user, fails with 2 users × 2 loops";

    @FunctionalInterface
    public interface AssertionInstaller {
        int addMissing(List<Observation> observations);
    }

    @FunctionalInterface
    public interface SingleIterationRunner {
        TestRunSummary run(long timeoutSeconds);

        default TestRunSummary runIterations(long timeoutSeconds, int threads, int loops) {
            return run(timeoutSeconds);
        }
    }

    public interface BackupStore {
        File backup();
        File roundBackup();
        void restore(File file);
        void discard(File file);
    }

    private final SingleIterationRunner runner;
    private final BackupStore backups;
    private final Supplier<PlanCensus> census;
    private final Function<String, AgentLoop.AgentResult> fixRound;
    private final BooleanSupplier cancelled;
    private final FixPrompt prompt;
    private final AssertionInstaller assertionInstaller;
    private Map<String, PlanDiff.ElementSnapshot> startSnapshot = Map.of();
    private final List<PlanDiff> roundDiffs = new ArrayList<>();
    private final List<String> roundSummaries = new ArrayList<>();
    private TestRunSummary concurrencyBefore;
    private TestRunSummary concurrencyAfter;

    public FixLoop(SingleIterationRunner runner, BackupStore backups, Supplier<PlanCensus> census,
                   Function<String, AgentLoop.AgentResult> fixRound, BooleanSupplier cancelled) {
        this(runner, backups, census, fixRound, cancelled, new FixPrompt());
    }

    public FixLoop(SingleIterationRunner runner, BackupStore backups, Supplier<PlanCensus> census,
                   Function<String, AgentLoop.AgentResult> fixRound, BooleanSupplier cancelled, FixPrompt prompt) {
        this(runner, backups, census, fixRound, cancelled, prompt, new FixAssertions()::addMissing);
    }

    public FixLoop(SingleIterationRunner runner, BackupStore backups, Supplier<PlanCensus> census,
                   Function<String, AgentLoop.AgentResult> fixRound, BooleanSupplier cancelled, FixPrompt prompt,
                   AssertionInstaller assertionInstaller) {
        this.runner = runner;
        this.backups = backups;
        this.census = census;
        this.fixRound = fixRound;
        this.cancelled = cancelled == null ? () -> false : cancelled;
        this.prompt = prompt;
        this.assertionInstaller = assertionInstaller == null ? ignored -> 0 : assertionInstaller;
    }

    public FixResult run(FixRequest request, Consumer<String> progress) {
        Consumer<String> sink = progress == null ? ignored -> { } : progress;
        File overall = backups.backup();
        startSnapshot = PlanDiff.liveSnapshot();
        roundDiffs.clear();
        roundSummaries.clear();
        concurrencyBefore = null;
        concurrencyAfter = null;
        if (isCancelled()) {
            return result(FixResult.Status.CANCELLED, null, null, 0, 0, overall, List.of(), "Cancelled.");
        }
        TestRunSummary current;
        try {
            current = runSingleCheck(request.timeoutSeconds());
        } catch (RuntimeException e) {
            if (isCancelled() || causedByInterruption(e)) {
                if (causedByInterruption(e)) {
                    Thread.currentThread().interrupt();
                }
                return result(FixResult.Status.CANCELLED, null, null, 0, 0, overall, List.of(), "Cancelled.");
            }
            return result(FixResult.Status.NOTHING_RAN, null, null, 0, 0, overall, List.of(),
                    checkFailureReason(e));
        }
        if (isCancelled()) {
            return result(FixResult.Status.CANCELLED, current, current, 0, 0, overall, List.of(), "Cancelled.");
        }
        if (current.isTimedOut()) {
            return result(FixResult.Status.NOT_FIXED, current, current, 0, 0, overall, List.of(),
                    timeoutReason(request.timeoutSeconds()));
        }
        if (current.getTotalSamples() == 0) {
            return result(FixResult.Status.NOTHING_RAN, current, current, 0, 0, overall, List.of(),
                    "JMeter did not produce any samples.");
        }
        if (passes(current)) {
            if (!request.concurrencyCheck()) {
                return result(FixResult.Status.ALREADY_PASSING, current, current, 0, 0, overall, List.of(), "");
            }
            try {
                TestRunSummary multiUser = runConcurrencyCheck(request);
                if (passes(multiUser)) {
                    return result(FixResult.Status.ALREADY_PASSING, current, multiUser, 0, 0, overall, List.of(), "");
                }
                current = multiUser;
            } catch (RuntimeException e) {
                return result(FixResult.Status.NOT_FIXED, current, current, 0, 0, overall, List.of(),
                        checkFailureReason(e));
            }
        }

        boolean concurrencyMode = concurrencyBefore != null && !passes(concurrencyAfter);
        FixListeners.ensurePresent();
        addAssertions(current.getObservations());
        TestRunSummary before = current;
        List<String> summaries = new ArrayList<>();
        int guardRollbacks = 0;
        int used = 0;
        int bestFailures = current.getFailureCount();
        int stagnantRounds = 0;
        for (int round = 1; round <= request.maxRounds(); round++) {
            if (isCancelled()) {
                break;
            }
            int targetFailures = current.getFailureCount();
            int targetTotal = current.getTotalSamples();
            File roundBackup = backups.roundBackup();
            try {
                if (isCancelled()) {
                    break;
                }
                PlanCensus beforeCensus = census.get();
                Map<String, PlanDiff.ElementSnapshot> roundBefore = PlanDiff.liveSnapshot();
                if (isCancelled()) {
                    break;
                }
                sink.accept("Round " + round + " of " + request.maxRounds() + ": "
                        + current.getFailureCount() + " of " + current.getTotalSamples() + " requests failing");
                AgentLoop.AgentResult agentResult;
                try {
                    agentResult = fixRound.apply(concurrencyMode
                            ? prompt.buildConcurrency(current) : prompt.build(current));
                } catch (RuntimeException e) {
                    used = round;
                    boolean interrupted = causedByInterruption(e);
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    restoreRoundBackup(roundBackup);
                    if (isCancelled() || interrupted) {
                        return result(FixResult.Status.CANCELLED, before, current, round, guardRollbacks,
                                overall, summaries, "Cancelled.");
                    }
                    return result(status(before, current), before, current, round, guardRollbacks,
                            overall, summaries, ProviderErrors.describe(e, "running Make it pass", true));
                }
                used = round;
                if (agentResult != null) {
                    summaries.add(agentResult.getFinalText());
                }
                roundDiffs.add(PlanDiff.between(roundBefore, PlanDiff.liveSnapshot()));
                if (isCancelled()) {
                    restoreRoundBackup(roundBackup);
                    break;
                }
                List<String> violations = beforeCensus.violations(census.get());
                if (isCancelled()) {
                    restoreRoundBackup(roundBackup);
                    break;
                }
                if (!violations.isEmpty()) {
                    restoreRoundBackup(roundBackup);
                    guardRollbacks++;
                    Telemetry.record(TelemetryFeature.FIX_GUARD_ROLLBACK);
                    violations.forEach(v -> sink.accept("Safety guard: " + v + " Restored this round."));
                    stagnantRounds++;
                } else {
                    if (isCancelled()) {
                        restoreRoundBackup(roundBackup);
                        break;
                    }
                    TestRunSummary checked;
                    try {
                        checked = runSingleCheck(request.timeoutSeconds());
                    } catch (RuntimeException e) {
                        boolean interrupted = causedByInterruption(e);
                        if (interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        restoreRoundBackup(roundBackup);
                        if (isCancelled() || interrupted) {
                            return result(FixResult.Status.CANCELLED, before, current, used, guardRollbacks,
                                    overall, summaries, "Cancelled.");
                        }
                        return result(status(before, current), before, current, used, guardRollbacks,
                                overall, summaries, checkFailureReason(e));
                    }
                    if (isCancelled()) {
                        restoreRoundBackup(roundBackup);
                        break;
                    }
                    if (concurrencyMode) {
                        if (!passes(checked)) {
                            restoreRoundBackup(roundBackup);
                            guardRollbacks++;
                            Telemetry.record(TelemetryFeature.FIX_GUARD_ROLLBACK);
                            stagnantRounds++;
                            continue;
                        }
                        try {
                            TestRunSummary multiUser = runConcurrencyCheck(request);
                            if (passes(multiUser)) {
                                int added = addAssertions(multiUser.getObservations());
                                if (added == 0) {
                                    return result(FixResult.Status.FIXED, before, multiUser, used,
                                            guardRollbacks, overall, summaries, "");
                                }
                                TestRunSummary verifiedSingle = runSingleCheck(request.timeoutSeconds());
                                if (passes(verifiedSingle)) {
                                    TestRunSummary verifiedMulti = runConcurrencyCheck(request);
                                    if (passes(verifiedMulti)) {
                                        return result(FixResult.Status.FIXED, before, verifiedMulti, used,
                                                guardRollbacks, overall, summaries, "");
                                    }
                                    current = verifiedMulti;
                                } else {
                                    restoreRoundBackup(roundBackup);
                                    guardRollbacks++;
                                    Telemetry.record(TelemetryFeature.FIX_GUARD_ROLLBACK);
                                    stagnantRounds++;
                                    continue;
                                }
                            } else {
                                current = multiUser;
                            }
                        } catch (RuntimeException e) {
                            boolean interrupted = causedByInterruption(e);
                            if (interrupted) {
                                Thread.currentThread().interrupt();
                            }
                            restoreRoundBackup(roundBackup);
                            if (isCancelled() || interrupted) {
                                return result(FixResult.Status.CANCELLED, before, current, used, guardRollbacks,
                                        overall, summaries, "Cancelled.");
                            }
                            return result(FixResult.Status.PARTLY_FIXED, before, current, used, guardRollbacks,
                                    overall, summaries, CONCURRENCY_PARTIAL_REASON);
                        }
                        if (passes(concurrencyAfter)) {
                            return result(FixResult.Status.FIXED, before, concurrencyAfter, used,
                                    guardRollbacks, overall, summaries, "");
                        }
                        if (current.getFailureCount() < bestFailures) {
                            bestFailures = current.getFailureCount();
                            stagnantRounds = 0;
                        } else {
                            stagnantRounds++;
                        }
                    } else if (passes(checked)) {
                        int added = addAssertions(checked.getObservations());
                        if (added > 0) {
                            try {
                                checked = runSingleCheck(request.timeoutSeconds());
                            } catch (RuntimeException e) {
                                boolean interrupted = causedByInterruption(e);
                                if (interrupted) {
                                    Thread.currentThread().interrupt();
                                }
                                restoreRoundBackup(roundBackup);
                                if (isCancelled() || interrupted) {
                                    return result(FixResult.Status.CANCELLED, before, current, used,
                                            guardRollbacks, overall, summaries, "Cancelled.");
                                }
                                return result(status(before, current), before, current, used, guardRollbacks,
                                        overall, summaries, checkFailureReason(e));
                            }
                        }
                        if (isCancelled()) {
                            restoreRoundBackup(roundBackup);
                            break;
                        }
                        if (passes(checked)) {
                            if (!request.concurrencyCheck()) {
                                return result(FixResult.Status.FIXED, before, checked, used, guardRollbacks,
                                        overall, summaries, "");
                            }
                            try {
                                TestRunSummary multiUser = runConcurrencyCheck(request);
                                if (passes(multiUser)) {
                                    int postMultiAssertions = addAssertions(multiUser.getObservations());
                                    if (postMultiAssertions > 0) {
                                        checked = runSingleCheck(request.timeoutSeconds());
                                        if (!passes(checked)) {
                                            current = checked;
                                            continue;
                                        }
                                        multiUser = runConcurrencyCheck(request);
                                    }
                                    if (passes(multiUser)) {
                                        return result(FixResult.Status.FIXED, before, multiUser, used,
                                                guardRollbacks, overall, summaries, "");
                                    }
                                }
                                current = multiUser;
                                concurrencyMode = true;
                                bestFailures = current.getFailureCount();
                                stagnantRounds = 0;
                                continue;
                            } catch (RuntimeException e) {
                                boolean interrupted = causedByInterruption(e);
                                if (interrupted) {
                                    Thread.currentThread().interrupt();
                                }
                                restoreRoundBackup(roundBackup);
                                if (isCancelled() || interrupted) {
                                    return result(FixResult.Status.CANCELLED, before, current, used,
                                            guardRollbacks, overall, summaries, "Cancelled.");
                                }
                                return result(status(before, current), before, current, used, guardRollbacks,
                                        overall, summaries, checkFailureReason(e));
                            }
                        }
                    }
                    if (!concurrencyMode) {
                        if (checked.isTimedOut() || checked.getTotalSamples() == 0) {
                            stagnantRounds++;
                        } else {
                            current = checked;
                            if (current.getFailureCount() < bestFailures) {
                                bestFailures = current.getFailureCount();
                                stagnantRounds = 0;
                            } else {
                                stagnantRounds++;
                            }
                        }
                    }
                }
                if (stagnantRounds >= 2) {
                    break;
                }
            } finally {
                roundSummaries.add("Round " + round + " targeted " + targetFailures + " of " + targetTotal
                        + " failures; " + current.getFailureCount() + " of " + current.getTotalSamples()
                        + " remained.");
                backups.discard(roundBackup);
            }
        }
        if (isCancelled()) {
            return result(FixResult.Status.CANCELLED, before, current, used, guardRollbacks,
                    overall, summaries, "Cancelled.");
        }
        return result(concurrencyMode ? FixResult.Status.PARTLY_FIXED : status(before, current),
                before, current, used, guardRollbacks, overall, summaries,
                concurrencyMode ? CONCURRENCY_PARTIAL_REASON : "");
    }

    private static FixResult.Status status(TestRunSummary before, TestRunSummary after) {
        return after.getFailureCount() < before.getFailureCount()
                ? FixResult.Status.PARTLY_FIXED : FixResult.Status.NOT_FIXED;
    }

    private static boolean passes(TestRunSummary summary) {
        return summary.getTotalSamples() > 0 && summary.getFailureCount() == 0 && !summary.isTimedOut();
    }

    private int addAssertions(List<Observation> observations) {
        int added = assertionInstaller.addMissing(observations);
        if (added > 0) {
            Telemetry.record(TelemetryFeature.FIX_ASSERTIONS_ADDED);
        }
        return added;
    }

    private TestRunSummary runConcurrencyCheck(FixRequest request) {
        TestRunSummary summary = runner.runIterations(request.timeoutSeconds(), 2, 2);
        recordSoftFailures(summary);
        if (concurrencyBefore == null) {
            concurrencyBefore = summary;
        }
        concurrencyAfter = summary;
        return summary;
    }

    private TestRunSummary runSingleCheck(long timeoutSeconds) {
        TestRunSummary summary = runner.run(timeoutSeconds);
        recordSoftFailures(summary);
        return summary;
    }

    private static void recordSoftFailures(TestRunSummary summary) {
        if (summary.getObservations().stream().anyMatch(observation -> observation.getSoftFailureReason() != null)) {
            Telemetry.record(TelemetryFeature.FIX_SOFT_FAILURE);
        }
    }

    private static String timeoutReason(long timeoutSeconds) {
        return "The check run timed out after " + timeoutSeconds
                + " s before every request ran. Raise jmeter.ai.fix.timeout.seconds.";
    }

    private static String checkFailureReason(RuntimeException error) {
        String detail = error.getMessage();
        return "JMeter check failed" + (detail == null || detail.isBlank() ? "." : ": " + detail);
    }

    private boolean isCancelled() {
        if (!cancelled.getAsBoolean()) {
            return false;
        }
        TestPlanRunner.stopActive(true);
        return true;
    }

    private void restoreRoundBackup(File file) {
        boolean interrupted = Thread.interrupted();
        try {
            backups.restore(file);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static boolean causedByInterruption(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof InterruptedException) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    private FixResult result(FixResult.Status status, TestRunSummary before, TestRunSummary after,
                             int rounds, int rollbacks, File overall, List<String> summaries, String reason) {
        int beforeFailed = before == null ? 0 : before.getFailureCount();
        int beforeTotal = before == null ? 0 : before.getTotalSamples();
        int afterFailed = after == null ? beforeFailed : after.getFailureCount();
        int afterTotal = after == null ? beforeTotal : after.getTotalSamples();
        List<Failure> remaining = after == null ? List.of() : after.getFailures();
        FixResult result = new FixResult(status, beforeFailed, beforeTotal, afterFailed, afterTotal,
                rounds, rollbacks, overall, summaries, remaining, reason,
                PlanDiff.between(startSnapshot, PlanDiff.liveSnapshot()), roundDiffs,
                concurrencyBefore != null,
                concurrencyBefore == null ? 0 : concurrencyBefore.getFailureCount(),
                concurrencyBefore == null ? 0 : concurrencyBefore.getTotalSamples(),
                concurrencyAfter == null ? 0 : concurrencyAfter.getFailureCount(),
                concurrencyAfter == null ? 0 : concurrencyAfter.getTotalSamples());
        result.setBeforeFailures(before == null ? List.of() : before.getFailures());
        result.setRoundSummaries(roundSummaries);
        result.setDeterministicHints(deterministicHints(before));
        return result;
    }

    private List<String> deterministicHints(TestRunSummary summary) {
        List<String> hints = new ArrayList<>();
        boolean literalPassword = startSnapshot.values().stream()
                .flatMap(element -> element.properties().entrySet().stream())
                .anyMatch(property -> (property.getKey() + " " + property.getValue())
                        .matches("(?is).*\\b(password|passwd|pwd)\\b.*"));
        if (literalPassword) {
            hints.add("Move literal password, passwd, or pwd argument values to User Defined Variables or a CSV Data Set.");
        }
        if (summary == null) {
            return List.copyOf(hints);
        }
        Map<String, Integer> hostCounts = new java.util.HashMap<>();
        summary.getObservations().forEach(observation -> hostOf(observation.getFinalUrl())
                .ifPresent(host -> hostCounts.merge(host, 1, Integer::sum)));
        String primary = hostCounts.entrySet().stream()
                .max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey).orElse(null);
        if (primary != null) {
            summary.getFailures().stream().map(failure -> hostOf(failure.getUrl()).orElse(null))
                    .filter(host -> host != null && !host.equalsIgnoreCase(primary)).distinct()
                    .forEach(host -> hints.add(host + " is outside your app, not fixed."));
        }
        return List.copyOf(hints);
    }

    private static java.util.Optional<String> hostOf(String url) {
        try {
            return java.util.Optional.ofNullable(URI.create(url).getHost());
        } catch (RuntimeException ignored) {
            return java.util.Optional.empty();
        }
    }
}
