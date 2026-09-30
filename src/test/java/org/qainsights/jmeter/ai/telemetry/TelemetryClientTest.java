package org.qainsights.jmeter.ai.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TelemetryClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static Properties props;

    @TempDir
    Path dir;

    private TelemetryState state;
    private TelemetryCounters counters;
    private List<String> sent;
    private boolean sendResult = true;

    @BeforeAll
    static void initJMeter() {
        if (JMeterUtils.getJMeterProperties() == null) {
            JMeterUtils.loadJMeterProperties("nonexistent.properties");
        }
        props = JMeterUtils.getJMeterProperties();
    }

    @BeforeEach
    void setUp() {
        props.remove(TelemetryConfig.ENABLED_PROPERTY);
        state = TelemetryState.load(dir);
        counters = new TelemetryCounters();
        sent = new ArrayList<>();
        sendResult = true;
    }

    private TelemetryClient client(Clock clock) {
        TelemetrySender sender = json -> {
            sent.add(json);
            return sendResult;
        };
        return new TelemetryClient(new TelemetryConfig(k -> null), state, counters, sender, clock);
    }

    private static Clock clockAt(String day) {
        return Clock.fixed(Instant.parse(day + "T12:00:00Z"), ZoneOffset.UTC);
    }

    @Test
    void disabledNeverSends() {
        props.setProperty(TelemetryConfig.ENABLED_PROPERTY, "false");
        client(clockAt("2026-01-05")).tick();
        assertTrue(sent.isEmpty());
        assertNull(state.getLastPingDay());
    }

    @Test
    void disabledStartWritesNoStateFile() throws Exception {
        // Reproduces Telemetry.start()'s disabled path: the config check runs
        // before any state file would be created.
        props.setProperty(TelemetryConfig.ENABLED_PROPERTY, "false");
        Path other = dir.resolve("other");
        if (new TelemetryConfig(k -> null).isEnabled()) {
            TelemetryState.load(other);
        }
        assertFalse(Files.exists(other.resolve("telemetry.json")));
    }

    @Test
    void sendsOncePerUtcDay() {
        TelemetryClient c = client(clockAt("2026-01-05"));
        c.tick();
        c.tick();
        assertEquals(1, sent.size());
        assertEquals("2026-01-05", state.getLastPingDay());

        TelemetryClient nextDay = client(clockAt("2026-01-06"));
        nextDay.tick();
        assertEquals(2, sent.size());
        assertEquals("2026-01-06", state.getLastPingDay());
    }

    @Test
    void failureKeepsCountsAndDoesNotAdvanceDay() {
        counters.increment(TelemetryFeature.CHAT_MESSAGE);
        sendResult = false;
        TelemetryClient c = client(clockAt("2026-01-05"));
        c.tick();
        assertEquals(1, sent.size());
        assertNull(state.getLastPingDay());
        assertFalse(state.isFirstPingSent());
        assertEquals(Map.of("chat_message", 1), counters.snapshot());

        sendResult = true;
        c.tick();
        assertEquals(2, sent.size());
        assertEquals("2026-01-05", state.getLastPingDay());
        assertTrue(counters.snapshot().isEmpty());
    }

    @Test
    void pendingCountsPersistedAfterSuccess() {
        counters.increment(TelemetryFeature.CMD_LINT);
        client(clockAt("2026-01-05")).tick();
        TelemetryState reloaded = TelemetryState.load(dir);
        // The sent counts were subtracted, so nothing pending remains.
        assertEquals(Map.of(), reloaded.getPendingFeatures());
        assertEquals("2026-01-05", reloaded.getLastPingDay());
        assertTrue(reloaded.isFirstPingSent());
    }

    @Test
    void payloadMatchesContract() throws Exception {
        counters.increment(TelemetryFeature.CHAT_MESSAGE);
        counters.increment(TelemetryFeature.CHAT_MESSAGE);
        client(clockAt("2026-01-05")).tick();
        assertEquals(1, sent.size());

        JsonNode p = MAPPER.readTree(sent.get(0));
        assertEquals(Set.of("installId", "event", "pluginVersion", "jmeterVersion",
                        "javaVersion", "os", "arch", "provider", "agentEnabled",
                        "firstRun", "features"),
                fieldSet(p));
        assertEquals(state.getInstallId(), p.get("installId").asText());
        assertEquals("daily", p.get("event").asText());
        assertFalse(p.get("agentEnabled").asBoolean());
        assertTrue(p.get("firstRun").asBoolean());
        assertEquals(2, p.get("features").get("chat_message").asInt());
    }

    private static Set<String> fieldSet(JsonNode n) {
        Set<String> s = new java.util.HashSet<>();
        n.fieldNames().forEachRemaining(s::add);
        return s;
    }

    @Test
    void zeroFeaturesOmitted() throws Exception {
        counters.increment(TelemetryFeature.CHAT_MESSAGE);
        counters.subtract(Map.of("chat_message", 1)); // back to zero
        client(clockAt("2026-01-05")).tick();
        JsonNode p = MAPPER.readTree(sent.get(0));
        assertEquals(0, p.get("features").size());
    }

    @Test
    void firstRunTrueOnlyOnFirstSuccess() throws Exception {
        sendResult = false;
        TelemetryClient c = client(clockAt("2026-01-05"));
        c.tick();
        assertTrue(MAPPER.readTree(sent.get(0)).get("firstRun").asBoolean());

        sendResult = true;
        c.tick();
        assertTrue(MAPPER.readTree(sent.get(1)).get("firstRun").asBoolean());

        TelemetryClient nextDay = client(clockAt("2026-01-06"));
        nextDay.tick();
        assertFalse(MAPPER.readTree(sent.get(2)).get("firstRun").asBoolean());
    }
}
