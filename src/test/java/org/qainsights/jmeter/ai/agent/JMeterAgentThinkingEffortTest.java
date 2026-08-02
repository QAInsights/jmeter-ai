package org.qainsights.jmeter.ai.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.qainsights.jmeter.ai.agent.claude.ClaudeChatModel;
import org.qainsights.jmeter.ai.agent.loop.ChatModel;
import org.qainsights.jmeter.ai.service.reasoning.ReasoningSettings;
import org.qainsights.jmeter.ai.utils.AiConfig;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for the agent-only thinking effort override
 * ({@code jmeter.ai.agent.thinking.effort}) in {@link JMeterAgent}.
 */
class JMeterAgentThinkingEffortTest {

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

    private static ChatModel createModel(String model, ReasoningSettings settings) {
        return JMeterAgent.claudeFactory(params -> null, model, 4096, settings)
                .create(Collections.emptyList(), "system", Collections.emptyList());
    }

    @Test
    void propertyOverrideWinsOverToolbarEffort() {
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(
                JMeterAgent.THINKING_EFFORT_KEY, "")).thenReturn("high");
        ReasoningSettings settings = new ReasoningSettings(true, "medium");

        ClaudeChatModel chat = (ClaudeChatModel) createModel("claude-opus-4-8", settings);

        // property (high -> 16384) beats the toolbar's medium (8192)
        assertEquals(16384L, chat.getThinkingBudget());
        assertEquals("high", JMeterAgent.effectiveAgentEffort(settings));
    }

    @Test
    void toolbarEffortUsedWhenPropertyUnset() {
        ReasoningSettings settings = new ReasoningSettings(true, "low");

        ClaudeChatModel chat = (ClaudeChatModel) createModel("claude-opus-4-8", settings);

        assertEquals(2048L, chat.getThinkingBudget());
        assertEquals("low", JMeterAgent.effectiveAgentEffort(settings));
    }

    @Test
    void effectiveAgentEffortDefaultsToMediumWithoutSettings() {
        assertEquals("medium", JMeterAgent.effectiveAgentEffort(null));
    }

    @Test
    void noThinkingForAdaptiveMarkerUsesPropertyEffort() {
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(
                JMeterAgent.THINKING_EFFORT_KEY, "")).thenReturn("XHIGH");
        // fable gets the adaptive marker budget; the effort string flows to the model
        assertEquals("xhigh", JMeterAgent.effectiveAgentEffort(
                new ReasoningSettings(true, "medium")));
        ClaudeChatModel chat = (ClaudeChatModel) createModel("claude-fable-5",
                new ReasoningSettings(true, "medium"));
        assertEquals(1L, chat.getThinkingBudget());
    }
}
