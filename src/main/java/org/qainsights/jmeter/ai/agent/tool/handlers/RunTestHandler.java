package org.qainsights.jmeter.ai.agent.tool.handlers;

import java.util.Map;
import java.util.function.Supplier;

import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.qainsights.jmeter.ai.agent.jmeter.TestRunController;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;

/**
 * The {@code run_test} agent tool. Triggers the exact same
 * {@code ActionNames.ACTION_START} that JMeter's own "Start" toolbar button
 * uses, so the test runs with whatever listeners (View Results Tree, Summary
 * Report, ...) are already in the plan. This dispatches the action and
 * returns immediately - it does not wait for the test to finish or collect
 * results itself; check the plan's listeners for outcomes, and use
 * {@code stop_test} to stop it.
 */
public final class RunTestHandler {

    public static final String RUN_TEST = "run_test";

    public static final String ERR_NO_TEST_PLAN = "no_test_plan";
    public static final String ERR_DISPATCH_FAILED = "dispatch_failed";

    private final Supplier<JMeterTreeNode> rootSupplier;
    private final TestRunController controller;

    /** Production constructor wiring the live JMeter tree and {@code ActionRouter}. */
    public RunTestHandler() {
        this(ReadToolHandlers.guiPackageTree()::getRoot, TestRunController.live());
    }

    public RunTestHandler(Supplier<JMeterTreeNode> rootSupplier, TestRunController controller) {
        this.rootSupplier = rootSupplier;
        this.controller = controller;
    }

    public Tool tool() {
        ToolSpec spec = ToolSpec.builder(RUN_TEST)
                .description("Starts running the current test plan - the same action as JMeter's own 'Start' "
                        + "toolbar button. Returns immediately once the run is triggered; it does not wait for "
                        + "the test to finish or report pass/fail results itself. Check whatever listeners "
                        + "(View Results Tree, Summary Report, ...) are in the plan for outcomes, and use "
                        + "stop_test to stop it.")
                .addPrecondition("A test plan must be open (see get_tree_state)")
                .build();

        return new Tool() {
            @Override
            public ToolSpec getSpec() {
                return spec;
            }

            @Override
            public ToolResult execute(Map<String, Object> arguments) {
                return handle();
            }
        };
    }

    private ToolResult handle() {
        if (rootSupplier.get() == null) {
            return ToolResult.error(ERR_NO_TEST_PLAN, "No test plan is currently open.");
        }
        if (!controller.dispatch(ActionNames.ACTION_START)) {
            return ToolResult.error(ERR_DISPATCH_FAILED, "Could not start the test - no live JMeter GUI available.");
        }
        return ToolResult.ok("Test started. Check the plan's listeners (View Results Tree, Summary Report, ...) "
                + "for results; use stop_test to stop it.");
    }
}
