package org.qainsights.jmeter.ai.record;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.anthropic.services.blocking.MessageService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.service.ClaudeService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ClaudeBrowserFlowPlanner}.
 */
class ClaudeBrowserFlowPlannerTest {

    @Test
    void should_parsePlan_when_llmReturnsValidToolCall() throws Exception {
        ClaudeService service = mock(ClaudeService.class);
        AnthropicClient client = mock(AnthropicClient.class);
        MessageService messages = mock(MessageService.class);
        Message message = mock(Message.class);

        when(service.getClient()).thenReturn(client);
        when(service.getCurrentModel()).thenReturn("claude-test");
        when(client.messages()).thenReturn(messages);
        when(messages.create(any(MessageCreateParams.class))).thenReturn(message);

        ContentBlock block = mock(ContentBlock.class);
        ToolUseBlock toolUse = mock(ToolUseBlock.class);
        when(block.isToolUse()).thenReturn(true);
        when(block.asToolUse()).thenReturn(toolUse);
        when(toolUse.name()).thenReturn("submit_browser_flow_plan");
        
        JsonValue inputVal = JsonValue.from(Map.of("steps", List.of(
            Map.of("action", "navigate", "value", "https://example.com"),
            Map.of("action", "fill", "role", "textbox", "text", "Password", "value", "${PASSWORD}")
        )));
        when(toolUse._input()).thenReturn(inputVal);
        when(message.content()).thenReturn(List.of(block));

        ClaudeBrowserFlowPlanner planner = new ClaudeBrowserFlowPlanner(service);
        SessionConfig config = new SessionConfig("Prompt", "https://example.com", "chromium");
        
        BrowserFlowPlan plan = planner.plan("Prompt", config);
        
        assertNotNull(plan);
        assertEquals(2, plan.steps().size());
        assertEquals("navigate", plan.steps().get(0).action());
        assertEquals("https://example.com", plan.steps().get(0).value());
        assertEquals("fill", plan.steps().get(1).action());
        assertEquals("textbox", plan.steps().get(1).role());
        assertEquals("Password", plan.steps().get(1).text());
        assertEquals("${PASSWORD}", plan.steps().get(1).value());
    }
}
