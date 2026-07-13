package org.qainsights.jmeter.ai.agent.jmeter;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link EdtExecutor}. */
class EdtExecutorTest {

    @Test
    void direct_runsSynchronouslyOnCallingThread() {
        Thread caller = Thread.currentThread();
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        EdtExecutor.direct().run(() -> ranOn.set(Thread.currentThread()));
        assertSame(caller, ranOn.get());
    }

    @Test
    void swing_runsTaskOnEventDispatchThread() {
        AtomicBoolean onEdt = new AtomicBoolean(false);
        EdtExecutor.swing().run(() -> onEdt.set(SwingUtilities.isEventDispatchThread()));
        assertTrue(onEdt.get(), "Task must run on the EDT when invoked from a non-EDT thread");
    }

    @Test
    void swing_propagatesTaskExceptionAsIllegalState() {
        EdtExecutor exec = EdtExecutor.swing();
        assertThrows(IllegalStateException.class,
                () -> exec.run(() -> {
                    throw new RuntimeException("boom");
                }));
    }
}
