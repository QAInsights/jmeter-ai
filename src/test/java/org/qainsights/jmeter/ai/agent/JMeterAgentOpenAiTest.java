package org.qainsights.jmeter.ai.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.loop.AgentLoop;
import org.qainsights.jmeter.ai.agent.openai.OpenAiChatModel;
import org.qainsights.jmeter.ai.agent.tool.ToolConfirmationGate;

import com.openai.core.JsonValue;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mirror of {@link JMeterAgentTest} for the OpenAI provider: the same agent
 * façade, tool registry and loop, driven by a fake completion service instead of
 * the Anthropic one - proving the tools are provider-agnostic.
 */
class JMeterAgentOpenAiTest {

    @BeforeEach
    void resetUndoNudge() {
        JMeterAgent.resetUndoNudgeForTests();
    }

    private static ChatCompletion completion(Map<String, Object> message) {
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

    private static ChatCompletion textCompletion(String text) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", text);
        return completion(message);
    }

    private static ChatCompletion toolCompletion(String id, String name, String arguments) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("arguments", arguments);
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("id", id);
        call.put("type", "function");
        call.put("function", function);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", null);
        message.put("tool_calls", Collections.singletonList(call));
        return completion(message);
    }

    private static JMeterAgent agent(OpenAiChatModel.CompletionService service, ToolConfirmationGate gate) {
        return new JMeterAgent(JMeterAgent.openAiFactory(service, "gpt-4o", 1024), 5, gate);
    }

    @Test
    void run_completesWithFinalTextWhenNoToolsRequested() {
        OpenAiChatModel.CompletionService service = params -> textCompletion("I can help with that.");

        AgentLoop.AgentResult result = agent(service, null).run("hello", null);

        assertTrue(result.isCompleted());
        assertEquals("I can help with that.", result.getFinalText());
        assertEquals(1, result.getIterations());
    }

    @Test
    void run_advertisesTheFullToolRegistryToOpenAi() {
        List<ChatCompletionCreateParams> captured = new ArrayList<>();
        OpenAiChatModel.CompletionService service = params -> {
            captured.add(params);
            return textCompletion("ok");
        };

        agent(service, null).run("hello", null);

        List<String> toolNames = new ArrayList<>();
        for (com.openai.models.chat.completions.ChatCompletionTool tool
                : captured.get(0).tools().orElse(Collections.emptyList())) {
            toolNames.add(tool.asFunction().function().name());
        }
        assertTrue(toolNames.contains("get_tree_state"), "expected read tools, got " + toolNames);
        assertTrue(toolNames.contains("add_element"), "expected write tools, got " + toolNames);
        assertTrue(toolNames.contains("delete_element"), "expected destructive tools, got " + toolNames);
    }

    @Test
    void run_withPriorConversation_seedsHistoryBeforeTheNewMessage() {
        List<ChatCompletionCreateParams> captured = new ArrayList<>();
        OpenAiChatModel.CompletionService service = params -> {
            captured.add(params);
            return textCompletion("Sure, added it.");
        };

        List<String> prior = Arrays.asList("add a thread group", "Added a Thread Group.");
        AgentLoop.AgentResult result = agent(service, null).run("now add an http sampler", prior, null);

        assertTrue(result.isCompleted());
        // system + seed (2) + the new user message (1) = 4.
        assertEquals(4, captured.get(0).messages().size());
    }

    @Test
    void run_withOddPriorConversation_dropsTrailingUnpairedTurn() {
        List<ChatCompletionCreateParams> captured = new ArrayList<>();
        OpenAiChatModel.CompletionService service = params -> {
            captured.add(params);
            return textCompletion("ok");
        };

        agent(service, null).run("now add an http sampler",
                Collections.singletonList("add a thread group"), null);

        // system + the new user message only.
        assertEquals(2, captured.get(0).messages().size());
    }

    @Test
    void run_withNullPriorConversation_behavesLikeNoHistory() {
        List<ChatCompletionCreateParams> captured = new ArrayList<>();
        OpenAiChatModel.CompletionService service = params -> {
            captured.add(params);
            return textCompletion("ok");
        };

        agent(service, null).run("hello", null, null);

        assertEquals(2, captured.get(0).messages().size());
    }

    @Test
    void run_declinedDestructiveTool_neverReachesTheHandler() {
        Deque<ChatCompletion> responses = new ArrayDeque<>();
        responses.add(toolCompletion("call_1", "delete_element",
                "{\"element_id\":\"Test Plan/Thread Group\"}"));
        responses.add(textCompletion("Okay, I will not delete it."));
        OpenAiChatModel.CompletionService service = params -> responses.removeFirst();

        List<String> progressLines = new ArrayList<>();
        AgentLoop.AgentResult result = agent(service, (toolName, args) -> false)
                .run("delete the thread group", null, progressLines::add);

        assertTrue(result.isCompleted());
        assertEquals("Okay, I will not delete it.", result.getFinalText());
        assertTrue(progressLines.stream().anyMatch(l -> l.contains("declined")));
    }

    @Test
    void run_toolCallResult_isFedBackAsAToolMessage() {
        Deque<ChatCompletion> responses = new ArrayDeque<>();
        responses.add(toolCompletion("call_1", "get_tree_state", "{}"));
        responses.add(textCompletion("Here's the tree."));
        List<ChatCompletionCreateParams> captured = new ArrayList<>();
        OpenAiChatModel.CompletionService service = params -> {
            captured.add(params);
            return responses.removeFirst();
        };

        AgentLoop.AgentResult result = agent(service, null).run("show me the tree", null);

        assertTrue(result.isCompleted());
        assertEquals(2, result.getIterations());
        List<com.openai.models.chat.completions.ChatCompletionMessageParam> second = captured.get(1).messages();
        assertTrue(second.get(second.size() - 1).isTool());
    }
}
