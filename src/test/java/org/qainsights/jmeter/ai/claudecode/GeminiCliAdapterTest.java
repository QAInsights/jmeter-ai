package org.qainsights.jmeter.ai.claudecode;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GeminiCliAdapter}.
 * <p>
 * The protected {@link BaseCliAdapter#findOnPath(String)} is overridden in
 * anonymous subclasses so tests never invoke a real system process.
 */
class GeminiCliAdapterTest {

    // ── getName ────────────────────────────────────────────────────────────────

    @Test
    void getName_returnsGeminiCli() {
        assertEquals("Gemini CLI", new GeminiCliAdapter().getName());
    }

    // ── toString ──────────────────────────────────────────────────────────────

    @Test
    void toString_returnsGeminiCli() {
        assertEquals("Gemini CLI", new GeminiCliAdapter().toString());
    }

    // ── getBinaryPath before detect ────────────────────────────────────────────

    @Test
    void getBinaryPath_returnsNullBeforeDetect() {
        assertNull(new GeminiCliAdapter().getBinaryPath());
    }

    // ── detect ─────────────────────────────────────────────────────────────────

    @Test
    void detect_whenGeminiOnPath_returnsTrueAndSetsPath() {
        GeminiCliAdapter adapter = new GeminiCliAdapter() {
            @Override
            protected String findOnPath(String binaryName) {
                return "/usr/local/bin/gemini";
            }
        };
        assertTrue(adapter.detect());
        assertEquals("/usr/local/bin/gemini", adapter.getBinaryPath());
    }

    @Test
    void detect_whenGeminiNotOnPath_returnsFalseAndPathRemainsNull() {
        GeminiCliAdapter adapter = new GeminiCliAdapter() {
            @Override
            protected String findOnPath(String binaryName) {
                return null;
            }
        };
        assertFalse(adapter.detect());
        assertNull(adapter.getBinaryPath());
    }

    @Test
    void detect_searchesForBinaryNamedGemini() {
        String[] capturedName = {null};
        GeminiCliAdapter adapter = new GeminiCliAdapter() {
            @Override
            protected String findOnPath(String binaryName) {
                capturedName[0] = binaryName;
                return null;
            }
        };
        adapter.detect();
        assertEquals("gemini", capturedName[0]);
    }

    // ── buildCommand after detect ──────────────────────────────────────────────

    @Test
    void buildCommand_afterSuccessfulDetect_returnsListWithBinaryPath() {
        GeminiCliAdapter adapter = new GeminiCliAdapter() {
            @Override
            protected String findOnPath(String binaryName) {
                return "/usr/local/bin/gemini";
            }
        };
        adapter.detect();

        List<String> command = adapter.buildCommand(null);

        assertEquals(1, command.size());
        assertEquals("/usr/local/bin/gemini", command.get(0));
    }
}
