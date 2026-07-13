package org.qainsights.jmeter.ai.agent.correlation;

import java.util.List;

import org.qainsights.jmeter.ai.correlation.CorrelationCandidate;
import org.qainsights.jmeter.ai.correlation.CorrelationInjector;

/**
 * Seam over "add an extractor to each approved candidate's source sampler and rewrite
 * every reusing sampler's matching value to a variable reference" -
 * {@link CorrelationInjector#apply}. Kept behind an interface so the
 * {@code apply_correlation} tool logic can be unit-tested without a running JMeter GUI.
 */
@FunctionalInterface
public interface CorrelationApplier {

    /**
     * @param approvedCandidates candidates to apply; only those with
     *                           {@code CorrelationCandidate.Status.APPROVED} are applied
     *                           (mirrors {@link CorrelationInjector#apply}'s own filter)
     * @return the number of candidates actually applied
     */
    int apply(List<CorrelationCandidate> approvedCandidates);

    /** Live applier, backed by a fresh {@link CorrelationInjector} per call. */
    static CorrelationApplier live() {
        return candidates -> new CorrelationInjector().apply(candidates);
    }
}
