package org.qainsights.jmeter.ai.utils;

import org.apache.jmeter.util.JMeterUtils;

public class AiConfig {
    public static String getProperty(String key, String defaultValue) {
        return JMeterUtils.getPropDefault(key, defaultValue);
    }

    public static boolean isStreamingEnabled() {
        return Boolean.parseBoolean(getProperty("jmeter.ai.streaming.enabled", "true"));
    }
}
