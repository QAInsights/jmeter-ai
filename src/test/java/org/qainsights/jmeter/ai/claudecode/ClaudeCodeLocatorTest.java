package org.qainsights.jmeter.ai.claudecode;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.qainsights.jmeter.ai.utils.AiConfig;

/**
 * Unit tests for {@link ClaudeCodeLocator#findClaudeCodeBinary()}.
 *
 * AiConfig.getProperty is mocked for each test so the results are deterministic
 * regardless of any properties file present on the build machine.
 */
class ClaudeCodeLocatorTest {

    @TempDir
    Path tempDir;

    private MockedStatic<AiConfig> aiConfigMock;

    private static final String ENABLED_KEY =
        "jmeter.ai.terminal.claudecode.enabled";
    private static final String PATH_KEY = "jmeter.ai.terminal.claudecode.path";

    @BeforeEach
    void setUp() {
        aiConfigMock = mockStatic(AiConfig.class);
    }

    @AfterEach
    void tearDown() {
        aiConfigMock.close();
    }

    // ── feature disabled ───────────────────────────────────────────────────────

    @Test
    void findClaudeCodeBinary_whenDisabled_returnsNull() {
        aiConfigMock
            .when(() -> AiConfig.getProperty(ENABLED_KEY, "false"))
            .thenReturn("false");

        assertNull(ClaudeCodeLocator.findClaudeCodeBinary());
    }

    @Test
    void findClaudeCodeBinary_enabledCheckIsCaseSensitive_uppercaseTrueYieldsNull() {
        // Implementation uses .equals("true") so only exact lowercase enables it.
        aiConfigMock
            .when(() -> AiConfig.getProperty(ENABLED_KEY, "false"))
            .thenReturn("TRUE");

        assertNull(ClaudeCodeLocator.findClaudeCodeBinary());
    }

    // ── feature enabled – valid property path ─────────────────────────────────

    @Test
    void findClaudeCodeBinary_whenEnabledAndValidPropertyPath_returnsPropertyPath()
        throws Exception {
        File exe = tempDir.resolve("claude").toFile();
        assertTrue(exe.createNewFile());
        exe.setExecutable(true);

        aiConfigMock
            .when(() -> AiConfig.getProperty(ENABLED_KEY, "false"))
            .thenReturn("true");
        aiConfigMock
            .when(() -> AiConfig.getProperty(PATH_KEY, ""))
            .thenReturn(exe.getAbsolutePath());

        String result = ClaudeCodeLocator.findClaudeCodeBinary();

        assertEquals(exe.getAbsolutePath(), result);
    }

    // ── feature enabled – property path present but file missing ─────────────

    @Test
    void findClaudeCodeBinary_whenPropertyPathDoesNotExist_doesNotThrow() {
        aiConfigMock
            .when(() -> AiConfig.getProperty(ENABLED_KEY, "false"))
            .thenReturn("true");
        aiConfigMock
            .when(() -> AiConfig.getProperty(PATH_KEY, ""))
            .thenReturn("/non/existent/path/to/claude");

        // Falls through to OS-specific candidate search; must not blow up.
        assertDoesNotThrow(ClaudeCodeLocator::findClaudeCodeBinary);
    }

    // ── feature enabled – empty property path ─────────────────────────────────

    @Test
    void findClaudeCodeBinary_whenPropertyPathIsEmpty_doesNotThrow() {
        aiConfigMock
            .when(() -> AiConfig.getProperty(ENABLED_KEY, "false"))
            .thenReturn("true");
        aiConfigMock
            .when(() -> AiConfig.getProperty(PATH_KEY, ""))
            .thenReturn("");

        // No property path – falls through to candidate list; must not blow up.
        assertDoesNotThrow(ClaudeCodeLocator::findClaudeCodeBinary);
    }

    // ── return type contract ───────────────────────────────────────────────────

    @Test
    void findClaudeCodeBinary_whenDisabled_returnTypeIsNull() {
        aiConfigMock
            .when(() -> AiConfig.getProperty(ENABLED_KEY, "false"))
            .thenReturn("false");

        Object result = ClaudeCodeLocator.findClaudeCodeBinary();
        assertNull(
            result,
            "Disabled mode must return null, not an empty string or fallback"
        );
    }
}
