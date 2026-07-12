package org.qainsights.jmeter.ai.agent.jmeter;

/**
 * Seam over "run the current test plan to completion and summarize the results".
 * Production code bridges the live {@code GuiPackage} tree model and delegates to
 * {@link TestPlanRunner}; tests supply a fake. Keeps the {@code get_test_results} tool
 * logic unit-testable without a running JMeter GUI or embedded engine.
 */
@FunctionalInterface
public interface TestResultsRunner {

    /**
     * Runs the current test plan and blocks until it finishes or {@code timeoutSeconds}
     * elapses.
     *
     * @param timeoutSeconds max seconds to wait for the run to finish before stopping it
     *                        and reporting whatever was collected so far
     * @return the aggregated pass/fail summary
     * @throws TestPlanRunner.TestExecutionException if the plan could not be run at all
     *                                                (no live GUI/plan, engine failure)
     */
    TestRunSummary run(long timeoutSeconds);

    /** Live runner, backed by {@link TestPlanRunner#run}. */
    static TestResultsRunner live() {
        TestPlanRunner runner = new TestPlanRunner();
        return runner::run;
    }
}
