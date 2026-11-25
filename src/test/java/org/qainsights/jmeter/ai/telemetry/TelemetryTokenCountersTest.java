package org.qainsights.jmeter.ai.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TelemetryTokenCountersTest {

    @Test
    void subtractingASnapshotPreservesIncrementsThatHappenedLater() {
        TelemetryTokenCounters counters = new TelemetryTokenCounters();
        counters.add(1000, 200);
        TelemetryTokenCounters.Snapshot sent = counters.snapshot();
        counters.add(50, 10);

        counters.subtract(sent);

        assertEquals(50, counters.snapshot().inputTokens());
        assertEquals(10, counters.snapshot().outputTokens());
    }

    @Test
    void ignoresNegativeAndZeroValues() {
        TelemetryTokenCounters counters = new TelemetryTokenCounters();
        counters.add(-5, 0);
        counters.loadPending(-1, 0);

        assertEquals(0, counters.snapshot().inputTokens());
        assertEquals(0, counters.snapshot().outputTokens());
    }
}
