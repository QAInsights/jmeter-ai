package org.qainsights.jmeter.ai.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.qainsights.jmeter.ai.agent.claude.ClaudeChatModel;
import org.qainsights.jmeter.ai.agent.google.GoogleChatModel;
import org.qainsights.jmeter.ai.agent.loop.ChatModel;
import org.qainsights.jmeter.ai.service.reasoning.ReasoningSettings;
import org.qainsights.jmeter.ai.utils.AiConfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ChatModel createModel(String model, ReasoningSettings settings) {
        return JMeterAgent.claudeFactory(params -> null, model, 4096, settings)
                .create(Collections.emptyList(), "system", Collections.emptyList());
    }

    private static GenerateContentResponse textResponse(String text) {
        try {
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("role", "model");
            content.put("parts", Collections.singletonList(Collections.singletonMap("text", text)));
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("content", content);
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("candidates", Collections.singletonList(candidate));
            return GenerateContentResponse.fromJson(JSON.writeValueAsString(json));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static ChatModel createGoogleModel(String model, ReasoningSettings settings,
                                                List<GenerateContentConfig> captured) {
        GoogleChatModel.GenerateService service = (m, contents, config) -> {
            captured.add(config);
            return textResponse("done");
        };
        return JMeterAgent.googleFactory(service, model, 4096, settings)
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

    @Test
    void googleFactory_propertyOverrideWinsOverToolbarEffort() {
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(
                JMeterAgent.THINKING_EFFORT_KEY, "")).thenReturn("high");
        ReasoningSettings settings = new ReasoningSettings(true, "low");
        List<GenerateContentConfig> captured = new ArrayList<>();

        createGoogleModel("gemini-2.5-pro", settings, captured).start("hi");

        // property (high -> 24576) beats the toolbar's low (1024)
        assertEquals(24576, captured.get(0).thinkingConfig().orElseThrow().thinkingBudget().orElseThrow());
    }

    @Test
    void googleFactory_toolbarEffortUsedWhenPropertyUnset() {
        ReasoningSettings settings = new ReasoningSettings(true, "low");
        List<GenerateContentConfig> captured = new ArrayList<>();

        createGoogleModel("gemini-2.5-pro", settings, captured).start("hi");

        assertEquals(1024, captured.get(0).thinkingConfig().orElseThrow().thinkingBudget().orElseThrow());
    }
}
