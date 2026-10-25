package org.qainsights.jmeter.ai.agent.jmeter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.jmeter.JMeter;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.engine.JMeterEngineException;
import org.apache.jmeter.engine.StandardJMeterEngine;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestStateListener;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.qainsights.jmeter.ai.fix.SoftFailureDetector;

/**
 * Runs the current test plan through a private {@link StandardJMeterEngine} - the same
 * embedding pattern {@code CorrelationEngine.replayTestPlan} already uses in this
 * codebase - with a dedicated {@link SampleListener}/{@link TestStateListener} pair to
 * collect every sample result into a {@link TestRunSummary}, independent of whatever
 * listeners (View Results Tree, Summary Report, ...) already happen to be in the plan.
 * <p>
 * Unlike {@code run_test} (which dispatches JMeter's own Start action and returns
 * immediately), {@link #run} blocks until the run finishes or {@code timeoutSeconds}
 * elapses, and runs the plan's Thread Groups exactly as configured (no forced
 * thread/loop overrides).
 */
public final class TestPlanRunner {

    private static volatile StandardJMeterEngine active;
    private static volatile List<SampleResult> lastSampleResults = Collections.emptyList();
    /** Pending stop for the active run: TRUE stops at once, FALSE gracefully, null means keep running. */
    private static volatile Boolean stopRequest;

    /**
     * Stops the run started by {@link #run}, which uses a private engine that JMeter's own Stop
     * button and {@code ActionRouter} cannot reach. Returns false when no such run is active.
     */
    public static boolean stopActive(boolean now) {
        if (active == null) {
            return false;
        }
        stopRequest = now;
        return true;
    }

    public static List<SampleResult> lastSampleResults() {
        return Collections.unmodifiableList(new ArrayList<>(lastSampleResults));
    }

