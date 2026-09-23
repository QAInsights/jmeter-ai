package org.qainsights.jmeter.ai.claudecode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BaseCliAdapter}.
 * Uses a minimal concrete subclass to exercise the base-class logic; only the
 * {@code findOnPath} tests spawn the real {@code where}/{@code which} lookup.
 */
class BaseCliAdapterTest {

    /** Minimal concrete implementation so we can instantiate the abstract class. */
    static class ConcreteAdapter extends BaseCliAdapter {
        private final String name;

        ConcreteAdapter(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean detect() {
            return detectedPath != null;
        }
    }

    private ConcreteAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ConcreteAdapter("TestAdapter");
    }

    // ── getBinaryPath ──────────────────────────────────────────────────────────

    @Test
    void getBinaryPath_returnsNullWhenDetectedPathIsNotSet() {
        assertNull(adapter.getBinaryPath());
    }

    @Test
    void getBinaryPath_returnsExactValueWhenDetectedPathIsSet() {
        adapter.detectedPath = "/usr/local/bin/my-tool";
        assertEquals("/usr/local/bin/my-tool", adapter.getBinaryPath());
    }

    // ── buildCommand ───────────────────────────────────────────────────────────

    @Test
    void buildCommand_returnsListWithDetectedPathAsFirstElement() {
        adapter.detectedPath = "/usr/local/bin/my-tool";
        List<String> command = adapter.buildCommand(null);
        assertEquals(1, command.size());
        assertEquals("/usr/local/bin/my-tool", command.get(0));
    }

    @Test
    void buildCommand_withWorkingDirectory_baseDoesNotAppendIt() {
        adapter.detectedPath = "/usr/local/bin/my-tool";
        List<String> command = adapter.buildCommand("/some/work/dir");
        // The base implementation intentionally ignores workingDirectory.
        assertEquals(1, command.size());
        assertEquals("/usr/local/bin/my-tool", command.get(0));
    }

    @Test
    void buildCommand_returnsModifiableList() {
        adapter.detectedPath = "/usr/local/bin/my-tool";
        List<String> command = adapter.buildCommand(null);
        // Callers (e.g. ClaudeCodeCliAdapter) must be able to add elements.
        assertDoesNotThrow(() -> command.add("extra-arg"));
        assertEquals(2, command.size());
    }

    // ── toString ──────────────────────────────────────────────────────────────

    @Test
    void toString_delegatesToGetName() {
        assertEquals("TestAdapter", adapter.toString());
    }

    // ── findOnPath (real where/which subprocess) ──────────────────────────────

    @Test
    void findOnPath_resolvesABinaryThatExistsOnThisOs() {
        String binary = BaseCliAdapter.isWindows() ? "cmd" : "sh";
        String found = adapter.findOnPath(binary);
        assertNotNull(found, "expected '" + binary + "' to be on PATH");
        assertEquals(found.trim(), found);
        assertTrue(Files.isRegularFile(Paths.get(found)) || Files.isSymbolicLink(Paths.get(found)), found);
        assertTrue(Paths.get(found).getFileName().toString().toLowerCase().startsWith(binary), found);
    }

    @Test
    void findOnPath_returnsNullForAnUnknownBinary() {
        assertNull(adapter.findOnPath("featherwand-no-such-binary-" + System.nanoTime()));
    }

    @Test
    void findOnPath_returnsNullWhenTheLookupItselfFails() {
        // a name the shell cannot even parse: the lookup exits non-zero with only an error line
        assertNull(assertDoesNotThrow(() -> adapter.findOnPath("'unterminated")));
    }

    @Test
    void lookupCommand_usesWhereOnWindowsAndWhichElsewhere() {
        assertArrayEquals(new String[]{"cmd.exe", "/c", "where", "codex"},
                BaseCliAdapter.lookupCommand("codex", true));
        assertArrayEquals(new String[]{"/bin/sh", "-c", "which codex"},
                BaseCliAdapter.lookupCommand("codex", false));
    }

    @Test
    void pickWindowsCandidate_prefersTheFirstExecutableLauncher() {
        assertEquals("C:\\npm\\codex.cmd", BaseCliAdapter.pickWindowsCandidate(
                List.of("C:\\npm\\codex", "C:\\npm\\codex.cmd", "C:\\npm\\codex.exe")));
        assertEquals("C:\\tools\\claude.EXE", BaseCliAdapter.pickWindowsCandidate(
                List.of("C:\\tools\\claude", "C:\\tools\\claude.EXE", "C:\\tools\\claude.cmd")));
        assertEquals("C:\\tools\\grok.bat", BaseCliAdapter.pickWindowsCandidate(
                List.of("C:\\tools\\grok.ps1", "C:\\tools\\grok.bat")));
    }

    @Test
    void pickWindowsCandidate_fallsBackToTheFirstMatchWhenNoneIsALauncher() {
        assertEquals("C:\\npm\\codex", BaseCliAdapter.pickWindowsCandidate(
                List.of("C:\\npm\\codex", "C:\\npm\\codex.ps1")));
        assertEquals("C:\\npm\\codex.cmd.bak", BaseCliAdapter.pickWindowsCandidate(
                List.of("C:\\npm\\codex.cmd.bak")));
    }

    @Test
    void pickWindowsCandidate_returnsNullWhenWhereFoundNothing() {
        assertNull(BaseCliAdapter.pickWindowsCandidate(List.of()));
    }

    // ── detect (delegated to concrete subclass) ────────────────────────────────

    @Test
    void detect_returnsFalseWhenDetectedPathIsNull() {
        assertFalse(adapter.detect());
    }

    @Test
    void detect_returnsTrueWhenDetectedPathIsSet() {
        adapter.detectedPath = "/usr/local/bin/my-tool";
        assertTrue(adapter.detect());
    }
}
