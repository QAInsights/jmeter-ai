package org.qainsights.jmeter.ai.agent.tool.handlers;

import java.util.Map;
import java.util.function.Supplier;

import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.qainsights.jmeter.ai.agent.jmeter.TestPlanRunner;
import org.qainsights.jmeter.ai.agent.jmeter.TestResultsRunner;
import org.qainsights.jmeter.ai.agent.jmeter.TestRunController;
import org.qainsights.jmeter.ai.agent.jmeter.TestRunSummary;
import org.qainsights.jmeter.ai.agent.tool.ParamType;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolParameter;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;

/**
 * The {@code get_test_results} agent tool. Unlike {@code run_test} (which dispatches
 * JMeter's own Start action and returns immediately, leaving results to whatever
 * listener happens to be in the plan), this runs a private embedded engine (see
 * {@link TestPlanRunner}) with its own listener pair, blocks until the run finishes or
 * times out, and reports pass/fail counts and failure details directly.
 * <p>
 * Runs the plan's Thread Groups exactly as configured (no forced thread/loop
 * overrides) - a real run, which may take as long as the plan is configured for, hence
 * the {@code timeout_seconds} bound.
 */
public final class GetTestResultsHandler {

    public static final String GET_TEST_RESULTS = "get_test_results";

    public static final String ERR_NO_TEST_PLAN = "no_test_plan";
    public static final String ERR_ALREADY_RUNNING = "already_running";
    public static final String ERR_EXECUTION_FAILED = "execution_failed";

    static final long DEFAULT_TIMEOUT_SECONDS = 300;
    static final long MAX_TIMEOUT_SECONDS = 3600;

    private final Supplier<JMeterTreeNode> rootSupplier;
    private final TestRunController controller;
    private final TestResultsRunner runner;

    /** Production constructor wiring the live JMeter tree, run controller and engine runner. */
    public GetTestResultsHandler() {
        this(ReadToolHandlers.guiPackageTree()::getRoot, TestRunController.live(), TestResultsRunner.live());
    }

    public GetTestResultsHandler(Supplier<JMeterTreeNode> rootSupplier, TestRunController controller,
                                  TestResultsRunner runner) {
        this.rootSupplier = rootSupplier;
        this.controller = controller;
        this.runner = runner;
    }

    public Tool tool() {
        ToolSpec spec = ToolSpec.builder(GET_TEST_RESULTS)
                .description("Runs the current test plan in a private embedded engine (independent of whatever "
                        + "listeners are already in the plan) and blocks until it finishes or times out, then "
                        + "reports back total/passed/failed sample counts and up to " + TestRunSummary.MAX_FAILURES_KEPT
                        + " failure details directly. Runs Thread Groups exactly as configured (no forced "
                        + "thread/loop overrides) - unlike run_test, this does not return immediately, so use it "
                        + "when you need actual pass/fail results back rather than just triggering a run. Fails "
                        + "if a test (started via this tool or run_test) is already in progress - call "
                        + "stop_test first.")
                .addParameter(ToolParameter.builder("timeout_seconds", ParamType.INTEGER)
                        .description("Max seconds to wait for the run to finish (default " + DEFAULT_TIMEOUT_SECONDS
                                + ", max " + MAX_TIMEOUT_SECONDS + "). If exceeded, the run is stopped and "
                                + "whatever results were collected so far are reported, flagged as timed out.")
                        .build())
                .addPrecondition("A test plan must be open (see get_tree_state)")
                .addPrecondition("no other test may currently be running (see stop_test)")
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
        if (controller.isRunning()) {
            return ToolResult.error(ERR_ALREADY_RUNNING,
                    "A test is already running. Use stop_test to stop it before requesting results.");
        }

        long timeoutSeconds = clampTimeout(integer(args.get("timeout_seconds")));

        TestRunSummary summary;
        try {
            summary = runner.run(timeoutSeconds);
        } catch (TestPlanRunner.TestExecutionException e) {
            return ToolResult.error(ERR_EXECUTION_FAILED, "Could not run the test plan: " + e.getMessage());
        }

        return ToolResult.ok(format(summary));
    }

    private static long clampTimeout(Long requested) {
        long timeout = requested == null ? DEFAULT_TIMEOUT_SECONDS : requested;
        if (timeout < 1) {
            timeout = 1;
        }
        return Math.min(timeout, MAX_TIMEOUT_SECONDS);
    }

    private static String format(TestRunSummary summary) {
        StringBuilder sb = new StringBuilder();
        sb.append(summary.isTimedOut() ? "Test run timed out after " : "Test run finished in ")
                .append(String.format("%.1f", summary.getElapsedMillis() / 1000.0)).append("s. ")
                .append("Total samples: ").append(summary.getTotalSamples())
                .append(", passed: ").append(summary.getSuccessCount())
                .append(", failed: ").append(summary.getFailureCount()).append(".");
        if (!summary.getFailures().isEmpty()) {
            sb.append(" Failures:");
            for (TestRunSummary.Failure failure : summary.getFailures()) {
                sb.append("\n- ").append(failure.getLabel());
                if (!failure.getResponseCode().isEmpty()) {
                    sb.append(" [").append(failure.getResponseCode()).append("]");
                }
                if (!failure.getMessage().isEmpty()) {
                    sb.append(": ").append(failure.getMessage());
                }
            }
            if (summary.hasMoreFailuresThanShown()) {
                sb.append("\n- ... and ").append(summary.getFailureCount() - summary.getFailures().size())
                        .append(" more failure(s) not shown.");
            }
        }
        return sb.toString();
    }

    private static Long integer(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
