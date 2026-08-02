package org.qainsights.jmeter.ai.service.reasoning;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.qainsights.jmeter.ai.utils.AiConfig;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link ReasoningSettings}: property-backed defaults, effort
 * normalization, and the always-reasoning model detection.
 */
class ReasoningSettingsTest {

    private MockedStatic<AiConfig> aiConfigMockedStatic;

    @BeforeEach
    void setUp() {
        aiConfigMockedStatic = mockStatic(AiConfig.class);
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    @AfterEach
    void tearDown() {
        aiConfigMockedStatic.close();
    }

    @Test
    void testDefaultsThinkingOffMediumEffort() {
        ReasoningSettings settings = new ReasoningSettings();
        assertFalse(settings.isThinkingEnabled());
        assertEquals("medium", settings.getEffort());
    }

    @Test
    void testPropertyBackedDefaults() {
        aiConfigMockedStatic.when(() -> AiConfig.getProperty("jmeter.ai.thinking.enabled", "false"))
                .thenReturn("true");
        aiConfigMockedStatic.when(() -> AiConfig.getProperty("jmeter.ai.thinking.effort", "medium"))
                .thenReturn("HIGH");
        ReasoningSettings settings = new ReasoningSettings();
        assertTrue(settings.isThinkingEnabled());
        assertEquals("high", settings.getEffort());
    }

    @Test
    void testExplicitConstructorNormalizesEffort() {
        ReasoningSettings settings = new ReasoningSettings(true, "  Low ");
        assertTrue(settings.isThinkingEnabled());
        assertEquals("low", settings.getEffort());
    }

    @Test
    void testSetters() {
        ReasoningSettings settings = new ReasoningSettings(false, "low");
        settings.setThinkingEnabled(true);
        settings.setEffort("HIGH");
        assertTrue(settings.isThinkingEnabled());
        assertEquals("high", settings.getEffort());
    }

    @Test
    void testSetEffortIgnoresNullAndBlank() {
        ReasoningSettings settings = new ReasoningSettings(false, "low");
        settings.setEffort(null);
        settings.setEffort("   ");
        assertEquals("low", settings.getEffort());
    }

    @Test
    void testTouchedFlagsStartFalse() {
        ReasoningSettings settings = new ReasoningSettings(true, "high");
        assertFalse(settings.isThinkingToggled());
        assertFalse(settings.isEffortTouched());
        // programmatic setters must not mark the flags
        settings.setThinkingEnabled(false);
        settings.setEffort("low");
        assertFalse(settings.isThinkingToggled());
        assertFalse(settings.isEffortTouched());
    }

    @Test
    void testUserSettersMarkTouched() {
        ReasoningSettings settings = new ReasoningSettings(false, "medium");
        settings.userSetThinkingEnabled(true);
        settings.userSetEffort("high");
        assertTrue(settings.isThinkingToggled());
        assertTrue(settings.isEffortTouched());
        assertTrue(settings.isThinkingEnabled());
        assertEquals("high", settings.getEffort());
    }
}
