package org.qainsights.jmeter.ai.agent.claude;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for the extended-thinking support in {@link ClaudeChatModel}. */
class ClaudeChatModelThinkingTest {

    private static Message textMessage(String text) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", "m_1");
        json.put("type", "message");
        json.put("role", "assistant");
        json.put("model", "claude");
        json.put("stop_reason", "end_turn");
        json.put("usage", Map.of("input_tokens", 0, "output_tokens", 0));
        json.put("content", List.of(Map.of("type", "text", "text", text)));
        return JsonValue.from(json).convert(Message.class);
    }

    private static ClaudeChatModel.MessageService capturing(List<MessageCreateParams> captured) {
        Deque<Message> responses = new ArrayDeque<>();
        responses.add(textMessage("done"));
        return params -> {
            captured.add(params);
            return responses.removeFirst();
        };
    }

    @Test
    void thinkingBudgetSendsThinkingConfigAndBumpsMaxTokens() {
        List<MessageCreateParams> captured = new ArrayList<>();
        ClaudeChatModel model = new ClaudeChatModel(capturing(captured), new ClaudeToolAdapter(),
                Collections.<ToolSpec>emptyList(), "system", "claude-sonnet-4-6", 1024,
                Collections.emptyList(), 8192L);

        model.start("inspect the plan");

        MessageCreateParams params = captured.get(0);
        assertTrue(params.thinking().isPresent(), "thinking config must be sent");
        assertEquals(8192, params.thinking().get().enabled().orElseThrow().budgetTokens());
        // max_tokens must exceed the budget (1024 configured -> bumped)
        assertEquals(8192 + 1024, params.maxTokens());
    }

    @Test
    void thinkingBudgetKeepsConfiguredMaxTokensWhenAlreadyLarger() {
        List<MessageCreateParams> captured = new ArrayList<>();
        ClaudeChatModel model = new ClaudeChatModel(capturing(captured), new ClaudeToolAdapter(),
                Collections.<ToolSpec>emptyList(), "system", "claude-sonnet-4-6", 20000,
                Collections.emptyList(), 2048L);

        model.start("inspect the plan");

        assertEquals(20000, captured.get(0).maxTokens());
    }

    @Test
    void adaptiveModelSendsAdaptiveConfigWithoutBudget() {
        List<MessageCreateParams> captured = new ArrayList<>();
        ClaudeChatModel model = new ClaudeChatModel(capturing(captured), new ClaudeToolAdapter(),
                Collections.<ToolSpec>emptyList(), "system", "claude-fable-5", 1024,
                Collections.emptyList(), 1L);

        model.start("inspect the plan");

        MessageCreateParams params = captured.get(0);
        assertTrue(params.thinking().isPresent(), "thinking config must be sent");
        assertTrue(params.thinking().get().adaptive().isPresent(),
                "fable must get the adaptive thinking config, not a budget");
        // adaptive thinking needs no max_tokens bump
        assertEquals(1024, params.maxTokens());
    }

    @Test
    void nullBudgetSendsNoThinking() {
        List<MessageCreateParams> captured = new ArrayList<>();
        ClaudeChatModel model = new ClaudeChatModel(capturing(captured), new ClaudeToolAdapter(),
                Collections.<ToolSpec>emptyList(), "system", "claude-sonnet-4-6", 1024,
                Collections.emptyList(), null);

        model.start("inspect the plan");

        assertTrue(captured.get(0).thinking().isEmpty());
        assertEquals(1024, captured.get(0).maxTokens());
        assertNull(model.getThinkingBudget());
    }

    @Test
    void getThinkingBudgetReturnsConfiguredBudget() {
        ClaudeChatModel model = new ClaudeChatModel(params -> textMessage("x"), new ClaudeToolAdapter(),
                Collections.<ToolSpec>emptyList(), "system", "claude-sonnet-4-6", 1024,
                Collections.emptyList(), 4096L);
        assertEquals(4096L, model.getThinkingBudget());
    }

    @Test
    void jMeterAgentFactoryMarksAdaptiveForFable() {
        org.mockito.MockedStatic<org.qainsights.jmeter.ai.utils.AiConfig> mocked =
                org.mockito.Mockito.mockStatic(org.qainsights.jmeter.ai.utils.AiConfig.class);
        try {
            mocked.when(() -> org.qainsights.jmeter.ai.utils.AiConfig.getProperty(
                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                    .thenAnswer(invocation -> invocation.getArgument(1));

            ClaudeChatModel chatModel = (ClaudeChatModel) org.qainsights.jmeter.ai.agent.JMeterAgent
                    .claudeFactory(params -> textMessage("x"), "claude-fable-5", 4096,
                            new org.qainsights.jmeter.ai.service.reasoning.ReasoningSettings(true, "medium"))
                    .create(Collections.emptyList(), "system", Collections.emptyList());

            assertEquals(1L, chatModel.getThinkingBudget(),
                    "adaptive models must get the marker budget, not a token budget");
        } finally {
            mocked.close();
        }
    }

    @Test
    void jMeterAgentFactoryUsesRealBudgetForBudgetModels() {
        org.mockito.MockedStatic<org.qainsights.jmeter.ai.utils.AiConfig> mocked =
                org.mockito.Mockito.mockStatic(org.qainsights.jmeter.ai.utils.AiConfig.class);
        try {
            mocked.when(() -> org.qainsights.jmeter.ai.utils.AiConfig.getProperty(
                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                    .thenAnswer(invocation -> invocation.getArgument(1));

            ClaudeChatModel chatModel = (ClaudeChatModel) org.qainsights.jmeter.ai.agent.JMeterAgent
                    .claudeFactory(params -> textMessage("x"), "claude-opus-4-8", 4096,
                            new org.qainsights.jmeter.ai.service.reasoning.ReasoningSettings(true, "high"))
                    .create(Collections.emptyList(), "system", Collections.emptyList());

            assertEquals(16384L, chatModel.getThinkingBudget());
        } finally {
            mocked.close();
        }
    }
}
