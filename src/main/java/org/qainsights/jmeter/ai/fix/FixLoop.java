package org.qainsights.jmeter.ai.fix;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.qainsights.jmeter.ai.agent.loop.AgentLoop;
import org.qainsights.jmeter.ai.agent.jmeter.TestPlanRunner;
import org.qainsights.jmeter.ai.agent.jmeter.TestRunSummary;
import org.qainsights.jmeter.ai.agent.jmeter.TestRunSummary.Failure;
import org.qainsights.jmeter.ai.agent.jmeter.TestRunSummary.Observation;
import org.qainsights.jmeter.ai.utils.ProviderErrors;
import org.qainsights.jmeter.ai.telemetry.Telemetry;
import org.qainsights.jmeter.ai.telemetry.TelemetryFeature;
import org.qainsights.jmeter.ai.utils.SafeText;
import org.apache.jmeter.config.Argument;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jorphan.collections.HashTree;
import org.qainsights.jmeter.ai.agent.jmeter.EdtExecutor;

/** GUI-free orchestration for Make it pass. Every repair is checked by a real JMeter run. */
public final class FixLoop {
    public static final String CONCURRENCY_PARTIAL_REASON =
            "Passes with 1 user, fails with 2 users × 2 loops";

    public interface AssertionInstaller {
        List<String> addMissing(List<Observation> observations);
        void removeAdded(Collection<String> samplerLabels);
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

    private enum GateStage {
        ONE_USER, TWO_USERS, PASSED
    }

    private record GateResult(TestRunSummary decisive, TestRunSummary singleUser, GateStage failedStage) {
        private boolean passes() {
            return failedStage == GateStage.PASSED;
        }
    }

    private static final Pattern PASSWORD_ARGUMENT = Pattern.compile("(?i)(password|passwd|pwd|pass)");

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
    private final List<String> skippedPageChecks = new ArrayList<>();
    private boolean assertionTelemetryRecorded;
    private boolean softFailureTelemetryRecorded;
    private boolean concurrencyFixedTelemetryRecorded;

    public FixLoop(SingleIterationRunner runner, BackupStore backups, Supplier<PlanCensus> census,
                   Function<String, AgentLoop.AgentResult> fixRound, BooleanSupplier cancelled) {
        this(runner, backups, census, fixRound, cancelled, new FixPrompt());
    }

    public FixLoop(SingleIterationRunner runner, BackupStore backups, Supplier<PlanCensus> census,
                   Function<String, AgentLoop.AgentResult> fixRound, BooleanSupplier cancelled, FixPrompt prompt) {
        this(runner, backups, census, fixRound, cancelled, prompt, new FixAssertions());
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
        this.assertionInstaller = assertionInstaller == null ? new FixAssertions() : assertionInstaller;
    }

