package org.qainsights.jmeter.ai.agent;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.claude.ClaudeChatModel;
import org.qainsights.jmeter.ai.agent.loop.AgentLoop;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Message;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for {@link JMeterAgent#run} with a fake message service.
 * The model replies with plain text (no tool calls), so the loop completes
 * without touching the live JMeter tree.
 */
class JMeterAgentTest {

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

    @Test
    void run_completesWithFinalTextWhenNoToolsRequested() {
        ClaudeChatModel.MessageService service = params -> textMessage("I can help with that.");
        JMeterAgent agent = new JMeterAgent(service, "claude", 1024, 5);

        AgentLoop.AgentResult result = agent.run("hello", null);

        assertTrue(result.isCompleted());
        assertEquals("I can help with that.", result.getFinalText());
        assertEquals(1, result.getIterations());
    }
}
