package org.qainsights.jmeter.ai.agent.loop;

import org.qainsights.jmeter.ai.agent.tool.ToolResult;

/**
 * Provider-neutral result of executing one {@link AssistantTurn.ToolCall},
 * ready to be fed back to the model on the next turn. Carries the original
 * tool-call id so providers can correlate it with their tool_use block.
 */
public final class ToolOutcome {

    private final String toolCallId;
    private final String name;
    private final String content;
    private final boolean error;

    public ToolOutcome(String toolCallId, String name, String content, boolean error) {
        this.toolCallId = toolCallId;
        this.name = name;
        this.content = content == null ? "" : content;
        this.error = error;
    }

    /** Builds an outcome from a {@link ToolResult}, flattening success/error into text. */
    public static ToolOutcome from(AssistantTurn.ToolCall call, ToolResult result) {
        if (result.isSuccess()) {
            return new ToolOutcome(call.getId(), call.getName(), result.getData(), false);
        }
        String body = "ERROR [" + result.getErrorCode() + "] " + result.getMessage();
        return new ToolOutcome(call.getId(), call.getName(), body, true);
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public String getName() {
        return name;
    }

    public String getContent() {
        return content;
    }

    public boolean isError() {
        return error;
    }
}
