package org.qainsights.jmeter.ai.telemetry;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TelemetryCountersTest {

    @Test
    void incrementsAccumulate() {
        TelemetryCounters c = new TelemetryCounters();
        c.increment(TelemetryFeature.CHAT_MESSAGE);
        c.increment(TelemetryFeature.CHAT_MESSAGE);
        c.increment(TelemetryFeature.AGENT_RUN);
        assertEquals(Map.of("chat_message", 2, "agent_run", 1), c.snapshot());
    }

    @Test
    void incrementsDuringSendSurviveSubtract() {
        TelemetryCounters c = new TelemetryCounters();
        c.increment(TelemetryFeature.CHAT_MESSAGE);
        c.increment(TelemetryFeature.CHAT_MESSAGE);
        Map<String, Integer> inFlight = c.snapshot();
        // A send is "in flight"; new increments arrive meanwhile.
        c.increment(TelemetryFeature.CHAT_MESSAGE);
        c.increment(TelemetryFeature.AGENT_RUN);
        c.subtract(inFlight);
        assertEquals(Map.of("chat_message", 1, "agent_run", 1), c.snapshot());
    }

    @Test
    void loadPendingRestoresCounts() {
        TelemetryCounters c = new TelemetryCounters();
        c.loadPending(Map.of("cmd_lint", 4, "panel_open", 1));
        c.increment(TelemetryFeature.CMD_LINT);
        assertEquals(Map.of("cmd_lint", 5, "panel_open", 1), c.snapshot());
    }

    @Test
    void snapshotIsACopy() {
        TelemetryCounters c = new TelemetryCounters();
        c.increment(TelemetryFeature.PANEL_OPEN);
        Map<String, Integer> snap = c.snapshot();
        snap.put("bogus", 99);
        assertEquals(Map.of("panel_open", 1), c.snapshot());
    }
}
