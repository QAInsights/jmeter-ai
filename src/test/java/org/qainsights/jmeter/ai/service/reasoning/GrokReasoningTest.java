package org.qainsights.jmeter.ai.service.reasoning;

import com.openai.models.ReasoningEffort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.qainsights.jmeter.ai.utils.AiConfig;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link GrokReasoning}: effort levels come from the vendored
 * models.dev data (grok-4.5: low/medium/high); models without catalog data or
 * without effort options send nothing.
 */
class GrokReasoningTest {

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
    void noEffortForModelsWithoutEffortSupport() {
        ReasoningSettings settings = new ReasoningSettings(false, "low");
        // grok-4 is absent from the vendored data; the reasoning variant of
        // grok-4.20 has reasoning but no effort options
        assertEquals(Optional.empty(), GrokReasoning.effortFor(settings, "grok-4"));
        assertEquals(Optional.empty(), GrokReasoning.effortFor(settings, "grok-4.20-0309-reasoning"));
        assertEquals(Optional.empty(), GrokReasoning.effortFor(settings, null));
    }

    @Test
    void grok45HonorsChosenEffort() {
        // xAI docs: grok-4.5 takes reasoning_effort low/medium/high, cannot disable
        assertEquals(Optional.of(ReasoningEffort.LOW),
                GrokReasoning.effortFor(new ReasoningSettings(false, "low"), "grok-4.5"));
        assertEquals(Optional.of(ReasoningEffort.MEDIUM),
                GrokReasoning.effortFor(new ReasoningSettings(false, "medium"), "grok-4.5"));
        assertEquals(Optional.of(ReasoningEffort.HIGH),
                GrokReasoning.effortFor(new ReasoningSettings(false, "high"), "grok-4.5"));
    }

    @Test
    void unsupportedLevelFallsBackToHigh() {
        assertEquals(Optional.of(ReasoningEffort.HIGH),
                GrokReasoning.effortFor(new ReasoningSettings(false, "extreme"), "grok-4.5"));
        assertEquals(Optional.of(ReasoningEffort.HIGH),
                GrokReasoning.effortFor(null, "grok-4.5"));
    }
}
