package org.qainsights.jmeter.ai.claudecode;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link KiroCliAdapter}.
 * <p>
 * The protected {@link BaseCliAdapter#findOnPath(String)} is overridden in
 * anonymous subclasses so tests never invoke a real system process.
 */
class KiroCliAdapterTest {

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
        KiroCliAdapter adapter = new KiroCliAdapter() {
            @Override
            protected String findOnPath(String binaryName) {
                return "/usr/local/bin/kiro-cli";
            }
        };
        adapter.detect();

        List<String> command = adapter.buildCommand(null);

        assertEquals(2, command.size());
        assertEquals("/usr/local/bin/kiro-cli", command.get(0));
        assertEquals("chat", command.get(1));
    }
}
