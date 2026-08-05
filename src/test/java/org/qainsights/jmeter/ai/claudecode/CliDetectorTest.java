package org.qainsights.jmeter.ai.claudecode;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CliDetector}.
 * <p>
 * Uses lightweight anonymous {@link AiCliAdapter} implementations so no real
 * process is spawned and no JMeter/Swing environment is required. The branching
 * contract (disabled / enabled-but-missing / detected) is verified through the
 * returned {@link Optional} and through whether {@code detect()} is invoked.
 * <p>
 * The INFO log lines emitted by {@code CliDetector} are not asserted here: the
 * headless test runtime binds SLF4J to the NOP logger (the project's
 * {@code log4j-slf4j-impl} binding targets SLF4J 1.7.x and is ignored by the
 * SLF4J 2.x API on the test classpath), so log events are discarded. The lines
 * are exercised in production where JMeter supplies a real SLF4J binding.
 */
class CliDetectorTest {

    private static final Logger log = LoggerFactory.getLogger(CliDetectorTest.class);

    // ── disabled by config ─────────────────────────────────────────────────────

    @Test
    void evaluate_whenDisabled_returnsEmptyAndDoesNotDetect() {
        boolean[] detectCalled = {false};
        AiCliAdapter adapter = stub("OpenCode", false, null, detectCalled);

        Optional<AiCliAdapter> result = CliDetector.evaluate(adapter, log);

        assertTrue(result.isEmpty());
        assertFalse(detectCalled[0], "detect() must not run when the CLI is disabled");
    }

    // ── enabled but not on PATH ────────────────────────────────────────────────

    @Test
    void evaluate_whenEnabledButNotDetected_returnsEmpty() {
        AiCliAdapter adapter = stub("Codex", true, null, new boolean[1]);

        Optional<AiCliAdapter> result = CliDetector.evaluate(adapter, log);

        assertTrue(result.isEmpty());
    }

    @Test
    void evaluate_whenEnabledButNotDetected_invokesDetect() {
        boolean[] detectCalled = {false};
        AiCliAdapter adapter = stub("Codex", true, null, detectCalled);

        CliDetector.evaluate(adapter, log);

        assertTrue(detectCalled[0], "detect() must run when the CLI is enabled");
    }

    // ── enabled and detected ───────────────────────────────────────────────────

    @Test
    void evaluate_whenEnabledAndDetected_returnsAdapter() {
        AiCliAdapter adapter = stub("Claude Code", true, "/usr/local/bin/claude", new boolean[1]);

        Optional<AiCliAdapter> result = CliDetector.evaluate(adapter, log);

        assertTrue(result.isPresent());
        assertSame(adapter, result.get());
    }

    @Test
    void evaluate_whenEnabledAndDetected_preservesBinaryPath() {
        AiCliAdapter adapter = stub("Claude Code", true, "/usr/local/bin/claude", new boolean[1]);

        Optional<AiCliAdapter> result = CliDetector.evaluate(adapter, log);

        assertTrue(result.isPresent());
        assertEquals("/usr/local/bin/claude", result.get().getBinaryPath());
    }

    // ── interface defaults ─────────────────────────────────────────────────────

    @Test
    void enablementProperty_defaultIsEmpty() {
        AiCliAdapter adapter = stub("Codex", true, null, new boolean[1]);

        assertEquals("", adapter.enablementProperty(),
                "adapters without an override must report no dedicated enablement property");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /**
     * Builds a stub {@link AiCliAdapter} with controllable {@code isEnabled()},
     * {@code detect()} (via the resolved path) and a flag recording whether
     * {@code detect()} was invoked.
     */
    private static AiCliAdapter stub(String name, boolean enabled,
                                     String resolvedPath, boolean[] detectCalled) {
        return new AiCliAdapter() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getBinaryPath() {
                return resolvedPath;
            }

            @Override
            public boolean detect() {
                detectCalled[0] = true;
                return resolvedPath != null;
            }

            @Override
            public boolean isEnabled() {
                return enabled;
            }

            @Override
            public String defaultPrompt() {
                return "stub";
            }

            @Override
            public java.util.List<String> buildCommand(String workingDirectory) {
                return java.util.Collections.emptyList();
            }
        };
    }
}
