package org.qainsights.jmeter.ai.security;

import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AuditLogger}. Exercises JSON encoding, the content hash,
 * and end-to-end append behaviour against a temp audit file.
 */
class AuditLoggerTest {

    @BeforeAll
    static void initJMeterProperties() throws IOException {
        if (JMeterUtils.getJMeterProperties() == null) {
            File tmp = File.createTempFile("jmeter-test", ".properties");
            tmp.deleteOnExit();
            JMeterUtils.loadJMeterProperties(tmp.getAbsolutePath());
        }
    }

    @Test
    void escape_handlesQuotesNewlinesAndControlChars() {
        assertEquals("a\\\"b", AuditLogger.escape("a\"b"));
        assertEquals("line1\\nline2", AuditLogger.escape("line1\nline2"));
        assertEquals("", AuditLogger.escape(null));
    }

    @Test
    void sha256_isStableAndHex() {
        String a = AuditLogger.sha256("hello");
        String b = AuditLogger.sha256("hello");
        assertEquals(a, b);
        assertEquals(64, a.length());
        assertTrue(a.matches("[0-9a-f]{64}"));
        assertNotEquals(a, AuditLogger.sha256("world"));
        assertEquals("", AuditLogger.sha256(null));
    }

    @Test
    void recordLaunch_appendsOneJsonLineWithExpectedFields(@TempDir Path dir) throws IOException {
        Path audit = dir.resolve("audit.log");
        withProperty("jmeter.ai.security.audit.file", audit.toString(), () -> {
            List<String> command = Arrays.asList("/usr/local/bin/kiro-cli", "chat",
                    "--trust-tools=read,grep,fs_read");
            AuditLogger.recordLaunch("AWS Kiro", "/usr/local/bin/kiro-cli", command,
                    "/work/dir", "  | HTTPSampler.path = /api");
        });

        List<String> lines = Files.readAllLines(audit);
        assertEquals(1, lines.size());
        String line = lines.get(0);
        assertTrue(line.startsWith("{") && line.endsWith("}"), line);
        assertTrue(line.contains("\"event\":\"ai_cli_launch\""), line);
        assertTrue(line.contains("\"cli\":\"AWS Kiro\""), line);
        assertTrue(line.contains("--trust-tools=read,grep,fs_read"), line);
        assertTrue(line.contains("\"contextSha256\":\""), line);
        assertFalse(line.contains("\n\n"));
    }

    @Test
    void recordLaunch_whenDisabled_writesNothing(@TempDir Path dir) throws IOException {
        Path audit = dir.resolve("audit-disabled.log");
        withProperty("jmeter.ai.security.audit.enabled", "false", () ->
                withProperty("jmeter.ai.security.audit.file", audit.toString(), () ->
                        AuditLogger.recordLaunch("AWS Kiro", "/bin/kiro-cli",
                                Arrays.asList("/bin/kiro-cli", "chat"), "/work", "ctx")));
        assertFalse(Files.exists(audit), "no audit file should be created when disabled");
    }

    private static void withProperty(String key, String value, Runnable body) {
        String prev = JMeterUtils.getProperty(key);
        try {
            JMeterUtils.setProperty(key, value);
            body.run();
        } finally {
            // Remove (not blank out) when the key was previously unset, so the
            // configured default applies again regardless of test order.
            if (prev == null) {
                JMeterUtils.getJMeterProperties().remove(key);
            } else {
                JMeterUtils.setProperty(key, prev);
            }
        }
    }
}
