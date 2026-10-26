package org.qainsights.jmeter.ai.agent.jmeter;

import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.sampler.DebugSampler;
import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.TestElement;
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

    private static HashTree planWithSampler(Sampler sampler) {
        TestPlan testPlan = new TestPlan("Test Plan");
        LoopController loopController = new LoopController();
        loopController.setLoops(1);
        loopController.setFirst(true);
        loopController.initialize();
        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName("Thread Group");
        threadGroup.setNumThreads(1);
        threadGroup.setRampUp(0);
        threadGroup.setSamplerController(loopController);

        HashTree tree = new ListedHashTree();
        tree.add(testPlan).add(threadGroup).add(sampler);
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

    @Test
    @Timeout(30)
    void takeLastSampleResultsClearsStaticRetention() {
        TestRunSummary summary = new TestPlanRunner().runTree(planWithLoops(2), 20);
        assertEquals(2, summary.getTotalSamples());

        assertEquals(2, TestPlanRunner.takeLastSampleResults().size());
        assertTrue(TestPlanRunner.takeLastSampleResults().isEmpty());
    }

    @Test
    void sampleCollectorCountsAllSamplesAndRetainsLateFailures() throws Exception {
        TestPlanRunner.SampleCollector.prepare();
        for (int index = 0; index < 5000; index++) {
            SampleResult sample;
            if (index == 4999) {
                HTTPSampleResult softFailure = new HTTPSampleResult();
                softFailure.setContentType("text/html");
                softFailure.setResponseData("<title>Not Found</title>", "UTF-8");
                sample = softFailure;
            } else {
                sample = new SampleResult();
            }
            sample.setSampleLabel("Sample " + index);
            boolean hardFailure = index < 2;
            sample.setSuccessful(!hardFailure);
            sample.setResponseCode(index < 2 ? "500" : "200");
            TestPlanRunner.SampleCollector.recordSample(sample);
        }

        TestPlanRunner.CollectedSamples collected = TestPlanRunner.SampleCollector.collected();
        TestRunSummary summary = TestRunSummary.of(collected.results(), collected.totalSamples(),
                collected.successCount(), collected.failureCount(), false, 0);

        assertEquals(1000, collected.results().size());
        assertEquals(5000, collected.totalSamples());
        assertEquals(4997, collected.successCount());
        assertEquals(3, collected.failureCount());
        assertEquals(5000, summary.getTotalSamples());
        assertEquals(4997, summary.getSuccessCount());
        assertEquals(3, summary.getFailureCount());
        assertEquals(3, summary.getFailures().size());
        assertTrue(summary.getFailures().stream().anyMatch(failure -> failure.getLabel().equals("Sample 0")));
        assertTrue(summary.getFailures().stream().anyMatch(failure -> failure.getLabel().equals("Sample 1")));
        assertTrue(summary.getFailures().stream().anyMatch(failure -> failure.getLabel().equals("Sample 4999")));
        assertFalse(summary.hasMoreFailuresThanShown());
    }

    @Test
    @Timeout(30)
    void runTree_singleIterationOverridesOnlyTheExecutionTree() {
        HashTree plan = planWithLoops(3);
        LoopController original = originalController(plan);

        TestRunSummary summary = new TestPlanRunner().runTree(plan, 20, true);

        assertEquals(1, summary.getTotalSamples());
        assertEquals(3, original.getLoops());
    }

    @Test
    @Timeout(30)
    void runTree_twoThreadsAndTwoLoopsCollectsFourSamples() {
        HashTree plan = planWithLoops(1);

        TestRunSummary summary = new TestPlanRunner().runTree(plan, 20, 2, 2);

        assertEquals(4, summary.getTotalSamples());
        assertEquals(4, summary.getSuccessCount());
    }

    @Test
    void forceIterationsRejectsUnsupportedThreadGroupTypes() {
        org.apache.jmeter.threads.AbstractThreadGroup pluginGroup =
                org.mockito.Mockito.mock(org.apache.jmeter.threads.AbstractThreadGroup.class);
        HashTree tree = new ListedHashTree();
        tree.add(pluginGroup);

        TestPlanRunner.TestExecutionException error = assertThrows(
                TestPlanRunner.TestExecutionException.class,
                () -> TestPlanRunner.forceIterations(tree, 2, 2));

        assertTrue(error.getMessage().contains(pluginGroup.getClass().getName()));
        assertTrue(error.getMessage().contains("Make it pass only supports standard Thread Groups"));
    }

    @Test
    void prepareExecutionTreeStripsUserListenersWithoutChangingLiveTree() {
        HashTree liveTree = planWithLoops(1);
        HashTree planChildren = liveTree.getTree(liveTree.list().iterator().next());
        ResultCollector resultCollector = new ResultCollector();
        resultCollector.setName("User JTL");
        resultCollector.setProperty(TestElement.GUI_CLASS,
                "org.apache.jmeter.visualizers.ViewResultsFullVisualizer");
        planChildren.add(resultCollector);
        TestPlanRunner.SampleCollector visualizerBacked = new TestPlanRunner.SampleCollector();
        visualizerBacked.setName("Visualizer-backed element");
        visualizerBacked.setProperty(TestElement.GUI_CLASS,
                "org.apache.jmeter.visualizers.ViewResultsFullVisualizer");
        planChildren.add(visualizerBacked);

        HashTree execTree = TestPlanRunner.prepareExecutionTree(liveTree, 0, 0);

        assertFalse(containsElement(execTree, element -> element instanceof ResultCollector));
        assertFalse(containsElement(execTree, element ->
                "org.apache.jmeter.visualizers.ViewResultsFullVisualizer"
                        .equals(element.getPropertyAsString(TestElement.GUI_CLASS))));
        assertTrue(containsElement(liveTree, element -> element == resultCollector));
        assertTrue(containsElement(liveTree, element -> element == visualizerBacked));
    }

    private static LoopController originalController(HashTree plan) {
        Object testPlan = plan.list().iterator().next();
        HashTree planChildren = plan.getTree(testPlan);
        Object group = planChildren.list().iterator().next();
        ThreadGroup threadGroup = (ThreadGroup) group;
        return (LoopController) threadGroup.getSamplerController();
    }

    private static boolean containsElement(HashTree tree,
            java.util.function.Predicate<TestElement> predicate) {
        if (tree == null) {
            return false;
        }
        for (Object key : tree.list()) {
            if (key instanceof TestElement element && predicate.test(element)) {
                return true;
            }
            if (containsElement(tree.getTree(key), predicate)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @Timeout(30)
    void stopActive_endsARunningToolRunEarly() throws Exception {
        assertFalse(TestPlanRunner.stopActive(false), "nothing to stop before a run");
        TestPlanRunner runner = new TestPlanRunner();
        java.util.concurrent.CompletableFuture<TestRunSummary> run =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> runner.runTree(planWithLoops(50_000_000), 25));
        long deadline = System.currentTimeMillis() + 10_000;
        boolean requested = false;
        while (!requested && System.currentTimeMillis() < deadline) {
            requested = TestPlanRunner.stopActive(true);
            if (!requested) {
                Thread.sleep(10);
            }
        }
        assertTrue(requested, "the private engine was registered as active");
        TestRunSummary summary = run.get(15, java.util.concurrent.TimeUnit.SECONDS);
        assertFalse(summary.isTimedOut());
        assertTrue(summary.getTotalSamples() < 50_000_000);
        assertFalse(TestPlanRunner.stopActive(false), "the engine is cleared once the run ends");
    }

    @Test
    @Timeout(30)
    void runTree_timeoutStopsALongRun() {
        long before = System.currentTimeMillis();
        TestRunSummary summary = new TestPlanRunner().runTree(planWithLoops(50_000_000), 1);
        assertTrue(summary.isTimedOut());
        assertTrue(System.currentTimeMillis() - before < 15_000);
    }

    @Test
    @Timeout(35)
    void timeoutKeepsUnstoppedEngineRegisteredAndRejectsAnotherRun() throws Exception {
        java.util.concurrent.CountDownLatch samplerStarted = new java.util.concurrent.CountDownLatch(1);
        InterruptIgnoringSampler.started = samplerStarted;
        java.util.concurrent.CompletableFuture<TestRunSummary> run =
                java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> new TestPlanRunner().runTree(planWithSampler(new InterruptIgnoringSampler()), 1));

        assertTrue(samplerStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
        TestRunSummary summary = run.get(20, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(summary.isTimedOut());

        TestPlanRunner.TestExecutionException error = assertThrows(
                TestPlanRunner.TestExecutionException.class,
                () -> new TestPlanRunner().runTree(planWithLoops(1), 5));
        assertTrue(error.getMessage().contains("The previous check run is still stopping"));

        long deadline = System.currentTimeMillis() + 10_000;
        boolean nextRunCompleted = false;
        while (!nextRunCompleted && System.currentTimeMillis() < deadline) {
            try {
                TestRunSummary next = new TestPlanRunner().runTree(planWithLoops(1), 5);
                assertEquals(1, next.getTotalSamples());
                nextRunCompleted = true;
            } catch (TestPlanRunner.TestExecutionException ignored) {
                Thread.sleep(50);
            }
        }
        assertTrue(nextRunCompleted, "a new run is accepted after the previous engine ends");
    }

    public static final class InterruptIgnoringSampler extends AbstractSampler {
        private static final long RUN_MILLIS = 15_000;
        private static volatile java.util.concurrent.CountDownLatch started;

        @Override
        public SampleResult sample(Entry entry) {
            java.util.concurrent.CountDownLatch current = started;
            if (current != null) {
                current.countDown();
            }
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(RUN_MILLIS);
            while (System.nanoTime() < deadline) {
                long remaining = deadline - System.nanoTime();
                try {
                    java.util.concurrent.TimeUnit.NANOSECONDS.sleep(remaining);
                } catch (InterruptedException ignored) {
                    // Simulate a sampler that cannot be stopped by interruption.
                }
            }
            SampleResult result = SampleResult.createTestSample(1);
            result.setSampleLabel("Interrupt-ignoring sampler");
            return result;
        }
    }
}
