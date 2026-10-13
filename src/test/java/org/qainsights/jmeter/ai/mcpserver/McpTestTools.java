package org.qainsights.jmeter.ai.mcpserver;

import org.qainsights.jmeter.ai.agent.tool.ParamType;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolParameter;
import org.qainsights.jmeter.ai.agent.tool.ToolRegistry;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Fake tools standing in for the live-tree Agent handlers. */
final class McpTestTools {

    final List<String> calls = new CopyOnWriteArrayList<>();
    final ToolRegistry registry = new ToolRegistry();
    final CountDownLatch started = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);

    McpTestTools() {
        add(ToolSpec.builder("get_tree_state").description("Returns the tree.").build(),
                args -> ToolResult.ok("Test Plan"));
        add(ToolSpec.builder("delete_element").description("Deletes an element.")
                        .addParameter(ToolParameter.builder("element_id", ParamType.STRING)
                                .description("Tree path").required(true).build())
                        .build(),
                args -> ToolResult.ok("deleted " + args.get("element_id")));
        add(ToolSpec.builder("echo").description("Echoes text.")
                        .addParameter(ToolParameter.builder("text", ParamType.STRING).required(true).build())
                        .addParameter(ToolParameter.builder("count", ParamType.INTEGER).build())
                        .addPrecondition("A plan is open")
                        .build(),
                args -> "fail".equals(args.get("text"))
                        ? ToolResult.error("bad_text", "Text was fail")
                        : ToolResult.ok(args.get("text") + " x" + args.get("count")));
        add(ToolSpec.builder("stop_test").description("Stops the run.").build(), args -> ToolResult.ok("stopped"));
        add(ToolSpec.builder("get_test_results").description("Runs the plan and waits.").build(), args -> {
            started.countDown();
            try {
                return release.await(10, TimeUnit.SECONDS) ? ToolResult.ok("done") : ToolResult.error("timeout", "x");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.error("interrupted", "x");
            }
        });
    }

    private void add(ToolSpec spec, Function<Map<String, Object>, ToolResult> body) {
        registry.register(new Tool() {
            @Override
            public ToolSpec getSpec() {
                return spec;
            }

            @Override
            public ToolResult execute(Map<String, Object> arguments) {
                calls.add(spec.getName());
                return body.apply(arguments);
            }
        });
    }
}
