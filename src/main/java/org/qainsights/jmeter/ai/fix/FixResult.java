package org.qainsights.jmeter.ai.fix;

import java.io.File;
import java.util.List;

import org.qainsights.jmeter.ai.agent.jmeter.TestRunSummary.Failure;

/** Outcome and review data from one Make it pass workflow. */
public final class FixResult {
    public enum Status {
        ALREADY_PASSING, FIXED, PARTLY_FIXED, NOT_FIXED, CANCELLED, NOTHING_RAN
    }

    private final Status status;
    private final int beforeFailed;
    private final int beforeTotal;
    private final int afterFailed;
    private final int afterTotal;
    private final int roundsUsed;
    private final int guardRollbacks;
    private final File backupFile;
    private final List<String> agentSummaries;
    private final List<Failure> remainingFailures;
    private final String reason;
    private final PlanDiff planDiff;
    private final List<PlanDiff> roundDiffs;
    private final boolean concurrencyChecked;
    private final int concurrencyBeforeFailed;
    private final int concurrencyBeforeTotal;
    private final int concurrencyAfterFailed;
    private final int concurrencyAfterTotal;
    private String concurrencyReason = "";
    private File reportFile;
    private List<Failure> beforeFailures = List.of();
    private List<String> roundSummaries = List.of();
    private List<String> deterministicHints = List.of();
    private List<String> skippedPageChecks = List.of();

    public FixResult(Status status, int beforeFailed, int beforeTotal, int afterFailed, int afterTotal,
                     int roundsUsed, int guardRollbacks, File backupFile, List<String> agentSummaries,
                     List<Failure> remainingFailures, String reason) {
        this(status, beforeFailed, beforeTotal, afterFailed, afterTotal, roundsUsed, guardRollbacks,
                backupFile, agentSummaries, remainingFailures, reason, null, List.of(),
                false, 0, 0, 0, 0);
    }

    public FixResult(Status status, int beforeFailed, int beforeTotal, int afterFailed, int afterTotal,
                     int roundsUsed, int guardRollbacks, File backupFile, List<String> agentSummaries,
                     List<Failure> remainingFailures, String reason, PlanDiff planDiff,
                     List<PlanDiff> roundDiffs) {
        this(status, beforeFailed, beforeTotal, afterFailed, afterTotal, roundsUsed, guardRollbacks,
                backupFile, agentSummaries, remainingFailures, reason, planDiff, roundDiffs,
                false, 0, 0, 0, 0);
    }

    public FixResult(Status status, int beforeFailed, int beforeTotal, int afterFailed, int afterTotal,
                     int roundsUsed, int guardRollbacks, File backupFile, List<String> agentSummaries,
                     List<Failure> remainingFailures, String reason, PlanDiff planDiff,
                     List<PlanDiff> roundDiffs, boolean concurrencyChecked, int concurrencyBeforeFailed,
                     int concurrencyBeforeTotal, int concurrencyAfterFailed, int concurrencyAfterTotal) {
        this.status = status;
        this.beforeFailed = beforeFailed;
        this.beforeTotal = beforeTotal;
        this.afterFailed = afterFailed;
        this.afterTotal = afterTotal;
        this.roundsUsed = roundsUsed;
        this.guardRollbacks = guardRollbacks;
        this.backupFile = backupFile;
        this.agentSummaries = List.copyOf(agentSummaries);
        this.remainingFailures = List.copyOf(remainingFailures);
        this.reason = reason == null ? "" : reason;
        this.planDiff = planDiff;
        this.roundDiffs = List.copyOf(roundDiffs);
        this.concurrencyChecked = concurrencyChecked;
        this.concurrencyBeforeFailed = concurrencyBeforeFailed;
        this.concurrencyBeforeTotal = concurrencyBeforeTotal;
        this.concurrencyAfterFailed = concurrencyAfterFailed;
        this.concurrencyAfterTotal = concurrencyAfterTotal;
    }

    public Status status() { return status; }
    public int beforeFailed() { return beforeFailed; }
    public int beforeTotal() { return beforeTotal; }
    public int afterFailed() { return afterFailed; }
    public int afterTotal() { return afterTotal; }
    public int roundsUsed() { return roundsUsed; }
    public int guardRollbacks() { return guardRollbacks; }
    public File backupFile() { return backupFile; }
    public List<String> agentSummaries() { return agentSummaries; }
    public List<Failure> remainingFailures() { return remainingFailures; }
    public String reason() { return reason; }
    public PlanDiff planDiff() { return planDiff; }
    public List<PlanDiff> roundDiffs() { return roundDiffs; }
    public boolean concurrencyChecked() { return concurrencyChecked; }
    public int concurrencyBeforeFailed() { return concurrencyBeforeFailed; }
    public int concurrencyBeforeTotal() { return concurrencyBeforeTotal; }
    public int concurrencyAfterFailed() { return concurrencyAfterFailed; }
    public int concurrencyAfterTotal() { return concurrencyAfterTotal; }
    public String concurrencyReason() { return concurrencyReason; }
    public void setConcurrencyReason(String concurrencyReason) {
        this.concurrencyReason = concurrencyReason == null ? "" : concurrencyReason;
    }
    public File reportFile() { return reportFile; }
    public void setReportFile(File reportFile) { this.reportFile = reportFile; }
    public List<Failure> beforeFailures() { return beforeFailures; }
    public void setBeforeFailures(List<Failure> beforeFailures) {
        this.beforeFailures = List.copyOf(beforeFailures);
    }
    public List<String> roundSummaries() { return roundSummaries; }
    public void setRoundSummaries(List<String> roundSummaries) {
        this.roundSummaries = List.copyOf(roundSummaries);
    }
    public List<String> deterministicHints() { return deterministicHints; }
    public void setDeterministicHints(List<String> deterministicHints) {
        this.deterministicHints = List.copyOf(deterministicHints);
    }
    public List<String> skippedPageChecks() { return skippedPageChecks; }
    public void setSkippedPageChecks(List<String> skippedPageChecks) {
        this.skippedPageChecks = List.copyOf(skippedPageChecks);
    }

    public boolean hasUndoableChanges() {
        return planDiff != null && (!planDiff.added().isEmpty()
                || !planDiff.removed().isEmpty() || !planDiff.modified().isEmpty());
    }
}
