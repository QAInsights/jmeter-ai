package org.qainsights.jmeter.ai.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ModelUtils {
    private static final Logger log = LoggerFactory.getLogger(ModelUtils.class);

    private ModelUtils() {

    }

    public static float parseTemperature(String temperature) {
        try {
            float temp = Float.parseFloat(temperature);
            if (temp < 0 || temp > 2) {
                log.warn("Temperature must be between 0 and 2. Provided value: {}. Setting to default 0.7", temp);
                return 0.7f;
            }
            return temp;
        } catch (NumberFormatException e) {
            log.warn("Invalid temperature value: '{}'. Setting to default 0.7", temperature);
            return 0.7f;
        }
    }
}
