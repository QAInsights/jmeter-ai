package org.qainsights.jmeter.ai.claudecode;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link OpenCodeCliAdapter}.
 * <p>
 * The protected {@link BaseCliAdapter#findOnPath(String)} is overridden in
 * anonymous subclasses so tests never invoke a real system process.
 */
class OpenCodeCliAdapterTest {

    // ── getName ────────────────────────────────────────────────────────────────

    @Test
    void getName_returnsOpenCode() {
        assertEquals("OpenCode", new OpenCodeCliAdapter().getName());
    }

    // ── toString ──────────────────────────────────────────────────────────────

    @Test
    void toString_returnsOpenCode() {
        assertEquals("OpenCode", new OpenCodeCliAdapter().toString());
    }

    // ── getBinaryPath before detect ────────────────────────────────────────────

    @Test
    void getBinaryPath_returnsNullBeforeDetect() {
        assertNull(new OpenCodeCliAdapter().getBinaryPath());
    }

    // ── detect ─────────────────────────────────────────────────────────────────

    @Test
    void detect_whenOpenCodeOnPath_returnsTrueAndSetsPath() {
        OpenCodeCliAdapter adapter = new OpenCodeCliAdapter() {
            @Override
            protected String findOnPath(String binaryName) {
                return "/usr/local/bin/opencode";
            }
        };
        assertTrue(adapter.detect());
        assertEquals("/usr/local/bin/opencode", adapter.getBinaryPath());
    }

    @Test
    void detect_whenOpenCodeNotOnPath_returnsFalseAndPathRemainsNull() {
        OpenCodeCliAdapter adapter = new OpenCodeCliAdapter() {
            @Override
            protected String findOnPath(String binaryName) {
                return null;
            }
        };
        assertFalse(adapter.detect());
        assertNull(adapter.getBinaryPath());
    }

    @Test
    void detect_searchesForBinaryNamedOpencode() {
        String[] capturedName = {null};
        OpenCodeCliAdapter adapter = new OpenCodeCliAdapter() {
            @Override
            protected String findOnPath(String binaryName) {
                capturedName[0] = binaryName;
                return null;
            }
        };
        adapter.detect();
        assertEquals("opencode", capturedName[0]);
    }

    // ── buildCommand after detect ──────────────────────────────────────────────

    @Test
    void buildCommand_afterSuccessfulDetect_returnsListWithBinaryPath() {
        OpenCodeCliAdapter adapter = new OpenCodeCliAdapter() {
            @Override
            protected String findOnPath(String binaryName) {
                return "/usr/local/bin/opencode";
            }
        };
        adapter.detect();

        List<String> command = adapter.buildCommand(null);

        assertEquals(1, command.size());
        assertEquals("/usr/local/bin/opencode", command.get(0));
    }
}
