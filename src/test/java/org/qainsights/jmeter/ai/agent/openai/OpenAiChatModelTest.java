package org.qainsights.jmeter.ai.agent.openai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.loop.AssistantTurn;
import org.qainsights.jmeter.ai.agent.loop.ToolOutcome;
import org.qainsights.jmeter.ai.agent.tool.ParamType;
import org.qainsights.jmeter.ai.agent.tool.ToolParameter;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;

import com.openai.core.JsonValue;
import com.openai.models.ReasoningEffort;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link OpenAiChatModel} using a fake {@link OpenAiChatModel.CompletionService}. */
class OpenAiChatModelTest {

    /** Builds a real ChatCompletion from its JSON shape, bypassing strict builders. */
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

    private static ChatCompletion toolCompletion(String id, String name) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("arguments", "{}");
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

    private static ChatCompletion emptyChoicesCompletion() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", "chatcmpl_1");
        json.put("object", "chat.completion");
        json.put("created", 0);
        json.put("model", "gpt-4o");
        json.put("choices", Collections.emptyList());
        return JsonValue.from(json).convert(ChatCompletion.class);
    }

    private static OpenAiChatModel model(OpenAiChatModel.CompletionService service) {
        return new OpenAiChatModel(service, new OpenAiToolAdapter(),
                Collections.<ToolSpec>emptyList(), "system", "gpt-4o", 1024);
    }

    @Test
    void start_sendsUserMessageAndParsesToolCall() {
        List<ChatCompletionCreateParams> captured = new ArrayList<>();
        OpenAiChatModel.CompletionService service = params -> {
            captured.add(params);
            return toolCompletion("call_1", "get_tree_state");
        };

        AssistantTurn turn = model(service).start("inspect the plan");

        assertTrue(turn.hasToolCalls());
        assertEquals("get_tree_state", turn.getToolCalls().get(0).getName());
        // system prompt + the user message.
        assertEquals(2, captured.get(0).messages().size());
        assertTrue(captured.get(0).messages().get(0).isSystem());
        assertTrue(captured.get(0).messages().get(1).isUser());
    }

    @Test
    void next_appendsToolResultsAndGrowsHistory() {
        Deque<ChatCompletion> responses = new ArrayDeque<>();
        responses.add(toolCompletion("call_1", "get_tree_state"));
        responses.add(textCompletion("All set."));
        List<ChatCompletionCreateParams> captured = new ArrayList<>();
        OpenAiChatModel.CompletionService service = params -> {
            captured.add(params);
            return responses.removeFirst();
        };

        OpenAiChatModel chat = model(service);
        chat.start("inspect");
        AssistantTurn turn = chat.next(Collections.singletonList(
                new ToolOutcome("call_1", "get_tree_state", "tree", false)));

        assertFalse(turn.hasToolCalls());
        assertEquals("All set.", turn.getText());
        // 2nd call: system + user + assistant(tool_calls) + tool result = 4.
        assertEquals(4, captured.get(1).messages().size());
        assertTrue(captured.get(1).messages().get(2).isAssistant());
        assertTrue(captured.get(1).messages().get(3).isTool());
    }

    @Test
    void send_advertisesEveryToolSpec() {
        List<ChatCompletionCreateParams> captured = new ArrayList<>();
        OpenAiChatModel.CompletionService service = params -> {
            captured.add(params);
            return textCompletion("ok");
        };
        List<ToolSpec> specs = Arrays.asList(
                ToolSpec.builder("get_tree_state").description("Reads the tree").build(),
                ToolSpec.builder("add_element").description("Adds an element")
                        .addParameter(ToolParameter.builder("parent_id", ParamType.STRING).required(true).build())
                        .build());

        new OpenAiChatModel(service, new OpenAiToolAdapter(), specs, "system", "gpt-4o", 1024).start("hi");

        List<com.openai.models.chat.completions.ChatCompletionTool> tools =
                captured.get(0).tools().orElse(Collections.emptyList());
        assertEquals(2, tools.size());
        assertEquals("get_tree_state", tools.get(0).asFunction().function().name());
        assertEquals("add_element", tools.get(1).asFunction().function().name());
    }

    @Test
    void send_setsModelAndMaxCompletionTokensButNotTemperature() {
        List<ChatCompletionCreateParams> captured = new ArrayList<>();
        OpenAiChatModel.CompletionService service = params -> {
            captured.add(params);
            return textCompletion("ok");
        };

        model(service).start("hi");

        assertEquals("gpt-4o", captured.get(0).model().asString());
        assertEquals(1024L, captured.get(0).maxCompletionTokens().orElse(0L));
        // Reasoning models (o1/o3/o4/gpt-5) reject a custom temperature, so it is never set.
        assertFalse(captured.get(0).temperature().isPresent());
        assertFalse(captured.get(0).reasoningEffort().isPresent());
    }

    @Test
    void send_gpt5DottedMinorModel_disablesReasoningSoFunctionToolsAreAccepted() {
        List<ChatCompletionCreateParams> captured = new ArrayList<>();
        OpenAiChatModel.CompletionService service = params -> {
            captured.add(params);
            return textCompletion("ok");
        };

        new OpenAiChatModel(service, new OpenAiToolAdapter(), Collections.<ToolSpec>emptyList(),
                "system", "gpt-5.6-terra", 1024).start("hi");

        assertEquals(ReasoningEffort.NONE, captured.get(0).reasoningEffort().orElse(null),
                "gpt-5.1+ rejects function tools unless reasoning_effort is 'none'");
    }

    @Test
    void constructor_withSeedHistory_prependsItToTheFirstRequest() {
        List<ChatCompletionCreateParams> captured = new ArrayList<>();
        OpenAiChatModel.CompletionService service = params -> {
            captured.add(params);
            return textCompletion("ok");
        };
        List<ChatCompletionMessageParam> seed =
                OpenAiChatModel.toSeedHistory(Arrays.asList("earlier question", "earlier answer"));

        new OpenAiChatModel(service, new OpenAiToolAdapter(), Collections.<ToolSpec>emptyList(),
                "system", "gpt-4o", 1024, seed).start("follow up");

        // system + seed (2) + the new user message (1) = 4.
        assertEquals(4, captured.get(0).messages().size());
        assertTrue(captured.get(0).messages().get(1).isUser());
        assertTrue(captured.get(0).messages().get(2).isAssistant());
    }

    @Test
    void constructor_withoutSeedHistory_sendsOnlyTheNewMessage() {
        List<ChatCompletionCreateParams> captured = new ArrayList<>();
        OpenAiChatModel.CompletionService service = params -> {
            captured.add(params);
            return textCompletion("ok");
        };

        model(service).start("hello");

        assertEquals(2, captured.get(0).messages().size());
    }

    @Test
    void toSeedHistory_nullTurns_returnsEmptyList() {
        assertTrue(OpenAiChatModel.toSeedHistory(null).isEmpty());
    }

    @Test
    void toSeedHistory_alternatesUserThenAssistant() {
        List<ChatCompletionMessageParam> seed =
                OpenAiChatModel.toSeedHistory(Arrays.asList("q1", "a1", "q2", "a2"));

        assertEquals(4, seed.size());
        assertTrue(seed.get(0).isUser());
        assertTrue(seed.get(1).isAssistant());
        assertTrue(seed.get(2).isUser());
        assertTrue(seed.get(3).isAssistant());
    }

    @Test
    void send_noChoices_throwsInsteadOfReturningAnEmptyTurn() {
        OpenAiChatModel.CompletionService service = params -> emptyChoicesCompletion();

        assertThrows(IllegalStateException.class, () -> model(service).start("hi"));
    }
}
