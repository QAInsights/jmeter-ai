package org.qainsights.jmeter.ai.agent.jmeter;

import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.sampler.DebugSampler;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for {@link TestPlanRunner#runTree}: builds a minimal, real JMeter
 * test plan (Thread Group + {@link DebugSampler}, no network, no GUI) and runs it
 * through a real {@link org.apache.jmeter.engine.StandardJMeterEngine}, headless -
 * exercising the same tree-cloning/convertSubTree/listener-attachment/latch wiring
 * {@code get_test_results} depends on via {@link TestResultsRunner#live()}.
 */
class TestPlanRunnerTest {

    /**
     * {@code StandardJMeterEngine} reads {@code JMeterUtils.getJMeterProperties()} during a
     * run; outside a live JMeter process (which loads its own jmeter.properties at startup)
     * that static field is never initialized, causing an NPE. Falls back to the classpath
     * default (bundled in ApacheJMeter_core.jar) since no real properties file is needed for
     * this headless, no-network test plan.
     */
    @BeforeAll
    static void initJMeterProperties() {
        if (JMeterUtils.getJMeterProperties() == null) {
            JMeterUtils.loadJMeterProperties("nonexistent.properties");
        }
    }

    private static HashTree planWithLoops(int loops) {
        TestPlan testPlan = new TestPlan("Test Plan");

        LoopController loopController = new LoopController();
        loopController.setLoops(loops);
        loopController.setFirst(true);
        loopController.initialize();

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("Thread Group");
        threadGroup.setNumThreads(1);
        threadGroup.setRampUp(1);
        threadGroup.setSamplerController(loopController);

        DebugSampler sampler = new DebugSampler();
        sampler.setName("Debug Sampler");

        HashTree tree = new ListedHashTree();
        HashTree planTree = tree.add(testPlan);
        HashTree threadGroupTree = planTree.add(threadGroup);
        threadGroupTree.add(sampler);
        return tree;
    }

    @Test
    @Timeout(30)
    void runTree_minimalPlan_collectsOneSuccessfulSample() {
        TestPlanRunner runner = new TestPlanRunner();

        TestRunSummary summary = runner.runTree(planWithLoops(1), 20);

        assertFalse(summary.isTimedOut());
        assertEquals(1, summary.getTotalSamples());
        assertEquals(1, summary.getSuccessCount());
        assertEquals(0, summary.getFailureCount());
        assertTrue(summary.getFailures().isEmpty());
        assertTrue(summary.getElapsedMillis() >= 0);
    }

    @Test
    @Timeout(30)
    void runTree_multipleLoops_collectsOneSamplePerLoop() {
        TestPlanRunner runner = new TestPlanRunner();

        TestRunSummary summary = runner.runTree(planWithLoops(3), 20);

        assertFalse(summary.isTimedOut());
        assertEquals(3, summary.getTotalSamples());
        assertEquals(3, summary.getSuccessCount());
        assertEquals(0, summary.getFailureCount());
    }
}
