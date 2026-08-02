package org.qainsights.jmeter.ai.agent.openai;

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

import com.openai.core.JsonValue;
import com.openai.models.ReasoningEffort;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

/** Unit tests for the reasoning-effort wiring in {@link OpenAiChatModel}. */
class OpenAiChatModelThinkingTest {

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

    private static ChatCompletion textCompletion(String text) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", text);
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("finish_reason", "stop");
        choice.put("message", message);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", "chatcmpl_1");
        json.put("object", "chat.completion");
        json.put("created", 0);
        json.put("model", "gpt-4o");
        json.put("choices", Collections.singletonList(choice));
        return JsonValue.from(json).convert(ChatCompletion.class);
    }

    private static OpenAiChatModel model(String modelId, ReasoningSettings settings,
                                         List<ChatCompletionCreateParams> captured) {
        OpenAiChatModel.CompletionService service = params -> {
            captured.add(params);
            return textCompletion("done");
        };
        return new OpenAiChatModel(service, new OpenAiToolAdapter(),
                Collections.<ToolSpec>emptyList(), "system", modelId, 4096,
                Collections.emptyList(), settings);
    }

    @Test
    void oSeriesSendsUserEffort() {
        List<ChatCompletionCreateParams> captured = new ArrayList<>();
        model("o3", new ReasoningSettings(false, "low"), captured).start("hi");

        assertEquals(ReasoningEffort.LOW, captured.get(0).reasoningEffort().orElseThrow());
    }

    @Test
    void gpt5xForcesNoneRegardlessOfSettings() {
        List<ChatCompletionCreateParams> captured = new ArrayList<>();
        model("gpt-5.1", new ReasoningSettings(true, "high"), captured).start("hi");

        assertEquals(ReasoningEffort.NONE, captured.get(0).reasoningEffort().orElseThrow());
    }

    @Test
    void nonReasoningModelSendsNoEffort() {
        List<ChatCompletionCreateParams> captured = new ArrayList<>();
        model("gpt-4o", new ReasoningSettings(true, "high"), captured).start("hi");

        assertTrue(captured.get(0).reasoningEffort().isEmpty());
    }
}
