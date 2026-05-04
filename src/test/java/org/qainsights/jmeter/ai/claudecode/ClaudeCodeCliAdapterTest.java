package org.qainsights.jmeter.ai.claudecode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ClaudeCodeCliAdapter}.
 * <p>
 * The protected {@link BaseCliAdapter#findOnPath(String)} hook is overridden in
 * anonymous subclasses to control the PATH-search behavior of {@code detect()}.
 */
class ClaudeCodeCliAdapterTest {

    @TempDir
    Path tempDir;

    // ── getName ────────────────────────────────────────────────────────────────

    @Test
    void getName_returnsClaudeCode() {
        assertEquals("Claude Code", new ClaudeCodeCliAdapter().getName());
    }

    // ── buildCommand ───────────────────────────────────────────────────────────

    @Test
    void buildCommand_withNullWorkingDirectory_returnsPathOnly() {
        ClaudeCodeCliAdapter adapter = new ClaudeCodeCliAdapter();
        adapter.detectedPath = "/usr/local/bin/claude";

        List<String> command = adapter.buildCommand(null);

        assertEquals(1, command.size());
        assertEquals("/usr/local/bin/claude", command.get(0));
    }

    @Test
    void buildCommand_withWorkingDirectory_insertsAddDirFlag() {
        ClaudeCodeCliAdapter adapter = new ClaudeCodeCliAdapter();
        adapter.detectedPath = "/usr/local/bin/claude";

        List<String> command = adapter.buildCommand("/test/plan/dir");

        assertEquals(3, command.size());
        assertEquals("/usr/local/bin/claude", command.get(0));
        assertEquals("--add-dir", command.get(1));
        assertEquals("/test/plan/dir", command.get(2));
    }

    @Test
    void buildCommand_workingDirectoryIsThirdToken() {
        ClaudeCodeCliAdapter adapter = new ClaudeCodeCliAdapter();
        adapter.detectedPath = "/path/to/claude";

        List<String> command = adapter.buildCommand("/my/project");

        assertEquals("/my/project", command.get(2));
    }

    // ── detect ─────────────────────────────────────────────────────────────────

    @Test
    void detect_whenFoundOnPathAndExecutable_returnsTrueAndSetsPath() {
        ClaudeCodeCliAdapter adapter = new ClaudeCodeCliAdapter() {
            @Override
            protected String findOnPath(String binaryName) {
                return "/usr/local/bin/claude";
            }
        };

        // Mock the file existence check by using a real temp file
        File mockFile = new File("/usr/local/bin/claude") {
            @Override
            public boolean exists() {
                return true;
            }

            @Override
            public boolean canExecute() {
                return true;
            }
        };

        assertTrue(adapter.detect());
        assertEquals("/usr/local/bin/claude", adapter.getBinaryPath());
    }

    @Test
    void detect_whenNotFoundOnPath_returnsFalse() {
        ClaudeCodeCliAdapter adapter = new ClaudeCodeCliAdapter() {
            @Override
            protected String findOnPath(String binaryName) {
                return null;
            }
        };

        assertFalse(adapter.detect());
        assertNull(adapter.getBinaryPath());
    }

    @Test
    void detect_searchesForBinaryNamedClaude() {
        String[] capturedName = {null};
        ClaudeCodeCliAdapter adapter = new ClaudeCodeCliAdapter() {
            @Override
            protected String findOnPath(String binaryName) {
                capturedName[0] = binaryName;
                return null;
            }
        };
        adapter.detect();
        assertEquals("claude", capturedName[0]);
    }

    // ── isEnabled ───────────────────────────────────────────────────────────────

    @Test
    void isEnabled_returnsTrueByDefault() {
        // Base implementation returns true; override checks properties
        assertTrue(new ClaudeCodeCliAdapter().isEnabled());
    }

    // ── defaultPrompt ────────────────────────────────────────────────────────────

    @Test
    void defaultPrompt_containsPerformanceEngineer() {
        String prompt = new ClaudeCodeCliAdapter().defaultPrompt();
        assertTrue(prompt.toLowerCase().contains("performance engineer"));
    }
}
