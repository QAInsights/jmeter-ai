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
        void restore(File file);
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
        TestRunSummary current = runner.run(request.timeoutSeconds());
        if (isCancelled()) {
            return result(FixResult.Status.CANCELLED, current, current, 0, 0, overall, List.of(), "Cancelled.");
        }
        if (current.getTotalSamples() == 0) {
            return result(FixResult.Status.NOTHING_RAN, current, current, 0, 0, overall, List.of(),
                    "JMeter did not produce any samples.");
        }
        if (current.getFailureCount() == 0) {
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
            File roundBackup = backups.backup();
            if (isCancelled()) {
                break;
            }
            PlanCensus beforeCensus = census.get();
            if (isCancelled()) {
                break;
            }
            sink.accept("Round " + round + " of " + request.maxRounds() + ": "
                    + current.getFailureCount() + " of " + current.getTotalSamples() + " requests failing");
            AgentLoop.AgentResult agentResult = fixRound.apply(prompt.build(current));
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
                    break;
                }
                current = runner.run(request.timeoutSeconds());
                if (isCancelled()) {
                    break;
                }
                if (current.getTotalSamples() > 0 && current.getFailureCount() == 0) {
                    return result(FixResult.Status.FIXED, before, current, used, guardRollbacks,
                            overall, summaries, "");
                }
                if (current.getFailureCount() < bestFailures) {
                    bestFailures = current.getFailureCount();
                    stagnantRounds = 0;
                } else {
                    stagnantRounds++;
                }
            }
            if (stagnantRounds >= 2) {
                break;
            }
        }
        if (isCancelled()) {
            return result(FixResult.Status.CANCELLED, before, current, used, guardRollbacks,
                    overall, summaries, "Cancelled.");
        }
        FixResult.Status status = current.getFailureCount() < before.getFailureCount()
                ? FixResult.Status.PARTLY_FIXED : FixResult.Status.NOT_FIXED;
        return result(status, before, current, used, guardRollbacks, overall, summaries, "");
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
