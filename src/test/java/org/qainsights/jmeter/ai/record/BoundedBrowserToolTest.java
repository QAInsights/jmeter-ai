package org.qainsights.jmeter.ai.record;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.tool.ParamType;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolParameter;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BoundedBrowserTool}.
 * <p>
 * Regression cover for a recording that died with "prompt is too long: 3653143 tokens >
 * 1000000 maximum": page snapshots entered the conversation unbounded and every turn is
 * re-sent on the next request.
 */
class BoundedBrowserToolTest {

    private static Tool stub(ToolResult result) {
        ToolSpec spec = ToolSpec.builder("browser_snapshot")
                .description("Capture the page")
                .addParameter(ToolParameter.builder("selector", ParamType.STRING).build())
                .build();
        return new Tool() {
            @Override
            public ToolSpec getSpec() {
                return spec;
            }

            @Override
            public ToolResult execute(Map<String, Object> arguments) {
                return result;
            }
        };
    }

    private static String repeat(char c, int times) {
        StringBuilder sb = new StringBuilder(times);
        for (int i = 0; i < times; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    @Test
    void should_capOversizedOutput() {
        String huge = repeat('x', 500_000);
        BoundedBrowserTool tool = new BoundedBrowserTool(stub(ToolResult.ok(huge)), 1000);

        ToolResult result = tool.execute(Map.of());

        assertTrue(result.isSuccess());
        assertTrue(result.getData().length() < 2000,
                "a 500KB snapshot must not reach the model verbatim");
        assertTrue(result.getData().startsWith(repeat('x', 1000)),
                "the retained portion should be the head, where MCP puts page metadata");
    }

    @Test
    void should_tellTheModelTheViewIsPartial() {
        // A silent cut would let the model conclude a missing element does not exist.
        BoundedBrowserTool tool = new BoundedBrowserTool(stub(ToolResult.ok(repeat('y', 5000))), 100);

        String data = tool.execute(Map.of()).getData();

        assertTrue(data.contains("partial view"), "the truncation must be self-describing");
        assertTrue(data.contains("4900"), "the omitted count tells the model how much is hidden");
    }

    @Test
    void should_passThroughOutputWithinBudget() {
        BoundedBrowserTool tool = new BoundedBrowserTool(stub(ToolResult.ok("small tree")), 1000);

        ToolResult result = tool.execute(Map.of());

        assertEquals("small tree", result.getData(), "short results must be untouched");
    }

    @Test
    void should_passThroughOutputExactlyAtBudget() {
        String exact = repeat('z', 100);
        BoundedBrowserTool tool = new BoundedBrowserTool(stub(ToolResult.ok(exact)), 100);

        assertEquals(exact, tool.execute(Map.of()).getData(), "the boundary must not truncate");
    }

    @Test
    void should_preserveErrorsUntouched() {
        // Errors are short and are exactly what the model self-corrects from.
        ToolResult error = ToolResult.error("mcp_tool_failed", "Element not found");
        BoundedBrowserTool tool = new BoundedBrowserTool(stub(error), 5);

        ToolResult result = tool.execute(Map.of());

        assertFalse(result.isSuccess());
        assertEquals("mcp_tool_failed", result.getErrorCode());
        assertEquals("Element not found", result.getMessage());
    }

    @Test
    void should_preserveTheDelegateSpec() {
        // The spec is what the model sees; wrapping must be invisible to it.
        Tool delegate = stub(ToolResult.ok("ok"));
        BoundedBrowserTool tool = new BoundedBrowserTool(delegate);

        assertSame(delegate.getSpec(), tool.getSpec());
    }

    @Test
    void should_keepWorstCaseContextWithinAModelWindow() {
        // The guard rail that matters: the default budget must keep a long recording inside
        // a 1M-token window. ~4 chars per token, 60 agent iterations.
        long worstCaseChars = (long) BoundedBrowserTool.DEFAULT_MAX_CHARS * 60;
        long approxTokens = worstCaseChars / 4;

        assertTrue(approxTokens < 500_000,
                "default budget would still risk exhausting the context: ~" + approxTokens);
    }

    @Test
    void should_rejectInvalidConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedBrowserTool(null));
        assertThrows(IllegalArgumentException.class,
                () -> new BoundedBrowserTool(stub(ToolResult.ok("x")), 0));
    }

    @Test
    void should_tolerateNullData() {
        BoundedBrowserTool tool = new BoundedBrowserTool(stub(ToolResult.ok(null)), 10);

        assertTrue(tool.execute(Map.of()).isSuccess());
    }
}
