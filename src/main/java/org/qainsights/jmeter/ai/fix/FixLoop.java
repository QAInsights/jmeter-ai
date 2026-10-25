package org.qainsights.jmeter.ai.fix;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.qainsights.jmeter.ai.agent.loop.AgentLoop;
import org.qainsights.jmeter.ai.agent.jmeter.TestPlanRunner;
import org.qainsights.jmeter.ai.agent.jmeter.TestRunSummary;
import org.qainsights.jmeter.ai.agent.jmeter.TestRunSummary.Failure;
import org.qainsights.jmeter.ai.utils.ProviderErrors;
import org.qainsights.jmeter.ai.telemetry.Telemetry;
import org.qainsights.jmeter.ai.telemetry.TelemetryFeature;

/** GUI-free orchestration for Make it pass. Every repair is checked by a real JMeter run. */
public final class FixLoop {
    @FunctionalInterface
    public interface SingleIterationRunner {
        TestRunSummary run(long timeoutSeconds);
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

    public FixLoop(SingleIterationRunner runner, BackupStore backups, Supplier<PlanCensus> census,
                   Function<String, AgentLoop.AgentResult> fixRound, BooleanSupplier cancelled) {
        this(runner, backups, census, fixRound, cancelled, new FixPrompt());
    }

    public FixLoop(SingleIterationRunner runner, BackupStore backups, Supplier<PlanCensus> census,
                   Function<String, AgentLoop.AgentResult> fixRound, BooleanSupplier cancelled, FixPrompt prompt) {
        this.runner = runner;
        this.backups = backups;
        this.census = census;
        this.fixRound = fixRound;
        this.cancelled = cancelled == null ? () -> false : cancelled;
        this.prompt = prompt;
    }

    public FixResult run(FixRequest request, Consumer<String> progress) {
        Consumer<String> sink = progress == null ? ignored -> { } : progress;
        File overall = backups.backup();
        if (isCancelled()) {
            return result(FixResult.Status.CANCELLED, null, null, 0, 0, overall, List.of(), "Cancelled.");
        }
        TestRunSummary current;
        try {
            current = runner.run(request.timeoutSeconds());
        } catch (RuntimeException e) {
            if (isCancelled()) {
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
            return result(FixResult.Status.ALREADY_PASSING, current, current, 0, 0, overall, List.of(), "");
        }

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
            File roundBackup = backups.roundBackup();
            try {
                if (isCancelled()) {
                    break;
                }
                PlanCensus beforeCensus = census.get();
                if (isCancelled()) {
                    break;
                }
                sink.accept("Round " + round + " of " + request.maxRounds() + ": "
                        + current.getFailureCount() + " of " + current.getTotalSamples() + " requests failing");
                AgentLoop.AgentResult agentResult;
                try {
                    agentResult = fixRound.apply(prompt.build(current));
                } catch (RuntimeException e) {
                    backups.restore(roundBackup);
                    if (isCancelled()) {
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
                if (isCancelled()) {
                    backups.restore(roundBackup);
                    break;
                }
                List<String> violations = beforeCensus.violations(census.get());
                if (isCancelled()) {
                    backups.restore(roundBackup);
                    break;
                }
                if (!violations.isEmpty()) {
                    backups.restore(roundBackup);
                    guardRollbacks++;
                    Telemetry.record(TelemetryFeature.FIX_GUARD_ROLLBACK);
                    violations.forEach(v -> sink.accept("Safety guard: " + v + " Restored this round."));
                    stagnantRounds++;
                } else {
                    if (isCancelled()) {
                        backups.restore(roundBackup);
                        break;
                    }
                    TestRunSummary checked;
                    try {
                        checked = runner.run(request.timeoutSeconds());
                    } catch (RuntimeException e) {
                        backups.restore(roundBackup);
                        if (isCancelled()) {
                            return result(FixResult.Status.CANCELLED, before, current, used, guardRollbacks,
                                    overall, summaries, "Cancelled.");
                        }
                        return result(status(before, current), before, current, used, guardRollbacks,
                                overall, summaries, checkFailureReason(e));
                    }
                    if (isCancelled()) {
                        backups.restore(roundBackup);
                        break;
                    }
                    if (passes(checked)) {
                        return result(FixResult.Status.FIXED, before, checked, used, guardRollbacks,
                                overall, summaries, "");
                    }
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
                if (stagnantRounds >= 2) {
                    break;
                }
            } finally {
                backups.discard(roundBackup);
            }
        }
        if (isCancelled()) {
            return result(FixResult.Status.CANCELLED, before, current, used, guardRollbacks,
                    overall, summaries, "Cancelled.");
        }
        return result(status(before, current), before, current, used, guardRollbacks, overall, summaries, "");
    }

    private static FixResult.Status status(TestRunSummary before, TestRunSummary after) {
        return after.getFailureCount() < before.getFailureCount()
                ? FixResult.Status.PARTLY_FIXED : FixResult.Status.NOT_FIXED;
    }

    private static boolean passes(TestRunSummary summary) {
        return summary.getTotalSamples() > 0 && summary.getFailureCount() == 0 && !summary.isTimedOut();
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

    private static FixResult result(FixResult.Status status, TestRunSummary before, TestRunSummary after,
                                   int rounds, int rollbacks, File overall, List<String> summaries, String reason) {
        int beforeFailed = before == null ? 0 : before.getFailureCount();
        int beforeTotal = before == null ? 0 : before.getTotalSamples();
        int afterFailed = after == null ? beforeFailed : after.getFailureCount();
        int afterTotal = after == null ? beforeTotal : after.getTotalSamples();
        List<Failure> remaining = after == null ? List.of() : after.getFailures();
        return new FixResult(status, beforeFailed, beforeTotal, afterFailed, afterTotal,
                rounds, rollbacks, overall, summaries, remaining, reason);
    }
}
