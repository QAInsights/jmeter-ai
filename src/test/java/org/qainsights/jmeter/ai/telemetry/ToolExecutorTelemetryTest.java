package org.qainsights.jmeter.ai.telemetry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.tool.ParamType;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolExecutor;
import org.qainsights.jmeter.ai.agent.tool.ToolParameter;
import org.qainsights.jmeter.ai.agent.tool.ToolRegistry;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Telemetry counters recorded by {@link ToolExecutor} for every way a tool call can end. */
class ToolExecutorTelemetryTest {

    private TelemetryCounters counters;
    private TelemetryCounters previous;
    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        counters = new TelemetryCounters();
        previous = Telemetry.swapCountersForTesting(counters);
        registry = new ToolRegistry();
        registry.register(tool("add_element", args -> ToolResult.ok("done")));
        registry.register(tool("delete_element", args -> ToolResult.ok("deleted")));
    }

    @AfterEach
    void tearDown() {
        Telemetry.swapCountersForTesting(previous);
    }

    private static Tool tool(String name, java.util.function.Function<Map<String, Object>, ToolResult> fn) {
        ToolSpec spec = ToolSpec.builder(name)
                .addParameter(ToolParameter.builder("type", ParamType.STRING).required(true).build())
                .build();
        return new Tool() {
            @Override
            public ToolSpec getSpec() {
                return spec;
            }

            @Override
            public ToolResult execute(Map<String, Object> arguments) {
                return fn.apply(arguments);
            }
        };
    }

    private static Map<String, Object> typeArg() {
        Map<String, Object> args = new HashMap<>();
        args.put("type", "x");
        return args;
    }

    @Test
    void successfulCallCountsOnlyTheCall() {
        new ToolExecutor(registry).execute("add_element", typeArg());
        assertEquals(Map.of("tool_add_element", 1), counters.snapshot());
    }

    @Test
    void missingParameterCountsAsAFailedCallOfThatTool() {
        new ToolExecutor(registry).execute("add_element", new HashMap<>());
        assertEquals(Map.of("tool_add_element", 1, "outcome_tool_failed", 1, "toolfail_add_element", 1),
                counters.snapshot());
    }

    @Test
    void unknownToolCountsAFailureWithoutTheRequestedName() {
        new ToolExecutor(registry).execute("Made Up Tool!", typeArg());
        assertEquals(Map.of("outcome_tool_failed", 1, "toolfail__unknown", 1), counters.snapshot());
    }

    @Test
    void declinedConfirmationCountsOnlyTheDecline() {
        new ToolExecutor(registry, Set.of("delete_element"), (name, args) -> false)
                .execute("delete_element", typeArg());
        assertEquals(Map.of("outcome_tool_declined", 1), counters.snapshot());
    }

    @Test
    void failedResultCountsAFailureOfThatTool() {
        registry.register(tool("run_test", args -> ToolResult.error("x", "boom")));
        new ToolExecutor(registry).execute("run_test", typeArg());
        assertEquals(Map.of("tool_run_test", 1, "outcome_tool_failed", 1, "toolfail_run_test", 1),
                counters.snapshot());
    }
}
