package org.qainsights.jmeter.ai.utils;

import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.threads.ThreadGroup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Unit tests for {@link JMeterElementManager#initializeThreadGroupDefaults}, the
 * defaulting applied to a freshly created Thread Group by {@code add_element}
 * (via {@code JMeterElementManager.addElement}).
 */
class JMeterElementManagerThreadGroupDefaultsTest {

    @Test
    void setsNumThreadsAndRampUpToOne() {
        ThreadGroup threadGroup = new ThreadGroup();

        JMeterElementManager.initializeThreadGroupDefaults(threadGroup);

        assertEquals(1, threadGroup.getNumThreads());
        assertEquals(1, threadGroup.getRampUp());
    }

    @Test
    void attachesAOneLoopLoopController() {
        ThreadGroup threadGroup = new ThreadGroup();

        JMeterElementManager.initializeThreadGroupDefaults(threadGroup);

        LoopController controller = assertInstanceOf(LoopController.class, threadGroup.getSamplerController());
        assertEquals(1, controller.getLoops());
    }
}
