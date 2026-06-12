package org.qainsights.jmeter.ai.consensus;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class ConsensusReportTest {

    @Test
    void jaccard_identicalIsOne_disjointIsZero() {
        assertEquals(1.0, ConsensusReport.jaccard(
                ConsensusReport.words("the cpu is saturated"),
                ConsensusReport.words("the cpu is saturated")), 1e-9);
        assertEquals(0.0, ConsensusReport.jaccard(
                new HashSet<>(Arrays.asList("a", "b")),
                new HashSet<>(Arrays.asList("c", "d"))), 1e-9);
    }

    @Test
    void agreementScore_highForSimilarAnswers() {
        ConsensusReport report = new ConsensusReport("why slow?", Arrays.asList(
                outcome("Kiro", "the database connection pool is exhausted"),
                outcome("Claude Code", "the database connection pool is exhausted under load")));
        assertTrue(report.agreementScore() > 0.6, "similar answers should score high");
    }

    @Test
    void agreementScore_lowForDivergentAnswers() {
        ConsensusReport report = new ConsensusReport("why slow?", Arrays.asList(
                outcome("Kiro", "garbage collection pauses dominate"),
                outcome("Claude Code", "network latency between regions")));
        assertTrue(report.agreementScore() < 0.3, "divergent answers should score low");
    }

    @Test
    void markdown_listsEachCliAndScore() {
        ConsensusReport report = new ConsensusReport("p99 spike", Arrays.asList(
                outcome("Kiro", "thread pool too small"),
                skipped("Claude Code")));
        String md = report.render("md");
        assertTrue(md.contains("# Multi-CLI Consensus Report"), md);
        assertTrue(md.contains("Agreement score:"), md);
        assertTrue(md.contains("## Kiro"), md);
        assertTrue(md.contains("SKIPPED"), md);
    }

    @Test
    void json_isWellFormedWithResults() {
        ConsensusReport report = new ConsensusReport("q", Arrays.asList(
                outcome("Kiro", "answer one")));
        String json = report.render("json");
        assertTrue(json.startsWith("{") && json.endsWith("}"), json);
        assertTrue(json.contains("\"agreementScore\":"), json);
        assertTrue(json.contains("\"cli\":\"Kiro\""), json);
    }

    private static ConsensusRunner.Outcome outcome(String cli, String output) {
        return new ConsensusRunner.Outcome(cli, true, 0, false, output);
    }

    private static ConsensusRunner.Outcome skipped(String cli) {
        return new ConsensusRunner.Outcome(cli, false, -1, false, "(skipped)");
    }
}