    public FixResult run(FixRequest request, Consumer<String> progress) {
        Consumer<String> sink = progress == null ? ignored -> { } : progress;
        File overall = backups.backup();
        startSnapshot = PlanDiff.liveSnapshot();
        roundDiffs.clear();
        roundSummaries.clear();
        concurrencyBefore = null;
        concurrencyAfter = null;
        skippedPageChecks.clear();
        assertionTelemetryRecorded = false;
        softFailureTelemetryRecorded = false;
        concurrencyFixedTelemetryRecorded = false;
        if (isCancelled()) {
            return result(FixResult.Status.CANCELLED, null, null, 0, 0, overall, List.of(), "Cancelled.");
        }
        GateResult initial;
        try {
            initial = gate(request);
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
        TestRunSummary first = initial.singleUser();
        if (first.isTimedOut()) {
            return result(FixResult.Status.NOT_FIXED, first, first, 0, 0, overall, List.of(),
                    timeoutReason(request.timeoutSeconds()));
        }
        if (first.getTotalSamples() == 0) {
            return result(FixResult.Status.NOTHING_RAN, first, first, 0, 0, overall, List.of(),
                    "JMeter did not produce any samples.");
        }

        TestRunSummary before = initial.decisive();
        GateResult verified;
        try {
            FixListeners.ensurePresent();
            verified = addVerifiedAssertions(initial.decisive().getObservations(), request);
        } catch (RuntimeException e) {
            if (isCancelled() || causedByInterruption(e)) {
                Thread.currentThread().interrupt();
                return result(FixResult.Status.CANCELLED, before, before, 0, 0, overall, List.of(), "Cancelled.");
            }
            return result(FixResult.Status.NOT_FIXED, before, before, 0, 0, overall, List.of(),
                    checkFailureReason(e));
        }
        if (isCancelled()) {
            return result(FixResult.Status.CANCELLED, before, before, 0, 0, overall, List.of(), "Cancelled.");
        }
        if (initial.passes() && verified.passes()) {
            return result(FixResult.Status.ALREADY_PASSING, before, verified.decisive(), 0, 0, overall, List.of(), "");
        }
        boolean concurrencyMode = request.concurrencyCheck()
                && passes(verified.singleUser()) && verified.failedStage() == GateStage.TWO_USERS;
        boolean concurrencyFailureObserved = request.concurrencyCheck()
                && (initial.failedStage() == GateStage.TWO_USERS || verified.failedStage() == GateStage.TWO_USERS);
        TestRunSummary current = initial.passes()
                ? (hasSamples(verified.decisive()) ? verified.decisive() : initial.decisive())
                : initial.decisive();
        if (initial.passes() && hasSamples(verified.decisive())) {
            before = verified.decisive();
        }
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
            boolean providerFailure = false;
            try {
                if (isCancelled()) {
                    restoreRoundBackup(roundBackup);
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
                providerFailure = true;
                agentResult = fixRound.apply(concurrencyMode
                        ? prompt.buildConcurrency(current) : prompt.build(current));
                providerFailure = false;
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
                    GateResult checked = gate(request);
                    if (isCancelled()) {
                        restoreRoundBackup(roundBackup);
                        break;
                    }
                    if (concurrencyMode && checked.failedStage() == GateStage.ONE_USER) {
                        restoreRoundBackup(roundBackup);
                        guardRollbacks++;
                        Telemetry.record(TelemetryFeature.FIX_GUARD_ROLLBACK);
                        stagnantRounds++;
                    } else {
                        if (checked.passes()) {
                            checked = addVerifiedAssertions(checked.decisive().getObservations(), request);
                            if (isCancelled()) {
                                restoreRoundBackup(roundBackup);
                                break;
                            }
                            if (concurrencyMode && checked.failedStage() == GateStage.ONE_USER) {
                                restoreRoundBackup(roundBackup);
                                guardRollbacks++;
                                Telemetry.record(TelemetryFeature.FIX_GUARD_ROLLBACK);
                                stagnantRounds++;
                                continue;
                            }
                            if (checked.passes()) {
                                if (concurrencyMode) {
                                    recordConcurrencyFixed();
                                } else if (concurrencyFailureObserved) {
                                    recordConcurrencyFixed();
                                }
                                return result(FixResult.Status.FIXED, before, checked.decisive(), used,
                                        guardRollbacks, overall, summaries, "");
                            }
                        }
                        if (checked.failedStage() == GateStage.TWO_USERS) {
                            concurrencyMode = true;
                        }
                        if (!hasSamples(checked.decisive()) || checked.decisive().isTimedOut()) {
                            stagnantRounds++;
                        } else {
                            current = checked.decisive();
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
            } catch (RuntimeException e) {
                used = round;
                return roundFailure(e, providerFailure, roundBackup, before, current, round, guardRollbacks,
                        overall, summaries);
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
        if (concurrencyMode) {
            Telemetry.record(TelemetryFeature.FIX_CONCURRENCY_WARN);
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

    private static boolean hasSamples(TestRunSummary summary) {
        return summary != null && summary.getTotalSamples() > 0;
    }

    private GateResult gate(FixRequest request) {
        TestRunSummary single = runSingleCheck(request.timeoutSeconds());
        if (!passes(single)) {
            return new GateResult(single, single, GateStage.ONE_USER);
        }
        if (!request.concurrencyCheck()) {
            return new GateResult(single, single, GateStage.PASSED);
        }
        TestRunSummary multi = runConcurrencyCheck(request);
        return new GateResult(multi, single, passes(multi) ? GateStage.PASSED : GateStage.TWO_USERS);
    }

    private GateResult addVerifiedAssertions(List<Observation> observations, FixRequest request) {
        List<String> added = assertionInstaller.addMissing(observations);
        if (!added.isEmpty() && !assertionTelemetryRecorded) {
            Telemetry.record(TelemetryFeature.FIX_ASSERTIONS_ADDED);
            assertionTelemetryRecorded = true;
        }
        List<String> stillInstalled = new ArrayList<>(added);
        try {
            GateResult verified = gate(request);
            if (!added.isEmpty()) {
                Set<String> failedLabels = new HashSet<>();
                verified.decisive().getObservations().stream().filter(Observation::isFailure)
                        .map(Observation::getLabel).forEach(failedLabels::add);
                List<String> failingAdded = added.stream().filter(failedLabels::contains).toList();
                if (!failingAdded.isEmpty()) {
                    assertionInstaller.removeAdded(failingAdded);
                    stillInstalled.removeAll(failingAdded);
                    failingAdded.forEach(label -> skippedPageChecks.add(
                            "Skipped page check for " + label + ": the page changes between runs."));
                    verified = gate(request);
                }
            }
            return verified;
        } catch (RuntimeException e) {
            if (!stillInstalled.isEmpty()) {
                assertionInstaller.removeAdded(stillInstalled);
            }
            throw e;
        }
    }

    private FixResult roundFailure(RuntimeException error, boolean providerFailure, File roundBackup,
                                   TestRunSummary before, TestRunSummary current, int round, int rollbacks,
                                   File overall, List<String> summaries) {
        boolean interrupted = causedByInterruption(error);
        if (interrupted) {
            Thread.interrupted();
        }
        try {
            restoreRoundBackup(roundBackup);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (isCancelled() || interrupted) {
            return result(FixResult.Status.CANCELLED, before, current, round, rollbacks,
                    overall, summaries, "Cancelled.");
        }
        String reason = providerFailure ? ProviderErrors.describe(error, "running Make it pass", true)
                : checkFailureReason(error);
        return result(status(before, current), before, current, round, rollbacks, overall, summaries, reason);
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

    private void recordSoftFailures(TestRunSummary summary) {
        if (!softFailureTelemetryRecorded && summary.getObservations().stream()
                .anyMatch(observation -> observation.getSoftFailureReason() != null)) {
            Telemetry.record(TelemetryFeature.FIX_SOFT_FAILURE);
            softFailureTelemetryRecorded = true;
        }
    }

    private void recordConcurrencyFixed() {
        if (!concurrencyFixedTelemetryRecorded) {
            Telemetry.record(TelemetryFeature.FIX_CONCURRENCY_FIXED);
            concurrencyFixedTelemetryRecorded = true;
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
        boolean interrupted = Thread.interrupted();
        try {
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
            result.setSkippedPageChecks(skippedPageChecks);
            return result;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private List<String> deterministicHints(TestRunSummary summary) {
        List<String> hints = new ArrayList<>(literalPasswordHints());
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

    static List<String> literalPasswordHints(HashTree tree) {
        List<String> hints = new ArrayList<>();
        collectPasswordHints(tree, hints);
        return List.copyOf(hints);
    }

    private static void collectPasswordHints(HashTree tree, List<String> hints) {
        if (tree == null) {
            return;
        }
        for (Object key : tree.list()) {
            TestElement element = key instanceof JMeterTreeNode node ? node.getTestElement()
                    : key instanceof TestElement testElement ? testElement : null;
            if (element instanceof HTTPSamplerBase sampler) {
                Arguments arguments = sampler.getArguments();
                if (arguments != null) {
                    for (int index = 0; index < arguments.getArgumentCount(); index++) {
                        Argument argument = arguments.getArgument(index);
                        String name = argument.getName() == null ? "" : argument.getName();
                        String value = argument.getValue() == null ? "" : argument.getValue();
                        if (PASSWORD_ARGUMENT.matcher(name).find() && !value.isBlank() && !value.contains("${")) {
                            hints.add("Sampler " + SafeText.redactSensitiveValues(sampler.getName())
                                    + " has a literal password argument. Move it to User Defined Variables "
                                    + "or a CSV Data Set.");
                            break;
                        }
                    }
                }
            }
            collectPasswordHints(tree.getTree(key), hints);
        }
    }

    private static List<String> literalPasswordHints() {
        GuiPackage gui = GuiPackage.getInstance();
        if (gui == null || gui.getTreeModel() == null) {
            return List.of();
        }
        AtomicReference<HashTree> tree = new AtomicReference<>();
        boolean interrupted = Thread.interrupted();
        try {
            EdtExecutor.swing().run(() -> {
                gui.updateCurrentNode();
                tree.set(gui.getTreeModel().getTestPlan());
            });
            return literalPasswordHints(tree.get());
        } catch (RuntimeException e) {
            return List.of();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static java.util.Optional<String> hostOf(String url) {
        try {
            return java.util.Optional.ofNullable(URI.create(url).getHost());
        } catch (RuntimeException ignored) {
            return java.util.Optional.empty();
        }
    }
}
