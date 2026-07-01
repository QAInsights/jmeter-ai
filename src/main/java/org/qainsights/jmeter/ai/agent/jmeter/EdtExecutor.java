package org.qainsights.jmeter.ai.agent.jmeter;

import java.lang.reflect.InvocationTargetException;

import javax.swing.SwingUtilities;

/**
 * Abstraction over running a task on the Swing Event Dispatch Thread (EDT).
 * <p>
 * JMeter tree mutations must run on the EDT, but the agent loop runs on a
 * background thread. This interface lets production code marshal onto the EDT
 * while tests inject a {@link #direct()} executor that runs synchronously.
 */
@FunctionalInterface
public interface EdtExecutor {

    /** Runs the task, blocking until it completes. */
    void run(Runnable task);

    /**
     * Real Swing implementation: runs immediately if already on the EDT,
     * otherwise blocks via {@link SwingUtilities#invokeAndWait}.
     */
    static EdtExecutor swing() {
        return task -> {
            if (SwingUtilities.isEventDispatchThread()) {
                task.run();
                return;
            }
            try {
                SwingUtilities.invokeAndWait(task);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for EDT task", e);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                throw new IllegalStateException("EDT task failed",
                        cause != null ? cause : e);
            }
        };
    }

    /** Synchronous, same-thread executor for tests and headless use. */
    static EdtExecutor direct() {
        return Runnable::run;
    }
}
