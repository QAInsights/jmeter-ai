package org.qainsights.jmeter.ai.service.reasoning;

import com.google.genai.types.Part;
import com.google.genai.types.ThinkingConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.qainsights.jmeter.ai.utils.AiConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link GoogleThinking}: data-driven shape selection (named
 * levels for Gemini 3, budgets for Gemini 2.5), the Flash off-switch, and
 * thought/answer part routing. Backed by the real vendored models.dev file.
 */
class GoogleThinkingTest {

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
    void noConfigForUnsupportedModels() {
        ReasoningSettings settings = new ReasoningSettings(true, "high");
        assertEquals(Optional.empty(), GoogleThinking.configFor(settings, "gemini-2.0-flash"));
        assertEquals(Optional.empty(), GoogleThinking.configFor(settings, "gemma-3-27b-it"));
        assertEquals(Optional.empty(), GoogleThinking.configFor(settings, null));
    }

    @Test
    void nullSettingsBehaveLikeDefaults() {
        // default ReasoningSettings have thinking off: toggleable Flash gets
        // the off-switch config, nothing is sent for non-toggleable defaults
        ThinkingConfig flash = GoogleThinking.configFor(null, "gemini-2.5-flash").orElseThrow();
        assertEquals(0, flash.thinkingBudget().orElseThrow());
    }

    @Test
    void budgetShapedModelsGetThinkingBudget() {
        // Gemini 2.5 Pro: budget shape, no named levels
        ReasoningSettings settings = new ReasoningSettings(true, "high");
        ThinkingConfig config = GoogleThinking.configFor(settings, "gemini-2.5-pro").orElseThrow();
        assertTrue(config.includeThoughts().orElseThrow());
        assertEquals(24576, config.thinkingBudget().orElseThrow());
    }

    @Test
    void namedLevelModelsGetThinkingLevel() {
        // Gemini 3 Pro: named levels [low, high]
        ReasoningSettings low = new ReasoningSettings(true, "low");
        ThinkingConfig config = GoogleThinking.configFor(low, "gemini-3-pro-preview").orElseThrow();
        assertTrue(config.includeThoughts().orElseThrow());
        assertEquals("low", config.thinkingLevel().map(Object::toString).orElse("").toLowerCase());
    }

    @Test
    void invalidNamedLevelFallsBackToLastValue() {
        // "medium" is not a Gemini 3 level - falls back to "high"
        ReasoningSettings medium = new ReasoningSettings(true, "medium");
        ThinkingConfig config = GoogleThinking.configFor(medium, "gemini-3-pro-preview").orElseThrow();
        assertEquals("high", config.thinkingLevel().map(Object::toString).orElse("").toLowerCase());
    }

    @Test
    void toggleOffDisablesThinkingForFlashOnly() {
        ReasoningSettings settings = new ReasoningSettings(false, "medium");
        // Flash carries the explicit toggle -> budget 0
        ThinkingConfig flash = GoogleThinking.configFor(settings, "gemini-2.5-flash").orElseThrow();
        assertFalse(flash.includeThoughts().orElseThrow());
        assertEquals(0, flash.thinkingBudget().orElseThrow());
        // Pro thinks unconditionally (no toggle in the data) -> the chosen
        // effort's budget still applies
        ThinkingConfig pro = GoogleThinking.configFor(settings, "gemini-2.5-pro").orElseThrow();
        assertEquals(8192, pro.thinkingBudget().orElseThrow());
    }

    @Test
    void routePartSendsThoughtsToReasoningConsumer() {
        List<String> tokens = new ArrayList<>();
        List<String> thoughts = new ArrayList<>();

        GoogleThinking.routePart(Part.builder().text("answer text").build(), tokens::add, thoughts::add);
        GoogleThinking.routePart(Part.builder().text("a thought").thought(true).build(), tokens::add, thoughts::add);
        GoogleThinking.routePart(Part.builder().text("normal text").thought(false).build(), tokens::add, thoughts::add);

        assertEquals(List.of("answer text", "normal text"), tokens);
        assertEquals(List.of("a thought"), thoughts);
    }

    @Test
    void routePartIgnoresEmptyText() {
        List<String> tokens = new ArrayList<>();
        GoogleThinking.routePart(Part.builder().build(), tokens::add, tokens::add);
        assertTrue(tokens.isEmpty());
    }
}
