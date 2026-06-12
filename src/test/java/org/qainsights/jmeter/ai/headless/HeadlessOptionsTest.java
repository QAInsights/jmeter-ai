package org.qainsights.jmeter.ai.headless;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HeadlessOptionsTest {

    @Test
    void defaults_areSensible() {
        HeadlessOptions o = HeadlessOptions.parse(new String[]{});
        assertEquals("kiro", o.cli);
        assertEquals("jmeter-ai-report.md", o.output);
        assertEquals("md", o.format);
        assertEquals(300, o.timeoutSeconds);
        assertFalse(o.failOnError);
        assertFalse(o.help);
    }

    @Test
    void parsesAllFlags() {
        HeadlessOptions o = HeadlessOptions.parse(new String[]{
                "--cli", "kiro", "--prompt", "do it", "--jmx", "t.jmx",
                "--working-dir", "/tmp/x", "--output", "r.json", "--format", "json",
                "--timeout", "60", "--fail-on-error"});
        assertEquals("do it", o.prompt);
        assertEquals("t.jmx", o.jmx);
        assertEquals("/tmp/x", o.workingDir);
        assertEquals("r.json", o.output);
        assertEquals("json", o.format);
        assertEquals(60, o.timeoutSeconds);
        assertTrue(o.failOnError);
    }

    @Test
    void parsesGenerateFlags() {
        HeadlessOptions o = HeadlessOptions.parse(new String[]{
                "--generate-from", "api.har", "--generate-out", "plan.jmx"});
        assertEquals("api.har", o.generateFrom);
        assertEquals("plan.jmx", o.generateOut);
    }

    @Test
    void helpFlagSetsHelp() {
        assertTrue(HeadlessOptions.parse(new String[]{"--help"}).help);
        assertTrue(HeadlessOptions.parse(new String[]{"-h"}).help);
    }

    @Test
    void unknownArgumentThrows() {
        HeadlessUsageException e = assertThrows(HeadlessUsageException.class,
                () -> HeadlessOptions.parse(new String[]{"--bogus"}));
        assertTrue(e.getMessage().contains("Unknown argument"));
    }

    @Test
    void missingValueThrows() {
        assertThrows(HeadlessUsageException.class,
                () -> HeadlessOptions.parse(new String[]{"--prompt"}));
    }

    @Test
    void invalidFormatThrows() {
        assertThrows(HeadlessUsageException.class,
                () -> HeadlessOptions.parse(new String[]{"--format", "xml"}));
    }

    @Test
    void invalidTimeoutThrows() {
        assertThrows(HeadlessUsageException.class,
                () -> HeadlessOptions.parse(new String[]{"--timeout", "abc"}));
        assertThrows(HeadlessUsageException.class,
                () -> HeadlessOptions.parse(new String[]{"--timeout", "0"}));
    }
}
