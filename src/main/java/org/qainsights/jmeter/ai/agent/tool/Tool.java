package org.qainsights.jmeter.ai.agent.tool;

import java.util.Map;

/**
 * An executable tool: a {@link ToolSpec} definition plus the logic that performs
 * the operation. Implementations must be side-effect-free with respect to the
 * arguments map and should return a {@link ToolResult} rather than throwing for
 * expected failures (e.g. precondition violations).
 */
public interface Tool {

    /** The provider-neutral definition of this tool. */
    ToolSpec getSpec();

    /**
     * Executes the tool with the supplied arguments.
     *
     * @param arguments parameter name to value map (never {@code null})
     * @return the structured result of the call
     */
    ToolResult execute(Map<String, Object> arguments);
}
