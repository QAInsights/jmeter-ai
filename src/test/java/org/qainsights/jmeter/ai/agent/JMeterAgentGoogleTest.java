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
import org.qainsights.jmeter.ai.agent.google.GoogleChatModel;
import org.qainsights.jmeter.ai.agent.loop.AgentLoop;
import org.qainsights.jmeter.ai.agent.tool.ToolConfirmationGate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Tool;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mirror of {@link JMeterAgentTest} for the Google Gemini provider: the same
 * agent façade, tool registry and loop, driven by a fake generate service
 * instead of the Anthropic one - proving the tools are provider-agnostic.
 */
class JMeterAgentGoogleTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeEach
    void resetUndoNudge() {
        JMeterAgent.resetUndoNudgeForTests();
    }

    private static GenerateContentResponse response(Map<String, Object> contentJson) {
        try {
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("content", contentJson);
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("candidates", Collections.singletonList(candidate));
            return GenerateContentResponse.fromJson(JSON.writeValueAsString(json));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static GenerateContentResponse textResponse(String text) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("role", "model");
        content.put("parts", Collections.singletonList(Collections.singletonMap("text", text)));
        return response(content);
    }

    private static GenerateContentResponse toolResponse(String name, Map<String, Object> args) {
        Map<String, Object> functionCall = new LinkedHashMap<>();
        functionCall.put("name", name);
        functionCall.put("args", args);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("role", "model");
        content.put("parts", Collections.singletonList(Collections.singletonMap("functionCall", functionCall)));
        return response(content);
    }

    private static JMeterAgent agent(GoogleChatModel.GenerateService service, ToolConfirmationGate gate) {
        return new JMeterAgent(JMeterAgent.googleFactory(service, "gemini-2.5-flash", 1024), 5, gate);
    }

    @Test
    void run_completesWithFinalTextWhenNoToolsRequested() {
        GoogleChatModel.GenerateService service = (model, contents, config) -> textResponse("I can help with that.");

        AgentLoop.AgentResult result = agent(service, null).run("hello", null);

        assertTrue(result.isCompleted());
        assertEquals("I can help with that.", result.getFinalText());
        assertEquals(1, result.getIterations());
    }

    @Test
    void run_advertisesTheFullToolRegistryToGemini() {
        List<GenerateContentConfig> captured = new ArrayList<>();
        GoogleChatModel.GenerateService service = (model, contents, config) -> {
            captured.add(config);
            return textResponse("ok");
        };

        agent(service, null).run("hello", null);

        List<String> toolNames = new ArrayList<>();
        for (Tool tool : captured.get(0).tools().orElse(Collections.emptyList())) {
            for (FunctionDeclaration declaration : tool.functionDeclarations().orElse(Collections.emptyList())) {
                toolNames.add(declaration.name().orElse(""));
            }
        }
        assertTrue(toolNames.contains("get_tree_state"), "expected read tools, got " + toolNames);
        assertTrue(toolNames.contains("add_element"), "expected write tools, got " + toolNames);
        assertTrue(toolNames.contains("delete_element"), "expected destructive tools, got " + toolNames);
    }

    @Test
    void run_withPriorConversation_seedsHistoryBeforeTheNewMessage() {
        List<List<Content>> captured = new ArrayList<>();
        GoogleChatModel.GenerateService service = (model, contents, config) -> {
            captured.add(contents);
            return textResponse("Sure, added it.");
        };

        List<String> prior = Arrays.asList("add a thread group", "Added a Thread Group.");
        AgentLoop.AgentResult result = agent(service, null).run("now add an http sampler", prior, null);

        assertTrue(result.isCompleted());
        // seed (2) + the new user message (1) = 3 (system prompt is sent separately).
        assertEquals(3, captured.get(0).size());
    }

    @Test
    void run_withOddPriorConversation_dropsTrailingUnpairedTurn() {
        List<List<Content>> captured = new ArrayList<>();
        GoogleChatModel.GenerateService service = (model, contents, config) -> {
            captured.add(contents);
            return textResponse("ok");
        };

        agent(service, null).run("now add an http sampler",
                Collections.singletonList("add a thread group"), null);

        // Just the new user message.
        assertEquals(1, captured.get(0).size());
    }

    @Test
    void run_withNullPriorConversation_behavesLikeNoHistory() {
        List<List<Content>> captured = new ArrayList<>();
        GoogleChatModel.GenerateService service = (model, contents, config) -> {
            captured.add(contents);
            return textResponse("ok");
        };

        agent(service, null).run("hello", null, null);

        assertEquals(1, captured.get(0).size());
    }

    @Test
    void run_declinedDestructiveTool_neverReachesTheHandler() {
        Deque<GenerateContentResponse> responses = new ArrayDeque<>();
        responses.add(toolResponse("delete_element",
                Collections.singletonMap("element_id", "Test Plan/Thread Group")));
        responses.add(textResponse("Okay, I will not delete it."));
        GoogleChatModel.GenerateService service = (model, contents, config) -> responses.removeFirst();

        List<String> progressLines = new ArrayList<>();
        AgentLoop.AgentResult result = agent(service, (toolName, args) -> false)
                .run("delete the thread group", null, progressLines::add);

        assertTrue(result.isCompleted());
        assertEquals("Okay, I will not delete it.", result.getFinalText());
        assertTrue(progressLines.stream().anyMatch(l -> l.contains("declined")));
    }

    @Test
    void run_toolCallResult_isFedBackAsAFunctionResponse() {
        Deque<GenerateContentResponse> responses = new ArrayDeque<>();
        responses.add(toolResponse("get_tree_state", Collections.emptyMap()));
        responses.add(textResponse("Here's the tree."));
        List<List<Content>> captured = new ArrayList<>();
        GoogleChatModel.GenerateService service = (model, contents, config) -> {
            captured.add(contents);
            return responses.removeFirst();
        };

        AgentLoop.AgentResult result = agent(service, null).run("show me the tree", null);

        assertTrue(result.isCompleted());
        assertEquals(2, result.getIterations());
        Content lastTurn = captured.get(1).get(captured.get(1).size() - 1);
        assertEquals("user", lastTurn.role().orElse(""));
        assertTrue(lastTurn.parts().orElseThrow().get(0).functionResponse().isPresent());
    }
}
