package org.qainsights.jmeter.ai.claudecode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ClaudeCodeCliAdapter}.
 * <p>
 * {@link ClaudeCodeLocator#findClaudeCodeBinary()} is mocked statically so that
 * the tests do not depend on the host machine's Node/npm installation.
 * The protected {@link BaseCliAdapter#findOnPath(String)} hook is overridden in
 * anonymous subclasses to control the PATH-search leg of {@code detect()}.
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
    void detect_whenLocatorReturnsValidExecutable_returnsTrueAndSetsPath()
            throws IOException {
        File exe = tempDir.resolve("claude").toFile();
        exe.createNewFile();
        exe.setExecutable(true);

        try (MockedStatic<ClaudeCodeLocator> locatorMock =
                     mockStatic(ClaudeCodeLocator.class)) {
            locatorMock.when(ClaudeCodeLocator::findClaudeCodeBinary)
                    .thenReturn(exe.getAbsolutePath());

            ClaudeCodeCliAdapter adapter = new ClaudeCodeCliAdapter();
            assertTrue(adapter.detect());
            assertEquals(exe.getAbsolutePath(), adapter.getBinaryPath());
        }
    }

    @Test
    void detect_whenLocatorReturnsNonExistentPath_andFoundOnPath_returnsTrue() {
        try (MockedStatic<ClaudeCodeLocator> locatorMock =
                     mockStatic(ClaudeCodeLocator.class)) {
            locatorMock.when(ClaudeCodeLocator::findClaudeCodeBinary)
                    .thenReturn("/does/not/exist/claude");

            ClaudeCodeCliAdapter adapter = new ClaudeCodeCliAdapter() {
                @Override
                protected String findOnPath(String binaryName) {
                    return "/usr/local/bin/claude";
                }
            };
            assertTrue(adapter.detect());
            assertEquals("/usr/local/bin/claude", adapter.getBinaryPath());
        }
    }

    @Test
    void detect_whenLocatorReturnsNonExistentPath_andNotOnPath_returnsFalse() {
        try (MockedStatic<ClaudeCodeLocator> locatorMock =
                     mockStatic(ClaudeCodeLocator.class)) {
            locatorMock.when(ClaudeCodeLocator::findClaudeCodeBinary)
                    .thenReturn("/does/not/exist/claude");

            ClaudeCodeCliAdapter adapter = new ClaudeCodeCliAdapter() {
                @Override
                protected String findOnPath(String binaryName) {
                    return null;
                }
            };
            assertFalse(adapter.detect());
        }
    }

    @Test
    void detect_whenLocatorReturnsNull_andFoundOnPath_returnsTrue() {
        try (MockedStatic<ClaudeCodeLocator> locatorMock =
                     mockStatic(ClaudeCodeLocator.class)) {
            locatorMock.when(ClaudeCodeLocator::findClaudeCodeBinary)
                    .thenReturn(null);

            ClaudeCodeCliAdapter adapter = new ClaudeCodeCliAdapter() {
                @Override
                protected String findOnPath(String binaryName) {
                    return "/opt/claude/bin/claude";
                }
            };
            assertTrue(adapter.detect());
            assertEquals("/opt/claude/bin/claude", adapter.getBinaryPath());
        }
    }

    @Test
    void detect_whenLocatorReturnsNull_andNotOnPath_returnsFalse() {
        try (MockedStatic<ClaudeCodeLocator> locatorMock =
                     mockStatic(ClaudeCodeLocator.class)) {
            locatorMock.when(ClaudeCodeLocator::findClaudeCodeBinary)
                    .thenReturn(null);

            ClaudeCodeCliAdapter adapter = new ClaudeCodeCliAdapter() {
                @Override
                protected String findOnPath(String binaryName) {
                    return null;
                }
            };
            assertFalse(adapter.detect());
            assertNull(adapter.getBinaryPath());
        }
    }

    @Test
    void detect_searchesForBinaryNamedClaude() {
        try (MockedStatic<ClaudeCodeLocator> locatorMock =
                     mockStatic(ClaudeCodeLocator.class)) {
            locatorMock.when(ClaudeCodeLocator::findClaudeCodeBinary)
                    .thenReturn(null);

            // Capture binaryName argument to assert it equals "claude"
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
    }
}
