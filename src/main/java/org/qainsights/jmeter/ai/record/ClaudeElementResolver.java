package org.qainsights.jmeter.ai.record;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.qainsights.jmeter.ai.service.ClaudeService;

/**
 * Resolves element locators by presenting an accessibility snapshot to Claude.
 */
public final class ClaudeElementResolver implements ElementResolver {

    private final ClaudeService claudeService;

    public ClaudeElementResolver(ClaudeService claudeService) {
        this.claudeService = claudeService;
    }

    @Override
    public String resolve(String snapshot, BrowserStep step) throws Exception {
        AnthropicClient anthropic = claudeService.getClient();
        String systemPrompt = buildSystemPrompt(snapshot, step);
        Tool resolutionTool = buildResolutionTool();

        MessageCreateParams params = MessageCreateParams.builder()
            .model(claudeService.getCurrentModel())
            .maxTokens(1024)
            .system(systemPrompt)
            .addTool(resolutionTool)
            .toolChoice(ToolChoice.ofTool(ToolChoiceTool.builder().name("submit_resolved_selector").build()))
            .addMessage(MessageParam.builder().role(MessageParam.Role.USER).content("Resolve target element selector").build())
            .build();

        Message response = anthropic.messages().create(params);
        return extractSelector(response);
    }

    private String buildSystemPrompt(String snapshot, BrowserStep step) {
        return "You are a Playwright locator resolver. Find the correct selector for the target element.\n"
            + "Target intent:\n"
            + "- Action: " + step.action() + "\n"
            + "- Role: " + step.role() + "\n"
            + "- Text target: " + step.text() + "\n"
            + "- Value: " + step.value() + "\n\n"
            + "Accessibility snapshot:\n"
            + "```yaml\n"
            + snapshot + "\n"
            + "```\n\n"
            + "Submit the resolved Playwright selector (e.g. 'role=button,name=Sign In', 'text=Login', or explicit CSS like '#username').";
    }

    private Tool buildResolutionTool() {
        Tool.InputSchema.Properties properties = Tool.InputSchema.Properties.builder()
            .putAdditionalProperty("selector", JsonValue.from(Map.of("type", "string", "description", "The resolved Playwright locator selector")))
            .build();

        return Tool.builder()
            .name("submit_resolved_selector")
            .description("Submit the resolved Playwright selector to interact with the target element.")
            .inputSchema(Tool.InputSchema.builder().properties(properties).required(List.of("selector")).build())
            .build();
    }

    private String extractSelector(Message response) throws Exception {
        for (ContentBlock block : response.content()) {
            if (block.isToolUse()) {
                ToolUseBlock toolUse = block.asToolUse();
                if ("submit_resolved_selector".equals(toolUse.name())) {
                    JsonNode root = toolUse._input().convert(JsonNode.class);
                    return root.get("selector").asText();
                }
            }
        }
        throw new RecordingException("LLM failed to submit a resolved selector.");
    }
}
