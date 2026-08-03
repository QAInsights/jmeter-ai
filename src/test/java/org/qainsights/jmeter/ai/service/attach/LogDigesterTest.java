package org.qainsights.jmeter.ai.service.attach;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LogDigester}: level detection, logger counts,
 * ERROR/WARN capping, exception extraction, and first/last lines.
 */
class LogDigesterTest {

    private static String lines(String... ls) {
        return String.join("\n", ls);
    }

    @Test
    void looksLikeLogByExtensionOrContent() {
        assertTrue(LogDigester.looksLikeLog("jmeter.log", "anything"));
        assertTrue(LogDigester.looksLikeLog("out.txt", lines(
                "2026-08-02 10:00:00,000 INFO a.B: one",
                "2026-08-02 10:00:01,000 INFO a.B: two",
                "2026-08-02 10:00:02,000 WARN a.B: three",
                "2026-08-02 10:00:03,000 INFO a.B: four")));
        assertFalse(LogDigester.looksLikeLog("out.txt", "hello\nworld"));
    }

    @Test
    void countsErrorsAndWarnings() {
        String out = LogDigester.digest(lines(
                "2026-08-02 10:00:00,000 INFO a.B: boot",
                "2026-08-02 10:00:01,000 ERROR a.B: first failure",
                "2026-08-02 10:00:02,000 WARN a.C: heads up",
                "2026-08-02 10:00:03,000 ERROR a.B: second failure"));

        assertTrue(out.contains("ERROR x 2, WARN x 1"));
        assertTrue(out.contains("first failure"));
        assertTrue(out.contains("second failure"));
        assertTrue(out.contains("heads up"));
    }

    @Test
    void loggerCountsSortedDescending() {
        String out = LogDigester.digest(lines(
                "2026-08-02 10:00:01,000 ERROR a.B: e1",
                "2026-08-02 10:00:02,000 ERROR a.B: e2",
                "2026-08-02 10:00:03,000 ERROR a.C: e3"));
        int bIndex = out.indexOf("- a.B: 2");
        int cIndex = out.indexOf("- a.C: 1");
        assertTrue(bIndex > 0 && cIndex > bIndex, "a.B (2) must sort before a.C (1)");
    }

    @Test
    void outOfMemoryExtracted() {
        String out = LogDigester.digest(lines(
                "2026-08-02 10:00:00,000 INFO a.B: boot",
                "java.lang.OutOfMemoryError: Java heap space",
                "\tat java.util.Arrays.copyOf(Arrays.java:1)",
                "\tat java.lang.StringBuilder.append(StringBuilder.java:2)"));

        assertTrue(out.contains("## Exceptions"));
        assertTrue(out.contains("OutOfMemoryError: Java heap space"));
        assertTrue(out.contains("at java.util.Arrays.copyOf"));
    }

    @Test
    void exceptionWithStackCaptured() {
        String out = LogDigester.digest(lines(
                "2026-08-02 10:00:00,000 ERROR a.B: javax.net.ssl.SSLException: Connection reset",
                "\tat sun.security.ssl.Alert.createFatalAlert(Alert.java:1)",
                "\tat sun.security.ssl.TransportContext.fatal(TransportContext.java:2)"));

        assertTrue(out.contains("SSLException: Connection reset"));
        assertTrue(out.contains("at sun.security.ssl.Alert.createFatalAlert"));
    }

    @Test
    void firstAndLastLinesPresent() {
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= 40; i++) {
            content.append("2026-08-02 10:00:").append(String.format("%02d", i))
                    .append(",000 INFO a.B: line ").append(i).append('\n');
        }
        String out = LogDigester.digest(content.toString());
        assertTrue(out.contains("line 1"));
        assertTrue(out.contains("line 40"));
        assertTrue(out.contains("## First lines"));
        assertTrue(out.contains("## Last lines"));
    }

    @Test
    void loggerOverflowCappedAtTen() {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            content.append("2026-08-02 10:00:00,000 ERROR logger.Number").append(i)
                    .append(": error ").append(i).append('\n');
        }
        String out = LogDigester.digest(content.toString());
        int loggerSection = out.indexOf("## Counts by logger");
        int errorSection = out.indexOf("## ERROR/WARN lines");
        String loggers = out.substring(loggerSection, errorSection);
        long shown = loggers.lines().filter(l -> l.startsWith("- logger.")).count();
        assertEquals(10, shown, "only the top 10 loggers are listed");
    }

    @Test
    void exceptionsCappedAtThree() {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            content.append("java.lang.RuntimeException: boom ").append(i).append('\n');
        }
        String exceptions = exceptionsSection(LogDigester.digest(content.toString()));
        assertTrue(exceptions.contains("boom 0"));
        assertTrue(exceptions.contains("boom 2"));
        assertFalse(exceptions.contains("boom 3"), "only the first 3 exceptions are extracted");
    }

    @Test
    void stackLinesCappedAtFive() {
        StringBuilder content = new StringBuilder("java.lang.IllegalStateException: deep\n");
        for (int i = 0; i < 8; i++) {
            content.append("\tat com.example.Frame").append(i).append(".call(Frame").append(i).append(".java:1)\n");
        }
        String exceptions = exceptionsSection(LogDigester.digest(content.toString()));
        assertTrue(exceptions.contains("at com.example.Frame4"));
        assertFalse(exceptions.contains("at com.example.Frame5"), "only the first 5 stack lines are kept");
    }

    /** The "## Exceptions" section of a digest (up to the following section). */
    private static String exceptionsSection(String digest) {
        int start = digest.indexOf("## Exceptions");
        assertTrue(start >= 0, "digest must contain an Exceptions section");
        int end = digest.indexOf("## First lines", start);
        return end > start ? digest.substring(start, end) : digest.substring(start);
    }

    @Test
    void malformedLinesTolerated() {
        String out = LogDigester.digest(lines(
                "not a log line at all",
                "2026-13-99 99:99 broken timestamp INFO x: weird",
                "ERROR without logger prefix",
                "",
                ">>> garbage <<<"));
        assertTrue(out.contains("ERROR x 1"), "unprefixed ERROR lines still count");
        assertTrue(out.contains("## Log digest"));
    }

    @Test
    void emptyContentDigests() {
        String out = LogDigester.digest("");
        assertTrue(out.contains("0 lines total") || out.contains("1 lines total"),
                "empty content must produce a digest, not crash");
    }

    @Test
    void errorWarnLinesCapped() {
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= 60; i++) {
            content.append("2026-08-02 10:00:00,000 ERROR a.B: error ").append(i).append('\n');
        }
        String out = LogDigester.digest(content.toString());
        assertTrue(out.contains("(first 40 of 60)"));
        assertTrue(out.contains("error 40"));
        assertFalse(out.contains("error 41\n"));
    }
}
