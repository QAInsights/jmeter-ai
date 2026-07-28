package org.qainsights.jmeter.ai.record;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.testelement.AbstractTestElement;

/**
 * Counts samplers as the recorder delivers them, so callers can tell when traffic
 * has gone quiet.
 * <p>
 * This exists because {@code ProxyControl} delivers samplers asynchronously on proxy
 * threads. Re-pointing the recorder's target the instant the agent finishes a UI action
 * would misfile any request still in flight into the <em>next</em> business step. Waiting
 * for a quiet period before each boundary keeps step grouping honest.
 * <p>
 * Installed as a child of the recorder's {@code ProxyControl} node, which is how
 * {@code ProxyControl} discovers its {@link SampleListener}s.
 */
public final class RecordingSampleCounter extends AbstractTestElement implements SampleListener {

    private final AtomicInteger count = new AtomicInteger();
    private final AtomicLong lastSampleAt = new AtomicLong();

    @Override
    public void sampleOccurred(SampleEvent event) {
        count.incrementAndGet();
        lastSampleAt.set(System.currentTimeMillis());
    }

    @Override
    public void sampleStarted(SampleEvent event) {
        // The recorder only emits sampleOccurred.
    }

    @Override
    public void sampleStopped(SampleEvent event) {
        // The recorder only emits sampleOccurred.
    }

    /**
     * @return how many samplers have been delivered since recording started
     */
    public int count() {
        return count.get();
    }

    /**
     * @return epoch millis of the most recent delivery, or 0 if nothing has arrived yet
     */
    public long lastSampleAt() {
        return lastSampleAt.get();
    }

    /**
     * Blocks until no sampler has arrived for {@code quietMillis}, or until
     * {@code timeoutMillis} elapses.
     * <p>
     * Returns immediately when nothing has been delivered yet, since there is no
     * in-flight traffic to wait for.
     *
     * @param quietMillis   how long the stream must stay silent to count as quiet
     * @param timeoutMillis hard upper bound on the wait
     * @return true if a quiet period was observed, false if the timeout won first
     * @throws InterruptedException if the calling thread is interrupted
     */
    public boolean awaitQuiescence(long quietMillis, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            long last = lastSampleAt.get();
            if (last == 0L || System.currentTimeMillis() - last >= quietMillis) {
                return true;
            }
            Thread.sleep(Math.min(50L, Math.max(1L, quietMillis / 4)));
        }
        long last = lastSampleAt.get();
        return last == 0L || System.currentTimeMillis() - last >= quietMillis;
    }
}
