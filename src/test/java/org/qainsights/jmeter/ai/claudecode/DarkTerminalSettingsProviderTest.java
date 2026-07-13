package org.qainsights.jmeter.ai.claudecode;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentMatchers;

import java.awt.Font;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.qainsights.jmeter.ai.utils.AiConfig;

/**
 * Unit tests for {@link DarkTerminalSettingsProvider}.
 * <p>
 * Verifies that the terminal font size and family are configurable via
 * JMeter properties and that CJK fallback behaves as expected.
 */
class DarkTerminalSettingsProviderTest {

    private static final String CJK_SAMPLE =
            "\u4f60\u597d\u4e16\u754c\u7e41\u9ad4\u4e2d\u6587" +
            "\u3053\u3093\u306b\u3061\u306f\u30c6\u30b9\u30c8" +
            "\uc548\ub155\ud558\uc138\uc694";

    @Test
    void defaultFontSize() {
        withProps(Collections.emptyMap(), () -> {
            assertEquals(16.0f, new DarkTerminalSettingsProvider().getTerminalFontSize(), 0.001f);
        });
    }

    @Test
    void configuredFontSize() {
        withProps(Map.of("jmeter.ai.terminal.font.size", "14.5"), () -> {
            assertEquals(14.5f, new DarkTerminalSettingsProvider().getTerminalFontSize(), 0.001f);
        });
    }

    @Test
    void invalidFontSizeFallsBackToDefault() {
        withProps(Map.of("jmeter.ai.terminal.font.size", "not-a-number"), () -> {
            assertEquals(16.0f, new DarkTerminalSettingsProvider().getTerminalFontSize(), 0.001f);
        });
    }

    @Test
    void defaultFontFallbacksToCjkCapableFont() {
        withProps(Collections.emptyMap(), () -> {
            Font font = new DarkTerminalSettingsProvider().getTerminalFont();
            assertNotNull(font);
            assertCanDisplayCjk(font);
        });
    }

    @Test
    void configuredFontFamilyIsUsedWhenCjkFallbackDisabled() {
        withProps(Map.of(
                "jmeter.ai.terminal.font.family", "WenQuanYi Zen Hei Mono",
                "jmeter.ai.terminal.font.cjk.fallback", "false"), () -> {
            Font font = new DarkTerminalSettingsProvider().getTerminalFont();
            assertEquals("WenQuanYi Zen Hei Mono", font.getFamily());
            assertCanDisplayCjk(font);
        });
    }

    @Test
    void configuredNonCjkFontWithFallbackEnabledSwitchesToCjkFont() {
        withProps(Map.of(
                "jmeter.ai.terminal.font.family", "DejaVu Sans Mono",
                "jmeter.ai.terminal.font.cjk.fallback", "true"), () -> {
            Font font = new DarkTerminalSettingsProvider().getTerminalFont();
            assertCanDisplayCjk(font);
            assertNotEquals("DejaVu Sans Mono", font.getFamily());
        });
    }

    @Test
    void configuredNonCjkFontWithFallbackDisabledIsHonored() {
        withProps(Map.of(
                "jmeter.ai.terminal.font.family", "DejaVu Sans Mono",
                "jmeter.ai.terminal.font.cjk.fallback", "false"), () -> {
            Font font = new DarkTerminalSettingsProvider().getTerminalFont();
            assertEquals("DejaVu Sans Mono", font.getFamily());
        });
    }

    @Test
    void fontNameAliasIsSupported() {
        withProps(Map.of(
                "jmeter.ai.terminal.font", "WenQuanYi Zen Hei Mono",
                "jmeter.ai.terminal.font.cjk.fallback", "false"), () -> {
            Font font = new DarkTerminalSettingsProvider().getTerminalFont();
            assertEquals("WenQuanYi Zen Hei Mono", font.getFamily());
        });
    }

    private void withProps(Map<String, String> props, Runnable test) {
        try (MockedStatic<AiConfig> aiConfigMock = Mockito.mockStatic(AiConfig.class)) {
            aiConfigMock.when(() -> AiConfig.getProperty(any(String.class), ArgumentMatchers.<String>any()))
                    .thenAnswer(invocation -> {
                        String key = invocation.getArgument(0);
                        String defaultValue = invocation.getArgument(1);
                        return props.getOrDefault(key, defaultValue);
                    });
            test.run();
        }
    }

    private static void assertCanDisplayCjk(Font font) {
        assertEquals(-1, font.canDisplayUpTo(CJK_SAMPLE),
                "Font should support CJK characters: " + font.getFamily());
    }
}
