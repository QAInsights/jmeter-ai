package org.qainsights.jmeter.ai.record;

import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RecordingSampleCounter}, the quiescence detector that stops a
 * business-step boundary from splitting a burst of in-flight requests.
 */
class RecordingSampleCounterTest {

    @BeforeAll
    static void initJMeterProperties() {
        if (JMeterUtils.getJMeterProperties() == null) {
            JMeterUtils.loadJMeterProperties("nonexistent.properties");
        }
    }

    private static SampleEvent event() {
        SampleResult result = new SampleResult();
        result.setSampleLabel("sample");
        return new SampleEvent(result, "Feather Wand Recording");
    }

    @Test
    void should_startAtZero() {
        RecordingSampleCounter counter = new RecordingSampleCounter();
        assertEquals(0, counter.count());
        assertEquals(0L, counter.lastSampleAt());
    }

    @Test
    void should_countDeliveredSamples() {
        RecordingSampleCounter counter = new RecordingSampleCounter();
        counter.sampleOccurred(event());
        counter.sampleOccurred(event());
        assertEquals(2, counter.count());
        assertTrue(counter.lastSampleAt() > 0L);
    }

    @Test
    void should_ignoreStartedAndStoppedEvents() {
        RecordingSampleCounter counter = new RecordingSampleCounter();
        counter.sampleStarted(event());
        counter.sampleStopped(event());
        assertEquals(0, counter.count(), "only delivered samplers should be counted");
    }

    @Test
    void should_returnQuietImmediately_when_nothingRecordedYet() throws Exception {
        RecordingSampleCounter counter = new RecordingSampleCounter();
        long before = System.currentTimeMillis();
        assertTrue(counter.awaitQuiescence(5_000, 5_000));
        assertTrue(System.currentTimeMillis() - before < 1_000,
                "an empty stream has no in-flight traffic to wait for");
    }

    @Test
    void should_becomeQuiet_when_noFurtherSamplesArrive() throws Exception {
        RecordingSampleCounter counter = new RecordingSampleCounter();
        counter.sampleOccurred(event());
        assertTrue(counter.awaitQuiescence(100, 5_000));
    }

    @Test
    void should_timeOut_when_trafficNeverStops() throws Exception {
        RecordingSampleCounter counter = new RecordingSampleCounter();
        // Prime it: with nothing recorded yet the counter is quiet by definition, so the
        // worker must be demonstrably producing before the wait starts.
        counter.sampleOccurred(event());

        Thread noisy = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                counter.sampleOccurred(event());
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        noisy.setDaemon(true);
        noisy.start();
        try {
            int startCount = counter.count();
            long spinUpDeadline = System.currentTimeMillis() + 2_000;
            while (counter.count() == startCount && System.currentTimeMillis() < spinUpDeadline) {
                Thread.sleep(5);
            }
            assertTrue(counter.count() > startCount, "worker should be generating traffic");

            assertFalse(counter.awaitQuiescence(2_000, 300),
                    "continuous traffic must not be reported as quiet");
        } finally {
            noisy.interrupt();
            noisy.join(2_000);
        }
    }
}
