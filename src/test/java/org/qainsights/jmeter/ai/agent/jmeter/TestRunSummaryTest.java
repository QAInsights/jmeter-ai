package org.qainsights.jmeter.ai.agent.jmeter;

import java.util.ArrayList;
import java.util.List;

import org.apache.jmeter.samplers.SampleResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link TestRunSummary}. */
class TestRunSummaryTest {

    private static SampleResult success(String label) {
        SampleResult result = new SampleResult();
        result.setSampleLabel(label);
        result.setSuccessful(true);
        return result;
    }

    private static SampleResult failure(String label, String responseCode, String message) {
        SampleResult result = new SampleResult();
        result.setSampleLabel(label);
        result.setSuccessful(false);
        result.setResponseCode(responseCode);
        result.setResponseMessage(message);
        return result;
    }

    @Test
    void of_emptyResults_reportsZeroCounts() {
        TestRunSummary summary = TestRunSummary.of(new ArrayList<>(), false, 42L);

        assertEquals(0, summary.getTotalSamples());
        assertEquals(0, summary.getSuccessCount());
        assertEquals(0, summary.getFailureCount());
        assertFalse(summary.isTimedOut());
        assertEquals(42L, summary.getElapsedMillis());
        assertTrue(summary.getFailures().isEmpty());
        assertFalse(summary.hasMoreFailuresThanShown());
    }

    @Test
    void of_mixedResults_countsSuccessesAndFailuresSeparately() {
        List<SampleResult> results = List.of(
                success("A"), success("B"), failure("C", "500", "Server error"));

        TestRunSummary summary = TestRunSummary.of(results, false, 100L);

        assertEquals(3, summary.getTotalSamples());
        assertEquals(2, summary.getSuccessCount());
        assertEquals(1, summary.getFailureCount());
        assertEquals(1, summary.getFailures().size());
        TestRunSummary.Failure failure = summary.getFailures().get(0);
        assertEquals("C", failure.getLabel());
        assertEquals("500", failure.getResponseCode());
        assertEquals("Server error", failure.getMessage());
    }

    @Test
    void of_moreFailuresThanCap_keepsOnlyMaxFailuresKept() {
        List<SampleResult> results = new ArrayList<>();
        int total = TestRunSummary.MAX_FAILURES_KEPT + 5;
        for (int i = 0; i < total; i++) {
            results.add(failure("Sample " + i, "500", "err"));
        }

        TestRunSummary summary = TestRunSummary.of(results, false, 0L);

        assertEquals(total, summary.getFailureCount());
        assertEquals(TestRunSummary.MAX_FAILURES_KEPT, summary.getFailures().size());
        assertTrue(summary.hasMoreFailuresThanShown());
    }

    @Test
    void of_timedOutFlag_isPreserved() {
        TestRunSummary summary = TestRunSummary.of(new ArrayList<>(), true, 5000L);
        assertTrue(summary.isTimedOut());
    }

    @Test
    void of_nullResponseCodeAndMessage_areNormalizedToEmptyStrings() {
        SampleResult result = new SampleResult();
        result.setSampleLabel("X");
        result.setSuccessful(false);
        result.setResponseCode(null);
        result.setResponseMessage(null);

        TestRunSummary summary = TestRunSummary.of(List.of(result), false, 0L);

        TestRunSummary.Failure failure = summary.getFailures().get(0);
        assertEquals("", failure.getResponseCode());
        assertEquals("", failure.getMessage());
    }
}
