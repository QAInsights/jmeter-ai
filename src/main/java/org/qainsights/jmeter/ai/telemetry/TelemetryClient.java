package org.qainsights.jmeter.ai.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.jmeter.util.JMeterUtils;
import org.qainsights.jmeter.ai.agent.JMeterAgent;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.Properties;

/**
 * Sends one anonymous ping per UTC day. On a failed send everything (day,
 * firstRun, feature counts) is kept for the next tick.
 */
public class TelemetryClient {
    private static final Logger log = LoggerFactory.getLogger(TelemetryClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String VERSION_RESOURCE =
            "org/qainsights/jmeter/ai/telemetry/version.properties";

    private final TelemetryConfig config;
    private final TelemetryState state;
    private final TelemetryCounters counters;
    private final TelemetrySender sender;
    private final Clock clock;

    public TelemetryClient(TelemetryConfig config, TelemetryState state,
                           TelemetryCounters counters, TelemetrySender sender) {
        this(config, state, counters, sender, Clock.systemUTC());
    }

    /** Package-private seam for tests. */
    TelemetryClient(TelemetryConfig config, TelemetryState state,
                    TelemetryCounters counters, TelemetrySender sender, Clock clock) {
        this.config = config;
        this.state = state;
        this.counters = counters;
        this.sender = sender;
        this.clock = clock;
    }

    public void tick() {
        try {
            if (!config.isEnabled()) {
                return;
            }
            String today = LocalDate.now(clock).toString();
            if (today.equals(state.getLastPingDay())) {
                return;
            }
            Map<String, Integer> snapshot = counters.snapshot();
            String json = buildPayload(snapshot);
            if (sender.send(json)) {
                counters.subtract(snapshot);
                state.setLastPingDay(today);
                state.setFirstPingSent(true);
                state.setPendingFeatures(counters.snapshot());
                state.save();
            }
        } catch (Throwable t) {
            log.debug("Telemetry tick failed", t);
        }
    }

    String buildPayload(Map<String, Integer> features) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("installId", state.getInstallId());
        root.put("event", "daily");
        root.put("pluginVersion", pluginVersion());
        root.put("jmeterVersion", jmeterVersion());
        root.put("javaVersion", System.getProperty("java.version", "unknown"));
        root.put("os", System.getProperty("os.name", "unknown"));
        root.put("arch", System.getProperty("os.arch", "unknown"));
        root.put("provider", AiConfig.getProperty("jmeter.ai.service.type", "openai"));
        root.put("agentEnabled", JMeterAgent.isEnabled());
        root.put("firstRun", !state.isFirstPingSent());
        ObjectNode f = root.putObject("features");
        features.forEach((k, v) -> {
            if (v != null && v > 0) {
                f.put(k, v);
            }
        });
        return root.toString();
    }

    static String pluginVersion() {
        try (InputStream in = TelemetryClient.class.getClassLoader().getResourceAsStream(VERSION_RESOURCE)) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                String v = props.getProperty("version", "").trim();
                if (!v.isEmpty() && !v.contains("${")) {
                    return v;
                }
            }
        } catch (Throwable t) {
            log.debug("Failed to read plugin version resource", t);
        }
        return "unknown";
    }

    private static String jmeterVersion() {
        try {
            String v = JMeterUtils.getJMeterVersion();
            return v == null ? "unknown" : v;
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
