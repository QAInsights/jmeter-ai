package org.qainsights.jmeter.ai.telemetry;

import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class TelemetryConfigTest {

    private static Properties props;

    @BeforeAll
    static void initJMeter() {
        if (JMeterUtils.getJMeterProperties() == null) {
            JMeterUtils.loadJMeterProperties("nonexistent.properties");
        }
        props = JMeterUtils.getJMeterProperties();
    }

    @BeforeEach
    void reset() {
        props.remove(TelemetryConfig.ENABLED_PROPERTY);
        props.remove(TelemetryConfig.URL_PROPERTY);
    }

    private static TelemetryConfig config(Map<String, String> env) {
        return new TelemetryConfig(env::get);
    }

    @Test
    void enabledByDefault() {
        assertTrue(config(Map.of()).isEnabled());
    }

    @Test
    void propertyFalseDisables() {
        props.setProperty(TelemetryConfig.ENABLED_PROPERTY, "false");
        assertFalse(config(Map.of()).isEnabled());
    }

    @Test
    void doNotTrackDisables() {
        assertFalse(config(Map.of("DO_NOT_TRACK", "1")).isEnabled());
        assertFalse(config(Map.of("DO_NOT_TRACK", "true")).isEnabled());
        assertFalse(config(Map.of("DO_NOT_TRACK", "TRUE")).isEnabled());
    }

    @Test
    void featherWandTelemetryEnvDisables() {
        assertFalse(config(Map.of("FEATHER_WAND_TELEMETRY", "0")).isEnabled());
        assertFalse(config(Map.of("FEATHER_WAND_TELEMETRY", "false")).isEnabled());
    }

    @Test
    void unrelatedEnvValuesDoNotDisable() {
        assertTrue(config(Map.of("DO_NOT_TRACK", "0", "FEATHER_WAND_TELEMETRY", "1")).isEnabled());
        assertTrue(config(Map.of("DO_NOT_TRACK", "yes")).isEnabled());
    }

    @Test
    void endpointDefaultsAndOverride() {
        assertEquals(TelemetryConfig.DEFAULT_ENDPOINT, config(Map.of()).endpoint());
        props.setProperty(TelemetryConfig.URL_PROPERTY, "https://example.com/ping");
        assertEquals("https://example.com/ping", config(Map.of()).endpoint());
    }
}
