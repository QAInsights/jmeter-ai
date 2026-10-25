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

    public FixResult(Status status, int beforeFailed, int beforeTotal, int afterFailed, int afterTotal,
                     int roundsUsed, int guardRollbacks, File backupFile, List<String> agentSummaries,
                     List<Failure> remainingFailures, String reason) {
        this(status, beforeFailed, beforeTotal, afterFailed, afterTotal, roundsUsed, guardRollbacks,
                backupFile, agentSummaries, remainingFailures, reason, null, List.of());
    }

    public FixResult(Status status, int beforeFailed, int beforeTotal, int afterFailed, int afterTotal,
                     int roundsUsed, int guardRollbacks, File backupFile, List<String> agentSummaries,
                     List<Failure> remainingFailures, String reason, PlanDiff planDiff,
                     List<PlanDiff> roundDiffs) {
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

    public boolean hasUndoableChanges() {
        return status != Status.ALREADY_PASSING && status != Status.NOTHING_RAN
                && roundsUsed > guardRollbacks;
    }
}
