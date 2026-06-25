package org.qainsights.jmeter.ai.claudecode;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CopilotCliAdapter}.
 * <p>
 * The protected {@link BaseCliAdapter#findOnPath(String)} is overridden in
 * anonymous subclasses so tests never invoke a real system process.
 */
class CopilotCliAdapterTest {

    @Test
    void getName_returnsGitHubCopilotCli() {
        assertEquals("GitHub Copilot CLI", new CopilotCliAdapter().getName());
    }

    @Test
    void toString_returnsGitHubCopilotCli() {
        assertEquals("GitHub Copilot CLI", new CopilotCliAdapter().toString());
    }

    @Test
    void getBinaryPath_returnsNullBeforeDetect() {
        assertNull(new CopilotCliAdapter().getBinaryPath());
    }

    @Test
    void detect_whenCopilotOnPath_returnsTrueAndSetsPath() {
        CopilotCliAdapter adapter = new CopilotCliAdapter() {
            @Override
            protected String findOnPath(String binaryName) {
                return "/usr/local/bin/copilot";
            }
        };
        assertTrue(adapter.detect());
        assertEquals("/usr/local/bin/copilot", adapter.getBinaryPath());
    }

    @Test
    void detect_whenCopilotNotOnPath_returnsFalseAndPathRemainsNull() {
        CopilotCliAdapter adapter = new CopilotCliAdapter() {
            @Override
            protected String findOnPath(String binaryName) {
                return null;
            }
        };
        assertFalse(adapter.detect());
        assertNull(adapter.getBinaryPath());
    }

    @Test
    void detect_searchesForBinaryNamedCopilot() {
        String[] capturedName = {null};
        CopilotCliAdapter adapter = new CopilotCliAdapter() {
            @Override
            protected String findOnPath(String binaryName) {
                capturedName[0] = binaryName;
                return null;
            }
        };
        adapter.detect();
        assertEquals("copilot", capturedName[0]);
    }

    @Test
    void buildCommand_afterSuccessfulDetect_returnsListWithBinaryPath() {
        CopilotCliAdapter adapter = new CopilotCliAdapter() {
            @Override
            protected String findOnPath(String binaryName) {
                return "/usr/local/bin/copilot";
            }
        };
        adapter.detect();

        List<String> command = adapter.buildCommand(null);

        assertEquals(1, command.size());
        assertEquals("/usr/local/bin/copilot", command.get(0));
    }

    // ── defaultPrompt ────────────────────────────────────────────────────────────

    @Test
    void defaultPrompt_containsPerformanceEngineer() {
        String prompt = new CopilotCliAdapter().defaultPrompt();
        assertTrue(prompt.toLowerCase().contains("performance engineer"));
    }

    @Test
    void defaultPrompt_hasNoStringConcatenationArtifacts() {
        String prompt = new CopilotCliAdapter().defaultPrompt();
        // Guards against the copy-paste regression where the fallback literal
        // contained `" +` and source-indentation newlines.
        assertFalse(prompt.contains("\" +"));
        assertFalse(prompt.contains("\n  "));
        assertFalse(prompt.contains("\n"));
    }
}
