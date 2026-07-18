package org.qainsights.jmeter.ai.record;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.*;
import org.qainsights.jmeter.ai.service.ClaudeService;

/**
 * Anthropic-based planner that constructs structured step actions via tool-calling.
 */
public final class ClaudeBrowserFlowPlanner implements BrowserFlowPlanner {

    private final ClaudeService claudeService;

    public ClaudeBrowserFlowPlanner(ClaudeService claudeService) {
        this.claudeService = claudeService;
    }

    @Override
    public BrowserFlowPlan plan(String prompt, SessionConfig config) throws Exception {
        AnthropicClient anthropic = claudeService.getClient();
        String systemPrompt = buildSystemPrompt(prompt, config);
        Tool planningTool = buildPlanningTool();

        MessageCreateParams params = MessageCreateParams.builder()
            .model(claudeService.getCurrentModel())
            .maxTokens(4096)
            .system(systemPrompt)
            .addTool(planningTool)
            .toolChoice(ToolChoice.ofTool(ToolChoiceTool.builder().name("submit_browser_flow_plan").build()))
            .addMessage(MessageParam.builder().role(MessageParam.Role.USER).content(prompt).build())
            .build();

        Message response = anthropic.messages().create(params);
        return extractPlan(response);
    }

    private String buildSystemPrompt(String prompt, SessionConfig config) throws Exception {
        String host = new URI(config.baseUri()).getHost();
        return "You are a browser automation planning assistant. Generate intent-based steps to achieve the user's goal.\n"
            + "User goal: " + prompt + "\n"
            + "Base URL: " + config.baseUri() + "\n"
            + "Constraints:\n"
            + "- Only navigate within allowed origin (host: " + host + ").\n"
            + "- Supported actions: navigate, fill, click, select, wait.\n"
            + "- For click, fill, or select, produce semantic element intents: 'role' (e.g. link, button, textbox, combobox) and 'text' target representation (e.g. 'Sign In', 'Username').\n"
            + "- For navigate or wait, the value contains the URL or delay (in ms).\n"
            + "- Literal values only, or ${NAME} secret references.\n"
            + "- Do NOT use any other tool. Submit the plan using submit_browser_flow_plan.\n"
            + "- Maximum 50 steps.";
    }

    private Tool buildPlanningTool() {
        Map<String, Object> stepProperties = new LinkedHashMap<>();
        stepProperties.put("action", Map.of("type", "string", "enum", List.of("navigate", "fill", "click", "select", "wait")));
        stepProperties.put("role", Map.of("type", "string", "description", "The semantic role of the element (e.g. link, button, textbox)"));
        stepProperties.put("text", Map.of("type", "string", "description", "The label, placeholder or text of the element"));
        stepProperties.put("value", Map.of("type", "string", "description", "The input value for fill/select, navigation URL, or timeout duration"));
        stepProperties.put("index", Map.of("type", "integer"));
        stepProperties.put("description", Map.of("type", "string", "description", "Intent explanation"));

        Map<String, Object> stepsArray = new LinkedHashMap<>();
        stepsArray.put("type", "array");
        stepsArray.put("items", Map.of("type", "object", "properties", stepProperties, "required", List.of("action")));

        Tool.InputSchema.Properties properties = Tool.InputSchema.Properties.builder()
            .putAdditionalProperty("steps", JsonValue.from(stepsArray))
            .build();

        return Tool.builder()
            .name("submit_browser_flow_plan")
            .description("Submit the sequence of browser actions to execute.")
            .inputSchema(Tool.InputSchema.builder().properties(properties).required(List.of("steps")).build())
            .build();
    }

    private BrowserFlowPlan extractPlan(Message response) throws Exception {
        for (ContentBlock block : response.content()) {
            if (block.isToolUse()) {
                ToolUseBlock toolUse = block.asToolUse();
                if ("submit_browser_flow_plan".equals(toolUse.name())) {
                    JsonNode root = toolUse._input().convert(JsonNode.class);
                    return parsePlanJson(root);
                }
            }
        }
        throw new RecordingException("LLM response did not invoke the planning tool.");
    }

    private BrowserFlowPlan parsePlanJson(JsonNode root) throws Exception {
        JsonNode stepsNode = root.get("steps");
        List<BrowserStep> steps = new ArrayList<>();
        if (stepsNode != null && stepsNode.isArray()) {
            for (JsonNode stepNode : stepsNode) {
                steps.add(parseStep(stepNode));
            }
        }
        if (steps.size() > 50) {
            throw new RecordingException("Step count exceeds max limit of 50");
        }
        return new BrowserFlowPlan(steps, "Browser Flow Plan");
    }

    private BrowserStep parseStep(JsonNode node) {
        String action = node.get("action").asText();
        String role = node.has("role") ? node.get("role").asText() : "";
        String text = node.has("text") ? node.get("text").asText() : "";
        String value = node.has("value") ? node.get("value").asText() : "";
        Integer index = node.has("index") && !node.get("index").isNull() ? node.get("index").asInt() : null;
        String desc = node.has("description") ? node.get("description").asText() : "";

        Set<String> supported = Set.of("navigate", "fill", "click", "select", "wait");
        if (!supported.contains(action.toLowerCase())) {
            throw new RecordingException("Unsupported action in plan: " + action);
        }
        return new BrowserStep(action, role, text, value, index, desc);
    }
}
