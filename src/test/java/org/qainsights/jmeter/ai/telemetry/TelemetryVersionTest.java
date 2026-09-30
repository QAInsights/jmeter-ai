package org.qainsights.jmeter.ai.telemetry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TelemetryVersionTest {

    @Test
    void versionResourceResolvesToPomVersion() {
        // version.properties is Maven-filtered at process-resources time; on the
        // test classpath it must carry the real project version, not the raw
        // placeholder or the "unknown" fallback.
        String v = TelemetryClient.pluginVersion();
        assertNotEquals("unknown", v);
        assertFalse(v.contains("${"));
        assertEquals("3.8.5", v);
    }
}
