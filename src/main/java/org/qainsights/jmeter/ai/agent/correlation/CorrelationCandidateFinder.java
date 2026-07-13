package org.qainsights.jmeter.ai.agent.correlation;

import java.util.List;

import org.qainsights.jmeter.ai.correlation.CorrelationCandidate;
import org.qainsights.jmeter.ai.correlation.CorrelationEngine;

/**
 * Seam over "replay the current test plan as a single-thread, single-loop probe and
 * detect correlation candidates" - {@link CorrelationEngine#runAndCorrelate()}. Kept
 * behind an interface so the {@code find_correlation_candidates} tool logic can be
 * unit-tested without a running JMeter GUI or embedded engine.
 */
@FunctionalInterface
public interface CorrelationCandidateFinder {

    /**
     * @return correlation candidates whose extracted value from one sampler's response
     *         is reused by a later sampler's request
     * @throws CorrelationExecutionException if the plan could not be replayed at all
     */
    List<CorrelationCandidate> find();

    /** Live finder, backed by a fresh {@link CorrelationEngine} per call. */
    static CorrelationCandidateFinder live() {
        return () -> {
            try {
                return new CorrelationEngine().runAndCorrelate();
            } catch (Exception e) {
                throw new CorrelationExecutionException(
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        };
    }
}
