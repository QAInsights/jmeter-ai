package org.qainsights.jmeter.ai.agent.google;

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
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link GoogleChatModel} using a fake {@link GoogleChatModel.GenerateService}. */
class GoogleChatModelTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Builds a real GenerateContentResponse from its JSON shape, bypassing strict builders. */
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

    private static GenerateContentResponse toolResponse(String name) {
        Map<String, Object> functionCall = new LinkedHashMap<>();
        functionCall.put("name", name);
        functionCall.put("args", Collections.emptyMap());
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("role", "model");
        content.put("parts", Collections.singletonList(Collections.singletonMap("functionCall", functionCall)));
        return response(content);
    }

    @Test
    void start_sendsUserMessageAndParsesToolCall() {
        Deque<GenerateContentResponse> responses = new ArrayDeque<>();
        responses.add(toolResponse("get_tree_state"));
        List<List<Content>> captured = new ArrayList<>();
        GoogleChatModel.GenerateService service = (model, contents, config) -> {
            captured.add(contents);
            return responses.removeFirst();
        };

        GoogleChatModel model = new GoogleChatModel(service, new GoogleToolAdapter(),
                Collections.<ToolSpec>emptyList(), "system", "gemini-2.5-flash", 1024);

        AssistantTurn turn = model.start("inspect the plan");

        assertTrue(turn.hasToolCalls());
        assertEquals("get_tree_state", turn.getToolCalls().get(0).getName());
        assertEquals(1, captured.get(0).size());
    }

    @Test
    void next_appendsToolResultsAndGrowsHistory() {
        Deque<GenerateContentResponse> responses = new ArrayDeque<>();
        responses.add(toolResponse("get_tree_state"));
        responses.add(textResponse("All set."));
        List<List<Content>> captured = new ArrayList<>();
        GoogleChatModel.GenerateService service = (model, contents, config) -> {
            captured.add(contents);
            return responses.removeFirst();
        };

        GoogleChatModel model = new GoogleChatModel(service, new GoogleToolAdapter(),
                Collections.<ToolSpec>emptyList(), "system", "gemini-2.5-flash", 1024);

        model.start("inspect");
        AssistantTurn turn = model.next(Collections.singletonList(
                new ToolOutcome("call_0", "get_tree_state", "tree", false)));

        assertFalse(turn.hasToolCalls());
        assertEquals("All set.", turn.getText());
        // History on the 2nd call: user + model(functionCall) + user(functionResponse) = 3.
        assertTrue(captured.get(1).size() > captured.get(0).size());
        assertEquals(3, captured.get(1).size());
    }

    @Test
    void constructor_withSeedHistory_prependsItToTheFirstRequest() {
        List<List<Content>> captured = new ArrayList<>();
        GoogleChatModel.GenerateService service = (model, contents, config) -> {
            captured.add(contents);
            return textResponse("ok");
        };
        List<Content> seed = Arrays.asList(
                Content.builder().role("user").parts(Part.fromText("earlier question")).build(),
                Content.builder().role("model").parts(Part.fromText("earlier answer")).build());

        GoogleChatModel model = new GoogleChatModel(service, new GoogleToolAdapter(),
                Collections.<ToolSpec>emptyList(), "system", "gemini-2.5-flash", 1024, seed);
        model.start("follow up");

        // seed (2) + the new user message (1) = 3.
        assertEquals(3, captured.get(0).size());
    }

    @Test
    void constructor_withoutSeedHistory_sendsOnlyTheNewMessage() {
        List<List<Content>> captured = new ArrayList<>();
        GoogleChatModel.GenerateService service = (model, contents, config) -> {
            captured.add(contents);
            return textResponse("ok");
        };

        GoogleChatModel model = new GoogleChatModel(service, new GoogleToolAdapter(),
                Collections.<ToolSpec>emptyList(), "system", "gemini-2.5-flash", 1024);
        model.start("hello");

        assertEquals(1, captured.get(0).size());
    }

    @Test
    void send_advertisesFunctionDeclarationsWhenSpecsPresent() {
        List<GenerateContentConfig> captured = new ArrayList<>();
        GoogleChatModel.GenerateService service = (model, contents, config) -> {
            captured.add(config);
            return textResponse("ok");
        };
        ToolSpec spec = ToolSpec.builder("get_tree_state").description("Reads the tree").build();

        GoogleChatModel model = new GoogleChatModel(service, new GoogleToolAdapter(),
                Collections.singletonList(spec), "system", "gemini-2.5-flash", 1024);
        model.start("hi");

        assertEquals(1, captured.get(0).tools().orElseThrow().get(0)
                .functionDeclarations().orElseThrow().size());
    }

    @Test
    void send_noSpecs_advertisesNoTools() {
        List<GenerateContentConfig> captured = new ArrayList<>();
        GoogleChatModel.GenerateService service = (model, contents, config) -> {
            captured.add(config);
            return textResponse("ok");
        };

        GoogleChatModel model = new GoogleChatModel(service, new GoogleToolAdapter(),
                Collections.<ToolSpec>emptyList(), "system", "gemini-2.5-flash", 1024);
        model.start("hi");

        assertTrue(captured.get(0).tools().orElse(Collections.emptyList()).isEmpty());
    }

    private static GenerateContentResponse fromJson(Map<String, Object> json) {
        try {
            return GenerateContentResponse.fromJson(JSON.writeValueAsString(json));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void send_noCandidates_throwsInsteadOfSilentlySucceeding() {
        GoogleChatModel.GenerateService service = (model, contents, config) -> fromJson(Collections.emptyMap());

        GoogleChatModel model = new GoogleChatModel(service, new GoogleToolAdapter(),
                Collections.<ToolSpec>emptyList(), "system", "gemini-2.5-flash", 1024);

        assertThrows(IllegalStateException.class, () -> model.start("hi"));
    }

    @Test
    void send_blockedPrompt_throwsInsteadOfSilentlySucceeding() {
        Map<String, Object> promptFeedback = new LinkedHashMap<>();
        promptFeedback.put("blockReason", "SAFETY");
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("promptFeedback", promptFeedback);
        GoogleChatModel.GenerateService service = (model, contents, config) -> fromJson(json);

        GoogleChatModel model = new GoogleChatModel(service, new GoogleToolAdapter(),
                Collections.<ToolSpec>emptyList(), "system", "gemini-2.5-flash", 1024);

        assertThrows(IllegalStateException.class, () -> model.start("hi"));
    }

    @Test
    void send_emptyCandidateParts_throwsInsteadOfSilentlySucceeding() {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("role", "model");
        content.put("parts", Collections.emptyList());
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("content", content);
        candidate.put("finishReason", "SAFETY");
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("candidates", Collections.singletonList(candidate));
        GoogleChatModel.GenerateService service = (model, contents, config) -> fromJson(json);

        GoogleChatModel model = new GoogleChatModel(service, new GoogleToolAdapter(),
                Collections.<ToolSpec>emptyList(), "system", "gemini-2.5-flash", 1024);

        assertThrows(IllegalStateException.class, () -> model.start("hi"));
    }

    @Test
    void consumeLastReasoning_returnsThoughtTextAndOmitsItFromTheAnswer() {
        Map<String, Object> thoughtPart = new LinkedHashMap<>();
        thoughtPart.put("text", "Let me check the tree first.");
        thoughtPart.put("thought", true);
        Map<String, Object> answerPart = new LinkedHashMap<>();
        answerPart.put("text", "Done.");
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("role", "model");
        content.put("parts", Arrays.asList(thoughtPart, answerPart));
        GoogleChatModel.GenerateService service = (model, contents, config) -> response(content);

        GoogleChatModel model = new GoogleChatModel(service, new GoogleToolAdapter(),
                Collections.<ToolSpec>emptyList(), "system", "gemini-2.5-flash", 1024);
        AssistantTurn turn = model.start("inspect the plan");

        assertEquals("Done.", turn.getText());
        assertEquals("Let me check the tree first.", model.consumeLastReasoning());
        assertNull(model.consumeLastReasoning());
    }

    @Test
    void consumeLastReasoning_returnsNullWhenTurnHadNoThoughtParts() {
        GoogleChatModel.GenerateService service = (model, contents, config) -> textResponse("Done.");

        GoogleChatModel model = new GoogleChatModel(service, new GoogleToolAdapter(),
                Collections.<ToolSpec>emptyList(), "system", "gemini-2.5-flash", 1024);
        model.start("inspect the plan");

        assertNull(model.consumeLastReasoning());
    }
}
