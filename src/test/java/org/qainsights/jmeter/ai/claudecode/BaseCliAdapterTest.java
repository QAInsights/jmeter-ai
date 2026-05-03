package org.qainsights.jmeter.ai.claudecode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BaseCliAdapter}.
 * Uses a minimal concrete subclass to exercise the base-class logic without
 * relying on any external process or file-system state.
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
