package org.qainsights.jmeter.ai.pet;

import java.util.Collection;
import java.util.function.Supplier;
import org.apache.jmeter.engine.StandardJMeterEngine;
import org.apache.jmeter.reporters.AbstractListenerElement;
import org.apache.jmeter.testelement.TestStateListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges JMeter test lifecycle events to the pet. Registered through
 * {@link StandardJMeterEngine#register(TestStateListener)}, which fires for both
 * GUI-initiated runs and the plugin's embedded engine runs. JMeter consumes that
 * static registration list at the start of every run, so this monitor re-registers
 * itself after each test ends.
 */
public final class PetTestMonitor implements TestStateListener {
    private static final Logger log = LoggerFactory.getLogger(PetTestMonitor.class);

    private final PetAnimator animator;
    private final PetSampleTap sampleTap;
    private final Supplier<? extends Collection<? extends AbstractListenerElement>> listenerSource;
    private boolean runActive;

    public PetTestMonitor(PetAnimator animator, PetSampleTap sampleTap,
            Supplier<? extends Collection<? extends AbstractListenerElement>> listenerSource) {
        this.animator = animator;
        this.sampleTap = sampleTap;
        this.listenerSource = listenerSource;
    }

    /** Registers this monitor for the next engine run. */
    public void register() {
        StandardJMeterEngine.register(this);
    }

    @Override
    public synchronized void testStarted() {
        if (runActive) {
            log.info("Pet ignored test start; it is already tracking an active run.");
            return;
        }
        runActive = true;
        try {
            animator.onTestStarted();
            sampleTap.install(listenerSource.get());
        } catch (RuntimeException e) {
            log.warn("Pet could not react to test start: {}", e.toString());
        }
    }

    @Override
    public void testStarted(String host) {
        testStarted();
    }

    @Override
    public synchronized void testEnded() {
        if (!runActive) {
            log.info("Pet ignored test end; it was not tracking an active run.");
            return;
        }
        log.info("Pet saw the test run end.");
        runActive = false;
        try {
            sampleTap.uninstall();
            animator.onTestEnded();
        } catch (RuntimeException e) {
            log.warn("Pet could not react to test end: {}", e.toString());
        } finally {
            // The engine clears its static listener list at the start of every run;
            // re-register so the pet also sees the next one.
            register();
        }
    }

    @Override
    public void testEnded(String host) {
        testEnded();
    }
}
