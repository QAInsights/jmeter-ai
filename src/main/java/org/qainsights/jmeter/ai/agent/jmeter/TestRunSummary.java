package org.qainsights.jmeter.ai.agent.jmeter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.jmeter.samplers.SampleResult;

/**
 * Immutable summary of a {@link TestPlanRunner} run: aggregate pass/fail counts plus a
 * capped list of failure details, so {@code get_test_results} can report something
 * directly useful to the agent without dumping every raw {@link SampleResult}.
 */
public final class TestRunSummary {

    /** Max number of individual failures kept in {@link #getFailures()}; later ones are only counted. */
    public static final int MAX_FAILURES_KEPT = 20;

    private final int totalSamples;
    private final int successCount;
    private final int failureCount;
    private final boolean timedOut;
    private final long elapsedMillis;
    private final List<Failure> failures;

    private TestRunSummary(int totalSamples, int successCount, int failureCount, boolean timedOut,
                            long elapsedMillis, List<Failure> failures) {
        this.totalSamples = totalSamples;
        this.successCount = successCount;
        this.failureCount = failureCount;
        this.timedOut = timedOut;
        this.elapsedMillis = elapsedMillis;
        this.failures = Collections.unmodifiableList(failures);
    }

    /** Builds a summary by aggregating the raw sample results collected during a run. */
    public static TestRunSummary of(List<SampleResult> results, boolean timedOut, long elapsedMillis) {
        int success = 0;
        int failure = 0;
        List<Failure> failures = new ArrayList<>();
        for (SampleResult result : results) {
            if (result.isSuccessful()) {
                success++;
            } else {
                failure++;
                if (failures.size() < MAX_FAILURES_KEPT) {
                    failures.add(new Failure(result.getSampleLabel(), result.getResponseCode(),
                            result.getResponseMessage()));
                }
            }
        }
        return new TestRunSummary(results.size(), success, failure, timedOut, elapsedMillis, failures);
    }

    public int getTotalSamples() {
        return totalSamples;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public boolean isTimedOut() {
        return timedOut;
    }

    public long getElapsedMillis() {
        return elapsedMillis;
    }

    /** Up to {@link #MAX_FAILURES_KEPT} failed samples; see {@link #hasMoreFailuresThanShown()}. */
    public List<Failure> getFailures() {
        return failures;
    }

    /** True when there were more failures than fit in {@link #getFailures()}. */
    public boolean hasMoreFailuresThanShown() {
        return failureCount > failures.size();
    }

    /** A single failed sample's label, response code and message. */
    public static final class Failure {
        private final String label;
        private final String responseCode;
        private final String message;

        public Failure(String label, String responseCode, String message) {
            this.label = label == null ? "" : label;
            this.responseCode = responseCode == null ? "" : responseCode;
            this.message = message == null ? "" : message;
        }

        public String getLabel() {
            return label;
        }

        public String getResponseCode() {
            return responseCode;
        }

        public String getMessage() {
            return message;
        }
    }
}
