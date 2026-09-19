package org.qainsights.jmeter.ai.agent;

import java.util.Map;
import java.util.function.Function;

import org.qainsights.jmeter.ai.agent.tool.ParamType;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolParameter;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;

/**
 * The {@code expand_tools} meta-tool, advertised only inside Jev-focused Agent
 * Mode tool packs as an escape hatch: when the model realises its current tools
 * cannot accomplish the task, it calls this instead of giving up or guessing
 * tool names. It performs no JMeter mutation itself - it delegates to the run's
 * {@link AgentToolExpander}, which asks Jev to re-route and grows the live tool
 * registry. It is never part of the default registry.
 */
public final class ExpandToolsTool implements Tool {

    public static final String EXPAND_TOOLS = "expand_tools";
    static final int MAX_REASON_CHARS = 500;

    private final ToolSpec spec;
    private final Function<String, ToolResult> onExpand;

    /**
     * @param onExpand invoked with the bounded {@code reason} argument each time the
     *                 model calls the tool; returns the expansion result
     */
    public ExpandToolsTool(Function<String, ToolResult> onExpand) {
        if (onExpand == null) {
            throw new IllegalArgumentException("onExpand must not be null");
        }
        this.onExpand = onExpand;
        this.spec = ToolSpec.builder(EXPAND_TOOLS)
                .description("Request additional JMeter tools when the task needs a capability your current "
                        + "tools cannot provide (for example running a test when only editing tools are "
                        + "available). Do NOT invent or guess tool names that were not advertised to you; "
                        + "call this instead and explain the missing capability in 'reason'. The tool set "
                        + "can only grow, never shrink; newly enabled tools are described in the result.")
                .addParameter(ToolParameter.builder("reason", ParamType.STRING)
                        .description("The missing capability, e.g. 'run the test plan and inspect failures'")
                        .required(true)
                        .build())
                .build();
    }

    @Override
    public ToolSpec getSpec() {
        return spec;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        Object reason = arguments == null ? null : arguments.get("reason");
        String text = reason == null ? "" : String.valueOf(reason).trim();
        if (text.length() > MAX_REASON_CHARS) {
            text = text.substring(0, MAX_REASON_CHARS);
        }
        return onExpand.apply(text);
    }
}
