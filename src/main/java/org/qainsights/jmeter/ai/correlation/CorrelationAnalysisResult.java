package org.qainsights.jmeter.ai.correlation;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class CorrelationAnalysisResult {
    private final List<SampleRecord> samples;
    private final List<CorrelationCandidate> candidates;

    public CorrelationAnalysisResult(List<SampleRecord> samples, List<CorrelationCandidate> candidates) {
        this.samples = Collections.unmodifiableList(Objects.requireNonNull(samples, "samples"));
        this.candidates = Collections.unmodifiableList(Objects.requireNonNull(candidates, "candidates"));
    }

    public List<SampleRecord> getSamples() {
        return samples;
    }

    public List<CorrelationCandidate> getCandidates() {
        return candidates;
    }
}
