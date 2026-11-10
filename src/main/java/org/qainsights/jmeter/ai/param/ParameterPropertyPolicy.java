package org.qainsights.jmeter.ai.param;

import org.apache.jmeter.config.CSVDataSet;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.StringProperty;

import java.util.Locale;

final class ParameterPropertyPolicy {
    private ParameterPropertyPolicy() {}

    static boolean skip(JMeterProperty property, boolean config) {
        if (property.getObjectValue() instanceof CSVDataSet) {
            return true;
        }
        if (!config) {
            return false;
        }
        String name = property.getName().toLowerCase(Locale.ROOT);
        return name.matches(".*(file|filename|filepath|directory|path)$")
                || (property instanceof StringProperty
                        && (property.getStringValue().contains("/")
                                || property.getStringValue().contains("\\")));
    }
}
