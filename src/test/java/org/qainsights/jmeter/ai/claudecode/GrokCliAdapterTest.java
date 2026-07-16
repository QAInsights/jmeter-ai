package org.qainsights.jmeter.ai.claudecode;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GrokCliAdapter}.
 * <p>
 * The protected {@link BaseCliAdapter#findOnPath(String)} is overridden in
 * anonymous subclasses so tests never invoke a real system process.
 */
class GrokCliAdapterTest {

    @Test
    void getName_returnsGrokCli() {
        assertEquals("Grok CLI", new GrokCliAdapter().getName());
    }

    @Test
    void toString_returnsGrokCli() {
        assertEquals("Grok CLI", new GrokCliAdapter().toString());
    }

    @Test
    void getBinaryPath_returnsNullBeforeDetect() {
        assertNull(new GrokCliAdapter().getBinaryPath());
    }

    @Test
    void detect_whenGrokOnPath_returnsTrueAndSetsPath() {
        GrokCliAdapter adapter = new GrokCliAdapter() {
            @Override
            protected String findOnPath(String binaryName) {
                return "/usr/local/bin/grok";
            }
        };
        assertTrue(adapter.detect());
        assertEquals("/usr/local/bin/grok", adapter.getBinaryPath());
    }

    @Test
    void detect_whenGrokNotOnPath_returnsFalseAndPathRemainsNull() {
        GrokCliAdapter adapter = new GrokCliAdapter() {
            @Override
            protected String findOnPath(String binaryName) {
                return null;
            }
        };
        assertFalse(adapter.detect());
        assertNull(adapter.getBinaryPath());
    }

    @Test
    void detect_searchesForBinaryNamedGrok() {
        String[] capturedName = {null};
        GrokCliAdapter adapter = new GrokCliAdapter() {
            @Override
            protected String findOnPath(String binaryName) {
                capturedName[0] = binaryName;
                return null;
            }
        };
        adapter.detect();
        assertEquals("grok", capturedName[0]);
    }

    @Test
    void buildCommand_afterSuccessfulDetect_returnsListWithBinaryPath() {
        GrokCliAdapter adapter = new GrokCliAdapter() {
            @Override
            protected String findOnPath(String binaryName) {
                return "/usr/local/bin/grok";
            }
        };
        adapter.detect();

        List<String> command = adapter.buildCommand(null);

        assertEquals(1, command.size());
        assertEquals("/usr/local/bin/grok", command.get(0));
    }

    @Test
    void defaultPrompt_containsPerformanceEngineer() {
        String prompt = new GrokCliAdapter().defaultPrompt();
        assertTrue(prompt.toLowerCase().contains("performance engineer"));
    }

    @Test
    void defaultPrompt_hasNoStringConcatenationArtifacts() {
        String prompt = new GrokCliAdapter().defaultPrompt();
        assertFalse(prompt.contains("\" +"));
        assertFalse(prompt.contains("\n  "));
        assertFalse(prompt.contains("\n"));
    }
}
