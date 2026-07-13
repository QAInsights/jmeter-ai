package org.qainsights.jmeter.ai.agent.correlation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.qainsights.jmeter.ai.correlation.CorrelationCandidate;

/**
 * Holds the most recent {@code find_correlation_candidates} result so a later
 * {@code apply_correlation} call - in the same or a later chat turn - can reference a
 * finding by its 1-based id, without the agent needing to round-trip full candidate
 * details back through tool arguments.
 * <p>
 * Static/process-wide by design: a JMeter GUI process only ever has one active test
 * plan, and {@code AgentToolRegistry.createDefault()} constructs a fresh
 * {@code FindCorrelationCandidatesHandler}/{@code ApplyCorrelationHandler} pair on every
 * agent turn, so per-instance state would not be shared between "find" and a later
 * "apply" call. This mirrors the same static-state pattern already used by
 * {@code CorrelationEngine.Collector}/{@code Ender} and {@code TestPlanRunner}'s
 * {@code SampleCollector}/{@code RunEndedListener} in this codebase.
 */
public final class CorrelationCandidateStore {

    private static volatile List<CorrelationCandidate> candidates = Collections.emptyList();

    private CorrelationCandidateStore() {
    }

    /** Replaces the stored candidates, e.g. after a fresh find_correlation_candidates run. */
    public static synchronized void set(List<CorrelationCandidate> found) {
        candidates = found == null ? Collections.emptyList() : new ArrayList<>(found);
    }

    /** A snapshot of the last-found candidates, in the same (1-based-id) order they were found. */
    public static synchronized List<CorrelationCandidate> get() {
        return new ArrayList<>(candidates);
    }

    /** Test-only hook to reset state between test cases. */
    public static synchronized void clearForTests() {
        candidates = Collections.emptyList();
    }
}
