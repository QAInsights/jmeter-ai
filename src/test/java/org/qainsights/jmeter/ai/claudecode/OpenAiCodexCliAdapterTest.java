package org.qainsights.jmeter.ai.claudecode;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link OpenAiCodexCliAdapter}.
 * <p>
 * The protected {@link BaseCliAdapter#findOnPath(String)} is overridden in
 * anonymous subclasses so tests never invoke a real system process.
 */
class OpenAiCodexCliAdapterTest {

    // ── getName ────────────────────────────────────────────────────────────────

    @Test
    void getName_returnsOpenAiCodexCli() {
        assertEquals("OpenAI Codex CLI", new OpenAiCodexCliAdapter().getName());
    }

    // ── toString ──────────────────────────────────────────────────────────────

    @Test
    void toString_returnsOpenAiCodexCli() {
        assertEquals("OpenAI Codex CLI", new OpenAiCodexCliAdapter().toString());
    }

    // ── getBinaryPath before detect ────────────────────────────────────────────

    @Test
    void getBinaryPath_returnsNullBeforeDetect() {
        assertNull(new OpenAiCodexCliAdapter().getBinaryPath());
    }

    // ── detect ─────────────────────────────────────────────────────────────────

    @Test
    void detect_whenCodexOnPath_returnsTrueAndSetsPath() {
        OpenAiCodexCliAdapter adapter = new OpenAiCodexCliAdapter() {
            @Override
            protected String findOnPath(String binaryName) {
                return "/usr/local/bin/codex";
            }
        };
        assertTrue(adapter.detect());
        assertEquals("/usr/local/bin/codex", adapter.getBinaryPath());
    }

    @Test
    void detect_whenCodexNotOnPath_returnsFalseAndPathRemainsNull() {
        OpenAiCodexCliAdapter adapter = new OpenAiCodexCliAdapter() {
            @Override
            protected String findOnPath(String binaryName) {
                return null;
            }
        };
        assertFalse(adapter.detect());
        assertNull(adapter.getBinaryPath());
    }

    @Test
    void detect_searchesForBinaryNamedCodex() {
        String[] capturedName = {null};
        OpenAiCodexCliAdapter adapter = new OpenAiCodexCliAdapter() {
            @Override
            protected String findOnPath(String binaryName) {
                capturedName[0] = binaryName;
                return null;
            }
        };
        adapter.detect();
        assertEquals("codex", capturedName[0]);
    }

    // ── buildCommand after detect ──────────────────────────────────────────────

    @Test
    void buildCommand_afterSuccessfulDetect_returnsListWithBinaryPath() {
        OpenAiCodexCliAdapter adapter = new OpenAiCodexCliAdapter() {
            @Override
            protected String findOnPath(String binaryName) {
                return "/usr/local/bin/codex";
            }
        };
        adapter.detect();

        List<String> command = adapter.buildCommand(null);

        assertEquals(1, command.size());
        assertEquals("/usr/local/bin/codex", command.get(0));
    }
}
