package org.qainsights.jmeter.ai.agent.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link ToolExecutor}. */
class ToolExecutorTest {

    /** Builds a tool from a spec and an execution function. */
    private static Tool tool(ToolSpec spec, Function<Map<String, Object>, ToolResult> fn) {
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

    private static ToolSpec specWithRequired(String name, String requiredParam) {
        return ToolSpec.builder(name)
                .addParameter(ToolParameter.builder(requiredParam, ParamType.STRING).required(true).build())
                .build();
    }

    private ToolRegistry registry;
    private ToolExecutor executor;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
        executor = new ToolExecutor(registry);
    }

    @Test
    void constructor_nullRegistry_throws() {
        assertThrows(NullPointerException.class, () -> new ToolExecutor(null));
    }

    @Test
    void execute_unknownTool_returnsUnknownToolError() {
        ToolResult r = executor.execute("nope", new HashMap<>());
        assertFalse(r.isSuccess());
        assertEquals(ToolExecutor.ERR_UNKNOWN_TOOL, r.getErrorCode());
    }

    @Test
    void execute_missingRequiredParameter_returnsMissingParameterError() {
        registry.register(tool(specWithRequired("add", "type"), args -> ToolResult.ok("done")));
        ToolResult r = executor.execute("add", new HashMap<>());
        assertEquals(ToolExecutor.ERR_MISSING_PARAMETER, r.getErrorCode());
    }

    @Test
    void execute_nullValueForRequiredParameter_returnsMissingParameterError() {
        registry.register(tool(specWithRequired("add", "type"), args -> ToolResult.ok("done")));
        Map<String, Object> args = new HashMap<>();
        args.put("type", null);
        assertEquals(ToolExecutor.ERR_MISSING_PARAMETER, executor.execute("add", args).getErrorCode());
    }

    @Test
    void execute_validCall_passesArgumentsAndReturnsResult() {
        registry.register(tool(specWithRequired("add", "type"),
                args -> ToolResult.ok("type=" + args.get("type"))));
        Map<String, Object> args = new HashMap<>();
        args.put("type", "HTTPSamplerProxy");
        ToolResult r = executor.execute("add", args);
        assertTrue(r.isSuccess());
        assertEquals("type=HTTPSamplerProxy", r.getData());
    }

    @Test
    void execute_nullArguments_treatedAsEmptyForNoRequiredParams() {
        registry.register(tool(ToolSpec.builder("ping").build(), args -> ToolResult.ok("pong")));
        ToolResult r = executor.execute("ping", null);
        assertTrue(r.isSuccess());
        assertEquals("pong", r.getData());
    }

    @Test
    void execute_toolThrows_returnsToolExceptionError() {
        registry.register(tool(ToolSpec.builder("boom").build(), args -> {
            throw new RuntimeException("kaboom");
        }));
        ToolResult r = executor.execute("boom", new HashMap<>());
        assertEquals(ToolExecutor.ERR_TOOL_EXCEPTION, r.getErrorCode());
        assertTrue(r.getMessage().contains("kaboom"));
    }

    @Test
    void execute_toolReturnsNull_returnsNullResultError() {
        registry.register(tool(ToolSpec.builder("nullish").build(), args -> null));
        assertEquals(ToolExecutor.ERR_NULL_RESULT, executor.execute("nullish", new HashMap<>()).getErrorCode());
    }

    @Test
    void execute_gatedToolApproved_runsNormally() {
        registry.register(tool(ToolSpec.builder("delete_element").build(), args -> ToolResult.ok("deleted")));
        ToolExecutor gated = new ToolExecutor(registry, Collections.singleton("delete_element"),
                (toolName, args) -> true);

        ToolResult r = gated.execute("delete_element", new HashMap<>());

        assertTrue(r.isSuccess());
        assertEquals("deleted", r.getData());
    }

    @Test
    void execute_gatedToolDeclined_returnsDeclinedErrorWithoutRunningTheTool() {
        boolean[] ran = {false};
        registry.register(tool(ToolSpec.builder("delete_element").build(), args -> {
            ran[0] = true;
            return ToolResult.ok("deleted");
        }));
        ToolExecutor gated = new ToolExecutor(registry, Collections.singleton("delete_element"),
                (toolName, args) -> false);

        ToolResult r = gated.execute("delete_element", new HashMap<>());

        assertFalse(r.isSuccess());
        assertEquals(ToolExecutor.ERR_DECLINED, r.getErrorCode());
        assertFalse(ran[0]);
    }

    @Test
    void execute_ungatedTool_ignoresGateEvenWhenPresent() {
        registry.register(tool(ToolSpec.builder("get_tree_state").build(), args -> ToolResult.ok("tree")));
        ToolExecutor gated = new ToolExecutor(registry, Collections.singleton("delete_element"),
                (toolName, args) -> false);

        ToolResult r = gated.execute("get_tree_state", new HashMap<>());

        assertTrue(r.isSuccess());
    }

    @Test
    void execute_gatedToolWithNullGate_runsWithoutConfirmation() {
        registry.register(tool(ToolSpec.builder("delete_element").build(), args -> ToolResult.ok("deleted")));
        ToolExecutor noGate = new ToolExecutor(registry, Collections.singleton("delete_element"), null);

        ToolResult r = noGate.execute("delete_element", new HashMap<>());

        assertTrue(r.isSuccess());
    }
}
