package org.qainsights.jmeter.ai.agent.jmeter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.jmeter.JMeter;
import org.apache.jmeter.engine.StandardJMeterEngine;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestStateListener;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;

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

    /** Live entry point: pulls the current plan from the live {@link GuiPackage} tree model. */
    public TestRunSummary run(long timeoutSeconds) {
        GuiPackage gui = GuiPackage.getInstance();
        if (gui == null) {
            throw new TestExecutionException("No live JMeter GUI available.");
        }
        HashTree testPlanTree = gui.getTreeModel().getTestPlan();
        if (testPlanTree == null || testPlanTree.size() == 0) {
            throw new TestExecutionException("No test plan is currently open.");
        }
        return runTree(testPlanTree, timeoutSeconds);
    }

    /**
     * Runs an already-fetched test plan tree through a private engine. Package-visible so
     * it can be exercised directly with a hand-built {@link HashTree} in tests, without a
     * live {@link GuiPackage}. Accepts a tree keyed by either {@code JMeterTreeNode} (as
     * returned by {@code JMeterTreeModel.getTestPlan()}) or plain {@code TestElement} (as
     * returned by {@code SaveService.loadTree}) - {@link JMeter#convertSubTree} handles both.
     */
    synchronized TestRunSummary runTree(HashTree testPlanTree, long timeoutSeconds) {
        ListedHashTree clonedTree;
        try {
            clonedTree = (ListedHashTree) testPlanTree.clone();
        } catch (RuntimeException e) {
            throw new TestExecutionException("Could not clone the test plan: " + e.getMessage());
        }
        HashTree execTree = JMeter.convertSubTree(clonedTree, true);

        // JMeter reflectively clones per-thread SampleListener/TestStateListener test elements
        // via their no-arg constructor (see AbstractTestElement.clone()) - any instance field set
        // here would not survive that clone. Collector/Ender therefore coordinate via static state,
        // reset per call by prepare(), mirroring CorrelationEngine.Collector/Ender's proven pattern.
        SampleCollector.prepare();
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
            engine.configure(execTree);
            engine.run();
        } catch (RuntimeException e) {
            throw new TestExecutionException("Failed to run the test plan: " + e.getMessage());
        }

        boolean finished;
        try {
            finished = done.await(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            engine.stopTest(true);
        }
        long elapsed = System.currentTimeMillis() - start;
        return TestRunSummary.of(SampleCollector.collected(), !finished, elapsed);
    }

    /**
     * Collects every top-level sample result fired during the run, via static state shared
     * across every reflectively-cloned instance (see the note in {@link #runTree}). Must be
     * {@code public} - {@link AbstractTestElement#clone()} instantiates the runtime class
     * reflectively via its no-arg constructor, which fails with an {@code IllegalAccessException}
     * against a non-public nested class.
     */
    public static final class SampleCollector extends AbstractTestElement implements SampleListener {
        private static volatile List<SampleResult> shared;

        /** Resets the shared results list; call once before each run. */
        static void prepare() {
            shared = Collections.synchronizedList(new ArrayList<>());
        }

        /** A snapshot of every top-level sample result collected since the last {@link #prepare()}. */
        static List<SampleResult> collected() {
            List<SampleResult> current = shared;
            if (current == null) {
                return Collections.emptyList();
            }
            synchronized (current) {
                return new ArrayList<>(current);
            }
        }

        @Override
        public void sampleOccurred(SampleEvent event) {
            List<SampleResult> current = shared;
            SampleResult result = event.getResult();
            if (current != null && result != null && result.getParent() == null) {
                current.add(result);
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
