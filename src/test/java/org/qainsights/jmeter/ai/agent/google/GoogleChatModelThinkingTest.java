package org.qainsights.jmeter.ai.agent.google;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;
import org.qainsights.jmeter.ai.service.reasoning.ReasoningSettings;
import org.qainsights.jmeter.ai.utils.AiConfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for the reasoning wiring in {@link GoogleChatModel}, backed by the
 * real vendored models.dev capability data (mirrors {@code GoogleThinkingTest}).
 */
class GoogleChatModelThinkingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

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

    private static GenerateContentResponse textResponse(String text) throws Exception {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("role", "model");
        content.put("parts", Collections.singletonList(Collections.singletonMap("text", text)));
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("content", content);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("candidates", Collections.singletonList(candidate));
        return GenerateContentResponse.fromJson(JSON.writeValueAsString(json));
    }

    private static GoogleChatModel model(String modelId, ReasoningSettings settings,
                                         List<GenerateContentConfig> captured) {
        GoogleChatModel.GenerateService service = (model, contents, config) -> {
            captured.add(config);
            try {
                return textResponse("done");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        return new GoogleChatModel(service, new GoogleToolAdapter(),
                Collections.<ToolSpec>emptyList(), "system", modelId, 4096,
                Collections.emptyList(), settings);
    }

    @Test
    void budgetShapedModelSendsThinkingBudget() {
        List<GenerateContentConfig> captured = new ArrayList<>();
        model("gemini-2.5-pro", new ReasoningSettings(true, "high"), captured).start("hi");

        assertEquals(24576, captured.get(0).thinkingConfig().orElseThrow().thinkingBudget().orElseThrow());
    }

    @Test
    void namedLevelModelSendsThinkingLevel() {
        List<GenerateContentConfig> captured = new ArrayList<>();
        model("gemini-3-pro-preview", new ReasoningSettings(true, "low"), captured).start("hi");

        assertEquals("low", captured.get(0).thinkingConfig().orElseThrow()
                .thinkingLevel().map(Object::toString).orElse("").toLowerCase());
    }

    @Test
    void nonReasoningModelSendsNoThinkingConfig() {
        List<GenerateContentConfig> captured = new ArrayList<>();
        model("gemini-2.0-flash", new ReasoningSettings(true, "high"), captured).start("hi");

        assertTrue(captured.get(0).thinkingConfig().isEmpty());
    }
}
