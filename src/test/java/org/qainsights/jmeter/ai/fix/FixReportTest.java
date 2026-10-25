package org.qainsights.jmeter.ai.fix;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FixReportTest {
    @Test
    void markdownContainsRequiredSectionsAndMultiUserSummary() {
        PlanDiff.ElementSnapshot sampler = new PlanDiff.ElementSnapshot("HTTPSamplerProxy", Map.of("path", "/old"));
        PlanDiff diff = PlanDiff.between(Map.of(), Map.of("Plan/Request", sampler));
        FixResult result = new FixResult(FixResult.Status.PARTLY_FIXED, 2, 4, 1, 4, 1, 0,
                null, List.of("NEEDS USER: provide a test account"), List.of(),
                FixLoop.CONCURRENCY_PARTIAL_REASON, diff, List.of(diff), true, 3, 8, 1, 8);
        result.setRoundSummaries(List.of("Round 1 targeted 2 of 4 failures; 1 of 4 remained."));
        result.setDeterministicHints(List.of(
                "Move literal password, passwd, or pwd argument values to User Defined Variables or a CSV Data Set."));
        result.setBeforeFailures(List.of(new org.qainsights.jmeter.ai.agent.jmeter.TestRunSummary.Failure(
                "Request", "200", "Soft failure: page title indicates an error")));

        String markdown = FixReport.markdown(result);

        for (String section : List.of("## Before", "## What changed", "## Why", "## Assertions added",
                "## Listeners added", "## Needs you", "## Multi-user check")) {
            assertTrue(markdown.contains(section), section);
        }
        assertTrue(markdown.contains("2 users × 2 loops: 3 of 8 failed → 1 of 8 failed."));
        assertTrue(markdown.contains("NEEDS USER: provide a test account"));
        assertTrue(markdown.contains("Move literal password"));
        assertTrue(markdown.contains("Plan/Request"));
        assertTrue(markdown.contains("Soft failure: page title indicates an error"));
    }
}
