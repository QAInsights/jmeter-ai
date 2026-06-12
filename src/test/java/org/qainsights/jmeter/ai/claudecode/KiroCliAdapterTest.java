package org.qainsights.jmeter.ai.claudecode;

import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link KiroCliAdapter}.
 * <p>
 * The protected {@link BaseCliAdapter#findOnPath(String)} is overridden in
 * anonymous subclasses so tests never invoke a real system process.
 */
class KiroCliAdapterTest {

    @BeforeAll
    static void initJMeterProperties() throws IOException {
        // setProperty needs an initialized properties object; load an empty one.
        if (JMeterUtils.getJMeterProperties() == null) {
            File tmp = File.createTempFile("jmeter-test", ".properties");
            tmp.deleteOnExit();
            JMeterUtils.loadJMeterProperties(tmp.getAbsolutePath());
        }
    }

    // ── getName ────────────────────────────────────────────────────────────────

    @Test
    void getName_returnsAwsKiro() {
        assertEquals("AWS Kiro", new KiroCliAdapter().getName());
    }

    @Test
    void toString_returnsAwsKiro() {
        assertEquals("AWS Kiro", new KiroCliAdapter().toString());
    }

    // ── getBinaryPath before detect ────────────────────────────────────────────

    @Test
    void getBinaryPath_returnsNullBeforeDetect() {
        assertNull(new KiroCliAdapter().getBinaryPath());
    }

    // ── detect ─────────────────────────────────────────────────────────────────

    @Test
    void detect_whenKiroCliOnPath_returnsTrueAndSetsPath() {
        KiroCliAdapter adapter = new KiroCliAdapter() {
            @Override
            protected String findOnPath(String binaryName) {
                return "kiro-cli".equals(binaryName) ? "/home/me/.kiro/bin/kiro-cli" : null;
            }
        };
        assertTrue(adapter.detect());
        assertEquals("/home/me/.kiro/bin/kiro-cli", adapter.getBinaryPath());
    }

    @Test
    void detect_prefersKiroCliOverShortKiroAlias() {
        String[] firstQueried = {null};
        KiroCliAdapter adapter = new KiroCliAdapter() {
            @Override
            protected String findOnPath(String binaryName) {
                if (firstQueried[0] == null) {
                    firstQueried[0] = binaryName;
                }
                return null;
            }
        };
        adapter.detect();
        assertEquals("kiro-cli", firstQueried[0]);
    }

    @Test
    void detect_fallsBackToKiroAliasWhenKiroCliMissing() {
        KiroCliAdapter adapter = new KiroCliAdapter() {
            @Override
            protected String findOnPath(String binaryName) {
                return "kiro".equals(binaryName) ? "/usr/local/bin/kiro" : null;
            }
        };
        assertTrue(adapter.detect());
        assertEquals("/usr/local/bin/kiro", adapter.getBinaryPath());
    }

    // ── buildCommand ───────────────────────────────────────────────────────────

    @Test
    void buildCommand_afterDetect_appendsChatSubcommand() {
        KiroCliAdapter adapter = detectedAdapter("/usr/local/bin/kiro-cli");

        List<String> command = adapter.buildCommand(null);

        assertEquals("/usr/local/bin/kiro-cli", command.get(0));
        assertEquals("chat", command.get(1));
    }

    // ── tool-trust policy ──────────────────────────────────────────────────────

    @Test
    void buildCommand_byDefault_restrictsToReadOnlyTrustedTools() {
        KiroCliAdapter adapter = detectedAdapter("/usr/local/bin/kiro-cli");

        List<String> command = adapter.buildCommand(null);

        assertTrue(command.contains("--trust-tools=read,grep,fs_read"),
                "default policy must trust only read-only tools: " + command);
        assertFalse(command.contains("--trust-all-tools"));
    }

    @Test
    void buildCommand_whenTrustAllToolsEnabled_usesTrustAllFlag() {
        withProperty("jmeter.ai.terminal.kiro.trust_all_tools", "true", () -> {
            List<String> command = detectedAdapter("/usr/local/bin/kiro-cli").buildCommand(null);
            assertTrue(command.contains("--trust-all-tools"), command.toString());
            assertFalse(command.stream().anyMatch(s -> s.startsWith("--trust-tools=")));
        });
    }

    @Test
    void buildCommand_whenTrustToolsBlank_omitsTrustFlag() {
        withProperty("jmeter.ai.terminal.kiro.trust_tools", "", () -> {
            List<String> command = detectedAdapter("/usr/local/bin/kiro-cli").buildCommand(null);
            assertFalse(command.stream().anyMatch(s -> s.startsWith("--trust-tools")), command.toString());
        });
    }

    // ── headless mode ──────────────────────────────────────────────────────────

    @Test
    void supportsHeadless_isTrue() {
        assertTrue(new KiroCliAdapter().supportsHeadless());
    }

    @Test
    void buildHeadlessCommand_includesNoInteractiveTrustAndPrompt() {
        KiroCliAdapter adapter = detectedAdapter("/usr/local/bin/kiro-cli");

        List<String> command = adapter.buildHeadlessCommand("Lint this plan", null);

        assertEquals("/usr/local/bin/kiro-cli", command.get(0));
        assertEquals("chat", command.get(1));
        assertTrue(command.contains("--no-interactive"), command.toString());
        assertTrue(command.contains("--trust-tools=read,grep,fs_read"), command.toString());
        assertEquals("Lint this plan", command.get(command.size() - 1),
                "prompt must be the final argument");
    }

    @Test
    void buildHeadlessCommand_nullPromptBecomesEmptyArg() {
        KiroCliAdapter adapter = detectedAdapter("/usr/local/bin/kiro-cli");
        List<String> command = adapter.buildHeadlessCommand(null, null);
        assertEquals("", command.get(command.size() - 1));
    }

    private static KiroCliAdapter detectedAdapter(String binary) {
        KiroCliAdapter adapter = new KiroCliAdapter() {
            @Override
            protected String findOnPath(String binaryName) {
                return binary;
            }
        };
        adapter.detect();
        return adapter;
    }

    private static void withProperty(String key, String value, Runnable body) {
        String prev = JMeterUtils.getProperty(key);
        try {
            JMeterUtils.setProperty(key, value);
            body.run();
        } finally {
            // Remove (not blank out) when previously unset, so the default applies
            // again regardless of test order.
            if (prev == null) {
                JMeterUtils.getJMeterProperties().remove(key);
            } else {
                JMeterUtils.setProperty(key, prev);
            }
        }
    }
}
