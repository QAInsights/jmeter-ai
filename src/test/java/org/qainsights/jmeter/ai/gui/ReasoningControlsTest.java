package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.qainsights.jmeter.ai.service.reasoning.ReasoningSettings;
import org.qainsights.jmeter.ai.utils.AiConfig;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link ReasoningControls}: capability-driven visibility,
 * effort repopulation, and writing user choices into {@link ReasoningSettings}.
 */
class ReasoningControlsTest {

    private MockedStatic<AiConfig> aiConfigMockedStatic;
    private ReasoningSettings settings;
    private ReasoningControls controls;

    @BeforeEach
    void setUp() {
        aiConfigMockedStatic = mockStatic(AiConfig.class);
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        settings = new ReasoningSettings();
        controls = new ReasoningControls(settings);
    }

    @AfterEach
    void tearDown() {
        aiConfigMockedStatic.close();
    }

    @Test
    void hiddenByDefaultBeforeModelKnown() {
        assertFalse(controls.isVisible());
    }

    @Test
    void modelWithNoReasoningHidesEverything() {
        controls.updateForModel("openai:gpt-4o");
        assertFalse(controls.isVisible());
        assertFalse(controls.getThinkingToggle().isVisible());
        assertFalse(controls.getEffortCombo().isVisible());
    }

    @Test
    void toggleableModelShowsToggleAndEffort() {
        controls.updateForModel("claude-sonnet-4-6");
        assertTrue(controls.isVisible());
        assertTrue(controls.getThinkingToggle().isVisible());
        assertTrue(controls.getEffortCombo().isVisible());
        // effort values come straight from the vendored catalog
        assertEquals(4, controls.getEffortCombo().getItemCount());
        assertEquals("medium", controls.getEffortCombo().getSelectedItem());
    }

    @Test
    void alwaysReasoningModelShowsEffortOnly() {
        controls.updateForModel("openai:o3");
        assertTrue(controls.isVisible());
        assertFalse(controls.getThinkingToggle().isVisible());
        assertTrue(controls.getEffortCombo().isVisible());
        assertEquals(3, controls.getEffortCombo().getItemCount());
    }

    @Test
    void gpt5xShowsToggleWithNoneLevel() {
        controls.updateForModel("openai:gpt-5.1");
        assertTrue(controls.getThinkingToggle().isVisible());
        assertEquals("none", controls.getEffortCombo().getItemAt(0));
        // default selection skips "none" and lands on medium
        assertEquals("medium", controls.getEffortCombo().getSelectedItem());
    }

    @Test
    void twoLevelModelFallsBackToLastLevelWhenMediumMissing() {
        // gemini-3-pro-preview has named levels [low, high] - no medium
        controls.updateForModel("google:gemini-3-pro-preview");
        assertEquals(2, controls.getEffortCombo().getItemCount());
        assertEquals("high", controls.getEffortCombo().getSelectedItem());
        assertEquals("high", settings.getEffort());
    }

    @Test
    void toggleWritesIntoSettings() {
        controls.updateForModel("claude-sonnet-4-6");
        // doClick() simulates a real user press (setSelected alone only fires ItemEvents)
        controls.getThinkingToggle().doClick();
        assertTrue(settings.isThinkingEnabled());
        controls.getThinkingToggle().doClick();
        assertFalse(settings.isThinkingEnabled());
    }

    @Test
    void effortSelectionWritesIntoSettings() {
        controls.updateForModel("claude-sonnet-4-6");
        controls.getEffortCombo().setSelectedItem("high");
        assertEquals("high", settings.getEffort());
    }

    @Test
    void validEffortChoiceSurvivesModelSwitch() {
        settings.setEffort("high");
        controls.updateForModel("claude-sonnet-4-6");
        assertEquals("high", controls.getEffortCombo().getSelectedItem());
        controls.updateForModel("google:gemini-2.5-flash");
        assertEquals("high", controls.getEffortCombo().getSelectedItem());
    }

    @Test
    void nullModelHidesEverything() {
        controls.updateForModel(null);
        assertFalse(controls.isVisible());
    }

    @Test
    void ollamaPropertyDefaultsShownWhenUntouched() {
        aiConfigMockedStatic.when(() -> AiConfig.getProperty("ollama.thinking.mode", "DISABLED"))
                .thenReturn("ENABLED");
        aiConfigMockedStatic.when(() -> AiConfig.getProperty("ollama.thinking.level", "medium"))
                .thenReturn("HIGH");

        controls.updateForModel("ollama:qwen3:8b");

        assertTrue(controls.getThinkingToggle().isSelected(),
                "ollama.thinking.mode=ENABLED must preselect the toggle");
        assertEquals("high", controls.getEffortCombo().getSelectedItem(),
                "ollama.thinking.level must preselect the effort");
    }

    @Test
    void userChoiceBeatsOllamaPropertyDefault() {
        aiConfigMockedStatic.when(() -> AiConfig.getProperty("ollama.thinking.mode", "DISABLED"))
                .thenReturn("ENABLED");

        settings.userSetThinkingEnabled(false);
        settings.userSetEffort("low");
        controls.updateForModel("ollama:qwen3:8b");

        assertFalse(controls.getThinkingToggle().isSelected(),
                "a deliberate user toggle-off must win over the property default");
        assertEquals("low", controls.getEffortCombo().getSelectedItem());
    }

    @Test
    void toggleClickMarksThinkingToggled() {
        controls.updateForModel("claude-sonnet-4-6");
        assertFalse(settings.isThinkingToggled());
        controls.getThinkingToggle().doClick();
        assertTrue(settings.isThinkingToggled());
    }

    @Test
    void fableShowsToggleAndFullEffortRange() {
        // fable is adaptive-thinking with the full effort range (low..max)
        controls.updateForModel("claude-fable-5");
        assertTrue(controls.isVisible());
        assertTrue(controls.getThinkingToggle().isVisible());
        assertTrue(controls.getEffortCombo().isVisible());
        assertEquals(5, controls.getEffortCombo().getItemCount());
    }

    @Test
    void nonToggleableReasoningModelShowsEffortOnly() {
        // gemini-2.5-pro always thinks (no toggle in the data) - budget dropdown only
        controls.updateForModel("google:gemini-2.5-pro");
        assertTrue(controls.isVisible());
        assertFalse(controls.getThinkingToggle().isVisible());
        assertTrue(controls.getEffortCombo().isVisible());
    }
}
