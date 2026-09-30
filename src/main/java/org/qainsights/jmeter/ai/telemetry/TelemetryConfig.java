package org.qainsights.jmeter.ai.telemetry;

import org.qainsights.jmeter.ai.utils.AiConfig;

import java.util.function.Function;

/** Resolves telemetry opt-out switches and the ping endpoint. */
public class TelemetryConfig {
    public static final String ENABLED_PROPERTY = "jmeter.ai.telemetry.enabled";
    public static final String URL_PROPERTY = "jmeter.ai.telemetry.url";
    public static final String DEFAULT_ENDPOINT = "https://telemetry.jmeter.ai/v1/ping";

    private final Function<String, String> env;

    public TelemetryConfig() {
        this(System::getenv);
    }

    /** Package-private seam for tests. */
    TelemetryConfig(Function<String, String> env) {
        this.env = env;
    }

    /**
     * Telemetry is on by default. It is disabled when the property is explicitly
     * false, when DO_NOT_TRACK is 1/true, or when FEATHER_WAND_TELEMETRY is 0/false.
     */
    public boolean isEnabled() {
        if (!Boolean.parseBoolean(AiConfig.getProperty(ENABLED_PROPERTY, "true"))) {
            return false;
        }
        if (isTruthy(env.apply("DO_NOT_TRACK"))) {
            return false;
        }
        if (isFalsy(env.apply("FEATHER_WAND_TELEMETRY"))) {
            return false;
        }
        return true;
    }

    public String endpoint() {
        return AiConfig.getProperty(URL_PROPERTY, DEFAULT_ENDPOINT);
    }

    private static boolean isTruthy(String v) {
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    private static boolean isFalsy(String v) {
        return "0".equals(v) || "false".equalsIgnoreCase(v);
    }
}
