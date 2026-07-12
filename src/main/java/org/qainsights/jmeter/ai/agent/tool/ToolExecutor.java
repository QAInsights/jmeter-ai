package org.qainsights.jmeter.ai.agent.tool;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dispatches a named tool call to the matching {@link Tool} in a
 * {@link ToolRegistry}. Validates that required parameters are present and
 * converts unexpected exceptions into structured {@link ToolResult}s so the
 * agent loop never has to handle raw throwables.
 * <p>
 * Optionally gates a subset of tool names (typically destructive mutations)
 * behind a {@link ToolConfirmationGate}: when a gated tool is called and the
 * gate declines, the tool is never invoked and a descriptive error is
 * returned instead so the agent can report back to the user rather than retry.
 */
public final class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    public static final String ERR_UNKNOWN_TOOL = "unknown_tool";
    public static final String ERR_MISSING_PARAMETER = "missing_parameter";
    public static final String ERR_TOOL_EXCEPTION = "tool_exception";
    public static final String ERR_NULL_RESULT = "null_result";
    public static final String ERR_DECLINED = "declined_by_user";

    private final ToolRegistry registry;
    private final Set<String> confirmationRequiredTools;
    private final ToolConfirmationGate confirmationGate;

    public ToolExecutor(ToolRegistry registry) {
        this(registry, Collections.emptySet(), null);
    }

    /**
     * @param confirmationRequiredTools tool names that must be confirmed via {@code gate}
     *                                   before running; may be null/empty
     * @param confirmationGate           asked before running a gated tool; if {@code null},
     *                                    no confirmation is requested regardless of the set
     */
    public ToolExecutor(ToolRegistry registry, Set<String> confirmationRequiredTools,
                        ToolConfirmationGate confirmationGate) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.confirmationRequiredTools = confirmationRequiredTools == null
                ? Collections.emptySet() : confirmationRequiredTools;
        this.confirmationGate = confirmationGate;
    }

    /**
     * Executes the named tool with the given arguments.
     *
     * @param toolName  the registered tool name
     * @param arguments the argument map (may be {@code null}, treated as empty)
     * @return a successful or descriptive-error {@link ToolResult}
     */
    public ToolResult execute(String toolName, Map<String, Object> arguments) {
        Tool tool = registry.get(toolName);
        if (tool == null) {
            return ToolResult.error(ERR_UNKNOWN_TOOL, "No tool registered with name: " + toolName);
        }

        Map<String, Object> args = arguments == null ? Collections.emptyMap() : arguments;

        for (ToolParameter required : tool.getSpec().getRequiredParameters()) {
            if (!args.containsKey(required.getName()) || args.get(required.getName()) == null) {
                return ToolResult.error(ERR_MISSING_PARAMETER,
                        "Missing required parameter '" + required.getName() + "' for tool '" + toolName + "'");
            }
        }

        if (confirmationGate != null && confirmationRequiredTools.contains(toolName)
                && !confirmationGate.confirm(toolName, args)) {
            return ToolResult.error(ERR_DECLINED, "The user declined to confirm '" + toolName
                    + "'. Do not retry this call automatically; tell the user it was skipped and ask how "
                    + "they'd like to proceed.");
        }

        try {
            ToolResult result = tool.execute(args);
            if (result == null) {
                return ToolResult.error(ERR_NULL_RESULT, "Tool '" + toolName + "' returned no result");
            }
            return result;
        } catch (Exception e) {
            log.error("Tool '{}' threw an exception", toolName, e);
            return ToolResult.error(ERR_TOOL_EXCEPTION,
                    "Tool '" + toolName + "' failed: " + e.getMessage());
        }
    }
}
