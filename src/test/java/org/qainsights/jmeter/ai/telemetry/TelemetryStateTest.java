package org.qainsights.jmeter.ai.telemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TelemetryStateTest {

    @TempDir
    Path dir;

    @Test
    void firstLoadGeneratesUuidAndPersists() {
        TelemetryState s = TelemetryState.load(dir);
        assertTrue(s.isNewInstall());
        assertDoesNotThrow(() -> UUID.fromString(s.getInstallId()));
        assertTrue(Files.isRegularFile(dir.resolve("telemetry.json")));
        assertNull(s.getLastPingDay());
        assertFalse(s.isFirstPingSent());
    }

    @Test
    void installIdReusedAcrossLoads() {
        TelemetryState first = TelemetryState.load(dir);
        first.setLastPingDay("2026-01-01");
        first.setFirstPingSent(true);
        first.setPendingFeatures(Map.of("chat_message", 3));
        first.save();

        TelemetryState second = TelemetryState.load(dir);
        assertFalse(second.isNewInstall());
        assertEquals(first.getInstallId(), second.getInstallId());
        assertEquals("2026-01-01", second.getLastPingDay());
        assertTrue(second.isFirstPingSent());
        assertEquals(Map.of("chat_message", 3), second.getPendingFeatures());
    }

    @Test
    void corruptFileIsRegenerated() throws Exception {
        Files.writeString(dir.resolve("telemetry.json"), "{not json!!");
        TelemetryState s = TelemetryState.load(dir);
        assertTrue(s.isNewInstall());
        assertDoesNotThrow(() -> UUID.fromString(s.getInstallId()));
        // Reloading picks up the regenerated file.
        assertEquals(s.getInstallId(), TelemetryState.load(dir).getInstallId());
    }

    @Test
    void atomicWriteLeavesNoTempFile() {
        TelemetryState s = TelemetryState.load(dir);
        s.save();
        assertFalse(Files.exists(dir.resolve("telemetry.json.tmp")));
    }
}
