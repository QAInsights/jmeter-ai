package org.qainsights.jmeter.ai.headless;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class HeadlessReportTest {

    @Test
    void status_reflectsExitAndTimeout() {
        assertEquals("SUCCESS", report(0, false).status());
        assertEquals("FAILED", report(2, false).status());
        assertEquals("TIMED_OUT", report(124, true).status());
    }

    @Test
    void markdown_containsKeySections() {
        String md = report(0, false).render("md");
        assertTrue(md.contains("# JMeter AI Headless Report"), md);
        assertTrue(md.contains("**Status:** SUCCESS"), md);
        assertTrue(md.contains("## Prompt"), md);
        assertTrue(md.contains("## Output"), md);
        assertTrue(md.contains("analyze this"), md);
        assertTrue(md.contains("kiro-cli chat --no-interactive"), md);
    }

    @Test
    void json_isWellFormedAndEscaped() {
        HeadlessReport r = new HeadlessReport("AWS Kiro", "/bin/kiro-cli",
                "say \"hi\"\nnow", "t.jmx",
                Arrays.asList("/bin/kiro-cli", "chat"), 0, false, "line1\nline2");
        String json = r.render("json");
        assertTrue(json.startsWith("{") && json.endsWith("}"), json);
        assertTrue(json.contains("\"status\":\"SUCCESS\""), json);
        assertTrue(json.contains("\"exitCode\":0"), json);
        assertTrue(json.contains("\"timedOut\":false"), json);
        // quotes and newlines escaped, not raw
        assertTrue(json.contains("say \\\"hi\\\"\\nnow"), json);
        assertFalse(json.contains("line1\nline2"), "raw newline must be escaped");
    }

    private static HeadlessReport report(int exit, boolean timedOut) {
        return new HeadlessReport("AWS Kiro", "/bin/kiro-cli", "analyze this", "t.jmx",
                Arrays.asList("/bin/kiro-cli", "chat", "--no-interactive", "analyze this"),
                exit, timedOut, "some output");
    }
}
