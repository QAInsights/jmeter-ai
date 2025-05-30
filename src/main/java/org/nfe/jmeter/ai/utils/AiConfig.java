package org.nfe.jmeter.ai.utils;

import org.apache.jmeter.util.JMeterUtils;

public class AiConfig {
    public static String getProperty(String key, String defaultValue) {
        return JMeterUtils.getPropDefault(key, defaultValue);
    }
}
