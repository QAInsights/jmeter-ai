package org.qainsights.jmeter.ai.claudecode;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AntigravityCliAdapter}.
 * <p>
 * The protected {@link BaseCliAdapter#findOnPath(String)} is overridden in
 * anonymous subclasses so tests never invoke a real system process.
 */
class AntigravityCliAdapterTest {

    // ── getName ────────────────────────────────────────────────────────────────

    @Test
    void getName_returnsAntigravityCli() {
        assertEquals("Antigravity CLI", new AntigravityCliAdapter().getName());
    }

    // ── toString ──────────────────────────────────────────────────────────────

    @Test
    void toString_returnsAntigravityCli() {
        assertEquals("Antigravity CLI", new AntigravityCliAdapter().toString());
    }

    // ── getBinaryPath before detect ────────────────────────────────────────────

    @Test
    void getBinaryPath_returnsNullBeforeDetect() {
        assertNull(new AntigravityCliAdapter().getBinaryPath());
    }

    // ── detect ─────────────────────────────────────────────────────────────────

    @Test
    void detect_whenAgyOnPath_returnsTrueAndSetsPath() {
        AntigravityCliAdapter adapter = new AntigravityCliAdapter() {
            @Override
            protected String findOnPath(String binaryName) {
                return "/usr/local/bin/agy";
            }
        };
        assertTrue(adapter.detect());
        assertEquals("/usr/local/bin/agy", adapter.getBinaryPath());
    }

    @Test
    void detect_whenAgyNotOnPath_returnsFalseAndPathRemainsNull() {
        AntigravityCliAdapter adapter = new AntigravityCliAdapter() {
            @Override
            protected String findOnPath(String binaryName) {
                return null;
            }
        };
        assertFalse(adapter.detect());
        assertNull(adapter.getBinaryPath());
    }

    @Test
    void detect_searchesForBinaryNamedAgy() {
        String[] capturedName = {null};
        AntigravityCliAdapter adapter = new AntigravityCliAdapter() {
            @Override
            protected String findOnPath(String binaryName) {
                capturedName[0] = binaryName;
                return null;
            }
        };
        adapter.detect();
        assertEquals("agy", capturedName[0]);
    }

    // ── buildCommand after detect ──────────────────────────────────────────────

    @Test
    void buildCommand_afterSuccessfulDetect_returnsListWithBinaryPath() {
        AntigravityCliAdapter adapter = new AntigravityCliAdapter() {
            @Override
            protected String findOnPath(String binaryName) {
                return "/usr/local/bin/agy";
            }
        };
        adapter.detect();

        List<String> command = adapter.buildCommand(null);

        assertEquals(1, command.size());
        assertEquals("/usr/local/bin/agy", command.get(0));
    }

    // ── defaultPrompt ────────────────────────────────────────────────────────────

    @Test
    void defaultPrompt_containsPerformanceEngineer() {
        String prompt = new AntigravityCliAdapter().defaultPrompt();
        assertTrue(prompt.toLowerCase().contains("performance engineer"));
    }

    @Test
    void defaultPrompt_hasNoStringConcatenationArtifacts() {
        String prompt = new AntigravityCliAdapter().defaultPrompt();
        // Guards against the copy-paste regression where the fallback literal
        // contained `" +` and source-indentation newlines.
        assertFalse(prompt.contains("\" +"));
        assertFalse(prompt.contains("\n  "));
        assertFalse(prompt.contains("\n"));
    }
}
