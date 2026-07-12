package org.qainsights.jmeter.ai.agent.tool.handlers;

import java.util.Map;
import java.util.function.Supplier;

import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.qainsights.jmeter.ai.agent.jmeter.TestRunController;
import org.qainsights.jmeter.ai.agent.tool.ParamType;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolParameter;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;

/**
 * The {@code stop_test} agent tool. Triggers the same
 * {@code ActionNames.ACTION_STOP}/{@code ACTION_SHUTDOWN} that JMeter's own
 * "Stop"/"Shutdown" toolbar buttons use. If nothing is currently running,
 * this is a silent no-op - JMeter itself doesn't report an error in that
 * case, and there is no public API to check "is a test running" from outside
 * the GUI's own {@code Start} action.
 */
public final class StopTestHandler {

    public static final String STOP_TEST = "stop_test";

    public static final String ERR_NO_TEST_PLAN = "no_test_plan";
    public static final String ERR_DISPATCH_FAILED = "dispatch_failed";

    private final Supplier<JMeterTreeNode> rootSupplier;
    private final TestRunController controller;

    /** Production constructor wiring the live JMeter tree and {@code ActionRouter}. */
    public StopTestHandler() {
        this(ReadToolHandlers.guiPackageTree()::getRoot, TestRunController.live());
    }

    public StopTestHandler(Supplier<JMeterTreeNode> rootSupplier, TestRunController controller) {
        this.rootSupplier = rootSupplier;
        this.controller = controller;
    }

    public Tool tool() {
        ToolSpec spec = ToolSpec.builder(STOP_TEST)
                .description("Stops the currently running test - the same action as JMeter's own 'Stop'/"
                        + "'Shutdown' toolbar buttons. If nothing is running, this is a silent no-op.")
                .addParameter(ToolParameter.builder("force", ParamType.BOOLEAN)
                        .description("false (default): graceful Stop, waits for in-flight samples to finish. "
                                + "true: Shutdown, interrupts in-flight samples immediately.")
                        .build())
                .addPrecondition("A test plan must be open (see get_tree_state)")
                .build();

        return new Tool() {
            @Override
            public ToolSpec getSpec() {
                return spec;
            }

            @Override
            public ToolResult execute(Map<String, Object> arguments) {
                return handle(arguments);
            }
        };
    }

    private ToolResult handle(Map<String, Object> args) {
        if (rootSupplier.get() == null) {
            return ToolResult.error(ERR_NO_TEST_PLAN, "No test plan is currently open.");
        }
        boolean force = parseBoolean(args.get("force"));
        String actionName = force ? ActionNames.ACTION_SHUTDOWN : ActionNames.ACTION_STOP;
        if (!controller.dispatch(actionName)) {
            return ToolResult.error(ERR_DISPATCH_FAILED, "Could not stop the test - no live JMeter GUI available.");
        }
        return ToolResult.ok(force
                ? "Shutdown requested (in-flight samples interrupted immediately). If nothing was running, "
                        + "this had no effect."
                : "Stop requested (waits for in-flight samples to finish). If nothing was running, this had "
                        + "no effect.");
    }

    private static boolean parseBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value).trim());
    }
}