    public static synchronized List<SampleResult> takeLastSampleResults() {
        List<SampleResult> results = lastSampleResults;
        lastSampleResults = Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(results));
    }

    /** Live entry point: pulls the current plan from the live {@link GuiPackage} tree model. */
    public TestRunSummary run(long timeoutSeconds) {
        return run(timeoutSeconds, 0, 0);
    }

    /** Runs the current plan once with one thread per Thread Group. */
    public TestRunSummary runSingleIteration(long timeoutSeconds) {
        return run(timeoutSeconds, 1, 1);
    }

    public TestRunSummary runIterations(long timeoutSeconds, int threads, int loops) {
        return run(timeoutSeconds, threads, loops);
    }

    private TestRunSummary run(long timeoutSeconds, int threads, int loops) {
        GuiPackage gui = GuiPackage.getInstance();
        if (gui == null) {
            throw new TestExecutionException("No live JMeter GUI available.");
        }
        HashTree testPlanTree = gui.getTreeModel().getTestPlan();
        if (testPlanTree == null || testPlanTree.size() == 0) {
            throw new TestExecutionException("No test plan is currently open.");
        }
        return runTree(testPlanTree, timeoutSeconds, threads, loops);
    }

    /**
     * Runs an already-fetched test plan tree through a private engine. Package-visible so
     * it can be exercised directly with a hand-built {@link HashTree} in tests, without a
     * live {@link GuiPackage}. Accepts a tree keyed by either {@code JMeterTreeNode} (as
     * returned by {@code JMeterTreeModel.getTestPlan()}) or plain {@code TestElement} (as
     * returned by {@code SaveService.loadTree}) - {@link JMeter#convertSubTree} handles both.
     */
    synchronized TestRunSummary runTree(HashTree testPlanTree, long timeoutSeconds) {
        return runTree(testPlanTree, timeoutSeconds, 0, 0);
    }

    synchronized TestRunSummary runTree(HashTree testPlanTree, long timeoutSeconds, boolean singleIteration) {
        return runTree(testPlanTree, timeoutSeconds, singleIteration ? 1 : 0, singleIteration ? 1 : 0);
    }

    synchronized TestRunSummary runTree(HashTree testPlanTree, long timeoutSeconds, int threads, int loops) {
        ListedHashTree clonedTree;
        try {
            clonedTree = (ListedHashTree) testPlanTree.clone();
        } catch (RuntimeException e) {
            throw new TestExecutionException("Could not clone the test plan: " + e.getMessage());
        }
        HashTree execTree = JMeter.convertSubTree(clonedTree, true);
        if (threads > 0 && loops > 0) {
            forceIterations(execTree, threads, loops);
        }

        // JMeter reflectively clones per-thread SampleListener/TestStateListener test elements
        // via their no-arg constructor (see AbstractTestElement.clone()) - any instance field set
        // here would not survive that clone. Collector/Ender therefore coordinate via static state,
        // reset per call by prepare(), mirroring CorrelationEngine.Collector/Ender's proven pattern.
        SampleCollector.prepare();
        lastSampleResults = Collections.emptyList();
        CountDownLatch done = new CountDownLatch(1);
        RunEndedListener.prepare(done);
        for (Object key : execTree.list()) {
            HashTree sub = execTree.getTree(key);
            if (sub != null) {
                sub.add(new SampleCollector());
                sub.add(new RunEndedListener());
            }
        }

        StandardJMeterEngine engine = new StandardJMeterEngine();
        long start = System.currentTimeMillis();
        try {
            stopRequest = null;
            engine.configure(execTree);
            active = engine;
            engine.runTest();
        } catch (JMeterEngineException | RuntimeException e) {
            active = null;
            throw new TestExecutionException("Failed to run the test plan: " + e.getMessage());
        }

        // runTest() returns at once, so the timeout and stop_test are honoured while the engine runs.
        long deadline = start + TimeUnit.SECONDS.toMillis(Math.max(1, timeoutSeconds));
        boolean timedOut = false;
        long lastStop = 0;
        try {
            while (!done.await(100, TimeUnit.MILLISECONDS)) {
                long now = System.currentTimeMillis();
                if (now >= deadline) {
                    timedOut = true;
                    engine.stopTest(true);
                    done.await(10, TimeUnit.SECONDS);
                    break;
                }
                Boolean stop = stopRequest;
                if (stop != null && now - lastStop >= 1000) {
                    engine.stopTest(stop);
                    lastStop = now;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            timedOut = true;
            engine.stopTest(true);
        } finally {
            active = null;
            stopRequest = null;
        }
        long elapsed = System.currentTimeMillis() - start;
        CollectedSamples collected = SampleCollector.collected();
        List<SampleResult> results = collected.results();
        lastSampleResults = Collections.unmodifiableList(new ArrayList<>(
                results.subList(0, Math.min(results.size(), 1000))));
        return TestRunSummary.of(results, collected.totalSamples(), collected.successCount(),
                collected.failureCount(), timedOut, elapsed);
    }

    /** Applies the one-user, one-loop override to an execution tree, never the live plan. */
    public static void forceSingleIteration(HashTree execTree) {
        forceIterations(execTree, 1, 1);
    }

    public static void forceIterations(HashTree execTree, int threads, int loops) {
        if (execTree == null) {
            return;
        }
        if (threads < 1 || loops < 1) {
            throw new IllegalArgumentException("threads and loops must be positive");
        }
        for (Object key : execTree.list()) {
            if (key instanceof AbstractThreadGroup && !(key instanceof ThreadGroup)) {
                throw new TestExecutionException(key.getClass().getName()
                        + ": Make it pass only supports standard Thread Groups");
            }
            if (key instanceof ThreadGroup threadGroup) {
                threadGroup.setNumThreads(threads);
                if (threadGroup.getSamplerController() instanceof LoopController) {
                    LoopController loopController = (LoopController) threadGroup.getSamplerController();
                    loopController.setLoops(loops);
                    loopController.setContinueForever(false);
                }
            }
            HashTree children = execTree.getTree(key);
            if (children != null) {
                forceIterations(children, threads, loops);
            }
        }
    }

    /**
     * Collects every top-level sample result fired during the run, via static state shared
     * across every reflectively-cloned instance (see the note in {@link #runTree}). Must be
     * {@code public} - {@link AbstractTestElement#clone()} instantiates the runtime class
     * reflectively via its no-arg constructor, which fails with an {@code IllegalAccessException}
     * against a non-public nested class.
     */
    public static final class SampleCollector extends AbstractTestElement implements SampleListener {
        private static final int MAX_RESULTS_KEPT = 1000;
        private static volatile SampleCollection shared;

        /** Resets the shared results list; call once before each run. */
        static void prepare() {
            shared = new SampleCollection();
        }

        static CollectedSamples collected() {
            SampleCollection current = shared;
            return current == null ? new CollectedSamples(List.of(), 0, 0, 0) : current.snapshot();
        }

        static void recordSample(SampleResult result) {
            SampleCollection current = shared;
            if (current != null && result != null) {
                current.record(result);
            }
        }

        @Override
        public void sampleOccurred(SampleEvent event) {
            SampleResult result = event.getResult();
            if (result != null && result.getParent() == null) {
                recordSample(result);
            }
        }

        @Override
        public void sampleStarted(SampleEvent event) {
            // no-op: only completed samples are summarized
        }

        @Override
        public void sampleStopped(SampleEvent event) {
            // no-op: only completed samples are summarized
        }
    }

    static record CollectedSamples(List<SampleResult> results, int totalSamples, int successCount,
                                   int failureCount) {
    }

    private static final class SampleCollection {
        private final List<SampleResult> results = new ArrayList<>();
        private final List<Boolean> failures = new ArrayList<>();
        private int totalSamples;
        private int successCount;
        private int failureCount;

        synchronized void record(SampleResult result) {
            String softFailure = SoftFailureDetector.reason(result);
            boolean failed = !result.isSuccessful() || softFailure != null;
            totalSamples++;
            if (failed) {
                failureCount++;
            } else {
                successCount++;
            }
            if (results.size() < SampleCollector.MAX_RESULTS_KEPT) {
                results.add(result);
                failures.add(failed);
            } else if (failed) {
                for (int index = 0; index < failures.size(); index++) {
                    if (!failures.get(index)) {
                        results.remove(index);
                        failures.remove(index);
                        results.add(result);
                        failures.add(true);
                        break;
                    }
                }
            }
        }

        synchronized CollectedSamples snapshot() {
            return new CollectedSamples(List.copyOf(results), totalSamples, successCount, failureCount);
        }
    }

    /**
     * Signals via a shared, static {@link CountDownLatch} once the engine's run has fully
     * ended - see the note in {@link #runTree} on why this can't be an instance field. Must be
     * {@code public} for the same reflective-cloning reason as {@link SampleCollector}.
     */
    public static final class RunEndedListener extends AbstractTestElement implements TestStateListener {
        private static volatile CountDownLatch latch;

        /** Sets the latch to count down when the run ends; call once before each run. */
        static void prepare(CountDownLatch newLatch) {
            latch = newLatch;
        }

        @Override
        public void testStarted() {
            // no-op: only completion is tracked
        }

        @Override
        public void testStarted(String host) {
            // no-op: only completion is tracked
        }

        @Override
        public void testEnded() {
            CountDownLatch current = latch;
            if (current != null) {
                current.countDown();
            }
        }

        @Override
        public void testEnded(String host) {
            testEnded();
        }
    }

    /** Thrown when the plan cannot be executed at all (missing GUI/plan, engine failure). */
    public static final class TestExecutionException extends RuntimeException {
        public TestExecutionException(String message) {
            super(message);
        }
    }
}
