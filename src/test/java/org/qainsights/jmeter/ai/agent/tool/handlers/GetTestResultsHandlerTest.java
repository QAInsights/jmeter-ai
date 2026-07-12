package org.qainsights.jmeter.ai.agent.tool.handlers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.samplers.SampleResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.jmeter.TestPlanRunner;
import org.qainsights.jmeter.ai.agent.jmeter.TestResultsRunner;
import org.qainsights.jmeter.ai.agent.jmeter.TestRunController;
import org.qainsights.jmeter.ai.agent.jmeter.TestRunSummary;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link GetTestResultsHandler} using fake run-controller/runner seams. */
class GetTestResultsHandlerTest {

    /** Records the requested timeout and returns a canned summary (or throws). */
    private static final class FakeRunner implements TestResultsRunner {
        Long lastTimeoutSeconds;
        TestRunSummary toReturn = TestRunSummary.of(new ArrayList<>(), false, 0L);
        TestPlanRunner.TestExecutionException toThrow;

        @Override
        public TestRunSummary run(long timeoutSeconds) {
            this.lastTimeoutSeconds = timeoutSeconds;
            if (toThrow != null) {
                throw toThrow;
            }
            return toReturn;
        }
    }

    /** Fake run controller reporting a fixed running state; dispatch is unused here. */
    private static final class FakeController implements TestRunController {
        boolean running = false;

        @Override
        public boolean dispatch(String actionName) {
            return true;
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }

    private JMeterTreeNode root;
    private FakeController controller;
    private FakeRunner runner;
    private Tool tool;

    @BeforeEach
    void setUp() {
        ConfigTestElement element = new ConfigTestElement();
        element.setName("Test Plan");
        root = new JMeterTreeNode(element, null);
        controller = new FakeController();
        runner = new FakeRunner();
        tool = new GetTestResultsHandler(() -> root, controller, runner).tool();
    }

    private static Map<String, Object> args(Object timeoutSeconds) {
        Map<String, Object> map = new HashMap<>();
        map.put("timeout_seconds", timeoutSeconds);
        return map;
    }

    @Test
    void spec_declaresName_andNoRequiredParameters() {
        assertEquals(GetTestResultsHandler.GET_TEST_RESULTS, tool.getSpec().getName());
        assertTrue(tool.getSpec().getRequiredParameters().isEmpty());
    }

    @Test
    void run_noTestPlan_returnsErrorWithoutDelegating() {
        Tool noPlan = new GetTestResultsHandler(() -> null, controller, runner).tool();
        ToolResult r = noPlan.execute(args(null));

        assertFalse(r.isSuccess());
        assertEquals(GetTestResultsHandler.ERR_NO_TEST_PLAN, r.getErrorCode());
        assertNull(runner.lastTimeoutSeconds);
    }

    @Test
    void run_alreadyRunning_returnsErrorWithoutDelegating() {
        controller.running = true;
        ToolResult r = tool.execute(args(null));

        assertFalse(r.isSuccess());
        assertEquals(GetTestResultsHandler.ERR_ALREADY_RUNNING, r.getErrorCode());
        assertNull(runner.lastTimeoutSeconds);
    }

    @Test
    void run_noTimeoutGiven_usesDefault() {
        tool.execute(args(null));
        assertEquals(GetTestResultsHandler.DEFAULT_TIMEOUT_SECONDS, runner.lastTimeoutSeconds);
    }

    @Test
    void run_customTimeout_isPassedThrough() {
        tool.execute(args(60));
        assertEquals(60L, runner.lastTimeoutSeconds);
    }

    @Test
    void run_timeoutAboveMax_isClampedToMax() {
        tool.execute(args(GetTestResultsHandler.MAX_TIMEOUT_SECONDS + 1000));
        assertEquals(GetTestResultsHandler.MAX_TIMEOUT_SECONDS, runner.lastTimeoutSeconds);
    }

    @Test
    void run_timeoutBelowOne_isClampedToOne() {
        tool.execute(args(0));
        assertEquals(1L, runner.lastTimeoutSeconds);
    }

    @Test
    void run_successfulSummary_reportsCountsInMessage() {
        runner.toReturn = TestRunSummary.of(sampleResults(3, 1), false, 2500L);

        ToolResult r = tool.execute(args(null));

        assertTrue(r.isSuccess());
        assertTrue(r.getData().contains("Total samples: 4"));
        assertTrue(r.getData().contains("passed: 3"));
        assertTrue(r.getData().contains("failed: 1"));
        assertTrue(r.getData().contains("2.5s"));
    }

    @Test
    void run_summaryWithFailures_listsFailureDetails() {
        SampleResult failed = new SampleResult();
        failed.setSampleLabel("Broken Sampler");
        failed.setSuccessful(false);
        failed.setResponseCode("500");
        failed.setResponseMessage("Internal Server Error");
        runner.toReturn = TestRunSummary.of(List.of(failed), false, 10L);

        ToolResult r = tool.execute(args(null));

        assertTrue(r.isSuccess());
        assertTrue(r.getData().contains("Broken Sampler"));
        assertTrue(r.getData().contains("500"));
        assertTrue(r.getData().contains("Internal Server Error"));
    }

    @Test
    void run_timedOutSummary_mentionsTimeout() {
        runner.toReturn = TestRunSummary.of(new ArrayList<>(), true, 1000L);

        ToolResult r = tool.execute(args(null));

        assertTrue(r.isSuccess());
        assertTrue(r.getData().contains("timed out"));
    }

    @Test
    void run_whenRunnerThrows_returnsExecutionFailedError() {
        runner.toThrow = new TestPlanRunner.TestExecutionException("no engine available");

        ToolResult r = tool.execute(args(null));

        assertFalse(r.isSuccess());
        assertEquals(GetTestResultsHandler.ERR_EXECUTION_FAILED, r.getErrorCode());
        assertTrue(r.getMessage().contains("no engine available"));
    }

    private static List<SampleResult> sampleResults(int successCount, int failureCount) {
        List<SampleResult> results = new ArrayList<>();
        for (int i = 0; i < successCount; i++) {
            SampleResult result = new SampleResult();
            result.setSampleLabel("Success " + i);
            result.setSuccessful(true);
            results.add(result);
        }
        for (int i = 0; i < failureCount; i++) {
            SampleResult result = new SampleResult();
            result.setSampleLabel("Failure " + i);
            result.setSuccessful(false);
            results.add(result);
        }
        return results;
    }
}
