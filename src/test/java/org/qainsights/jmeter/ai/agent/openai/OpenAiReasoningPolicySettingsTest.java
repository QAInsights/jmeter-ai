package org.qainsights.jmeter.ai.agent.openai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.qainsights.jmeter.ai.service.reasoning.ReasoningSettings;
import org.qainsights.jmeter.ai.utils.AiConfig;

import com.openai.models.ReasoningEffort;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for the settings-aware
 * {@link OpenAiReasoningPolicy#forToolCalling(String, ReasoningSettings)}:
 * the gpt-5.x restriction wins over user choices, unrestricted models honor them.
 */
class OpenAiReasoningPolicySettingsTest {

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
    void gpt5xForcesNoneEvenWhenUserPickedEffort() {
        ReasoningSettings settings = new ReasoningSettings(true, "high");
        assertEquals(Optional.of(ReasoningEffort.NONE),
                OpenAiReasoningPolicy.forToolCalling("gpt-5.1", settings));
    }

    @Test
    void oSeriesHonorsUserEffort() {
        assertEquals(Optional.of(ReasoningEffort.LOW),
                OpenAiReasoningPolicy.forToolCalling("o3", new ReasoningSettings(false, "low")));
        assertEquals(Optional.of(ReasoningEffort.HIGH),
                OpenAiReasoningPolicy.forToolCalling("o4-mini", new ReasoningSettings(false, "high")));
    }

    @Test
    void nonDottedGpt5HonorsUserEffort() {
        assertEquals(Optional.of(ReasoningEffort.MINIMAL),
                OpenAiReasoningPolicy.forToolCalling("gpt-5", new ReasoningSettings(false, "minimal")));
    }

    @Test
    void nonReasoningModelsSendNothing() {
        ReasoningSettings settings = new ReasoningSettings(true, "high");
        assertEquals(Optional.empty(), OpenAiReasoningPolicy.forToolCalling("gpt-4o", settings));
        assertEquals(Optional.empty(), OpenAiReasoningPolicy.forToolCalling(null, settings));
    }

    @Test
    void nullSettingsUseDefaults() {
        assertEquals(Optional.of(ReasoningEffort.MEDIUM),
                OpenAiReasoningPolicy.forToolCalling("o3", null));
    }
}
