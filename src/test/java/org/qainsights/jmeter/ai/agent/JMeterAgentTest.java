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
import org.qainsights.jmeter.ai.agent.claude.ClaudeChatModel;
import org.qainsights.jmeter.ai.agent.loop.AgentLoop;
import org.qainsights.jmeter.ai.agent.tool.ToolConfirmationGate;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for {@link JMeterAgent#run} with a fake message service.
 * The model replies with plain text (no tool calls), so the loop completes
 * without touching the live JMeter tree.
 */
class JMeterAgentTest {

    @BeforeEach
    void resetUndoNudge() {
        JMeterAgent.resetUndoNudgeForTests();
    }

    private static Message textMessage(String text) {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("input_tokens", 0);
        usage.put("output_tokens", 0);
        Map<String, Object> textBlock = new LinkedHashMap<>();
        textBlock.put("type", "text");
        textBlock.put("text", text);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", "m_1");
        json.put("type", "message");
        json.put("role", "assistant");
        json.put("model", "claude");
        json.put("stop_reason", "end_turn");
        json.put("usage", usage);
        json.put("content", Arrays.asList(textBlock));
        return JsonValue.from(json).convert(Message.class);
    }

    private static Message toolMessage(String id, String name, Map<String, Object> input) {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("input_tokens", 0);
        usage.put("output_tokens", 0);
        Map<String, Object> toolUse = new LinkedHashMap<>();
        toolUse.put("type", "tool_use");
        toolUse.put("id", id);
        toolUse.put("name", name);
        toolUse.put("input", input);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", "m_1");
        json.put("type", "message");
        json.put("role", "assistant");
        json.put("model", "claude");
        json.put("stop_reason", "tool_use");
        json.put("usage", usage);
        json.put("content", Arrays.asList(toolUse));
        return JsonValue.from(json).convert(Message.class);
    }

    @Test
    void run_completesWithFinalTextWhenNoToolsRequested() {
        ClaudeChatModel.MessageService service = params -> textMessage("I can help with that.");
        JMeterAgent agent = new JMeterAgent(service, "claude", 1024, 5);

        AgentLoop.AgentResult result = agent.run("hello", null);

        assertTrue(result.isCompleted());
        assertEquals("I can help with that.", result.getFinalText());
        assertEquals(1, result.getIterations());
    }

    @Test
    void run_withPriorConversation_seedsHistoryBeforeTheNewMessage() {
        List<MessageCreateParams> captured = new ArrayList<>();
        ClaudeChatModel.MessageService service = params -> {
            captured.add(params);
            return textMessage("Sure, added it.");
        };
        JMeterAgent agent = new JMeterAgent(service, "claude", 1024, 5);

        List<String> prior = Arrays.asList("add a thread group", "Added a Thread Group.");
        AgentLoop.AgentResult result = agent.run("now add an http sampler", prior, null);

        assertTrue(result.isCompleted());
        // seed (2) + the new user message (1) = 3.
        assertEquals(3, captured.get(0).messages().size());
    }

    @Test
    void run_withOddPriorConversation_dropsTrailingUnpairedTurn() {
        List<MessageCreateParams> captured = new ArrayList<>();
        ClaudeChatModel.MessageService service = params -> {
            captured.add(params);
            return textMessage("ok");
        };
        JMeterAgent agent = new JMeterAgent(service, "claude", 1024, 5);

        // A dangling unpaired user turn (no matching assistant reply) must be dropped so
        // the seed history doesn't end with two consecutive user turns.
        List<String> prior = Arrays.asList("add a thread group");
        agent.run("now add an http sampler", prior, null);

        assertEquals(1, captured.get(0).messages().size());
    }

    @Test
    void run_withNullPriorConversation_behavesLikeNoHistory() {
        List<MessageCreateParams> captured = new ArrayList<>();
        ClaudeChatModel.MessageService service = params -> {
            captured.add(params);
            return textMessage("ok");
        };
        JMeterAgent agent = new JMeterAgent(service, "claude", 1024, 5);

        agent.run("hello", null, null);

        assertEquals(1, captured.get(0).messages().size());
    }

    @Test
    void run_declinedDestructiveTool_neverReachesTheHandler() {
        Deque<Message> responses = new ArrayDeque<>();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("element_id", "Test Plan/Thread Group");
        responses.add(toolMessage("tu_1", "delete_element", input));
        responses.add(textMessage("Okay, I will not delete it."));
        ClaudeChatModel.MessageService service = params -> responses.removeFirst();

        ToolConfirmationGate decline = (toolName, args) -> false;
        JMeterAgent agent = new JMeterAgent(service, "claude", 1024, 5, decline);

        List<String> progressLines = new ArrayList<>();
        AgentLoop.AgentResult result = agent.run("delete the thread group", null, progressLines::add);

        assertTrue(result.isCompleted());
        assertEquals("Okay, I will not delete it.", result.getFinalText());
        assertTrue(progressLines.stream().anyMatch(l -> l.contains("declined")));
    }

    @Test
    void run_approvedDestructiveTool_proceedsToTheRealHandler() {
        Deque<Message> responses = new ArrayDeque<>();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("element_id", "Test Plan/Thread Group");
        responses.add(toolMessage("tu_1", "delete_element", input));
        responses.add(textMessage("Done."));
        ClaudeChatModel.MessageService service = params -> responses.removeFirst();

        ToolConfirmationGate approve = (toolName, args) -> true;
        JMeterAgent agent = new JMeterAgent(service, "claude", 1024, 5, approve);

        List<String> progressLines = new ArrayList<>();
        agent.run("delete the thread group", null, progressLines::add);

        // No live JMeter GUI in this test, so the real handler is reached and reports
        // "no test plan" rather than the confirmation gate's "declined" message.
        assertTrue(progressLines.stream().noneMatch(l -> l.contains("declined")));
        assertTrue(progressLines.stream().anyMatch(l -> l.toLowerCase().contains("test plan")));
    }

    @Test
    void run_nonDestructiveToolCall_ignoresGateEvenWhenPresent() {
        Deque<Message> responses = new ArrayDeque<>();
        responses.add(toolMessage("tu_1", "get_tree_state", Collections.emptyMap()));
        responses.add(textMessage("Here's the tree."));
        ClaudeChatModel.MessageService service = params -> responses.removeFirst();

        ToolConfirmationGate declineEverything = (toolName, args) -> false;
        JMeterAgent agent = new JMeterAgent(service, "claude", 1024, 5, declineEverything);

        List<String> progressLines = new ArrayList<>();
        agent.run("show me the tree", null, progressLines::add);

        assertTrue(progressLines.stream().noneMatch(l -> l.contains("declined")));
    }

    @Test
    void run_undoHistoryDisabled_showsNudgeOnceOnFirstRunOnly() {
        // undo.history.size defaults to 0 in a plain test JVM, so UndoHistory.isEnabled()
        // is false here - exercising the "not yet enabled" branch of the nudge.
        ClaudeChatModel.MessageService service = params -> textMessage("ok");
        JMeterAgent agent = new JMeterAgent(service, "claude", 1024, 5);

        List<String> firstRun = new ArrayList<>();
        agent.run("hello", null, firstRun::add);
        assertTrue(firstRun.stream().anyMatch(l -> l.contains("Undo/Redo")));

        List<String> secondRun = new ArrayList<>();
        agent.run("hello again", null, secondRun::add);
        assertTrue(secondRun.stream().noneMatch(l -> l.contains("Undo/Redo")));
    }

    @Test
    void run_nullProgress_doesNotThrowWhileCheckingUndoHistory() {
        ClaudeChatModel.MessageService service = params -> textMessage("ok");
        JMeterAgent agent = new JMeterAgent(service, "claude", 1024, 5);

        assertDoesNotThrow(() -> agent.run("hello", null, null));
    }
}
