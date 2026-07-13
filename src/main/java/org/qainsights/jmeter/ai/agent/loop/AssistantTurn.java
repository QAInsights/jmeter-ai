package org.qainsights.jmeter.ai.agent.loop;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Provider-neutral view of a single assistant turn: optional free text plus zero
 * or more tool calls the model wants executed. {@link ChatModel} implementations
 * translate a provider response into this shape so {@link AgentLoop} stays
 * independent of any specific LLM SDK.
 */
public final class AssistantTurn {

    /** A single tool invocation requested by the model. */
    public static final class ToolCall {
        private final String id;
        private final String name;
        private final Map<String, Object> arguments;

        public ToolCall(String id, String name, Map<String, Object> arguments) {
            this.id = id;
            this.name = name;
            this.arguments = arguments == null
                    ? Collections.emptyMap()
                    : Collections.unmodifiableMap(arguments);
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public Map<String, Object> getArguments() {
            return arguments;
        }
    }

    private final String text;
    private final List<ToolCall> toolCalls;

    public AssistantTurn(String text, List<ToolCall> toolCalls) {
        this.text = text == null ? "" : text;
        this.toolCalls = toolCalls == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(toolCalls);
    }

    /** The assistant's free-text content (may be empty). */
    public String getText() {
        return text;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
