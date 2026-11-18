package org.qainsights.jmeter.ai.param;

import org.apache.jmeter.config.CSVDataSet;
import org.apache.jmeter.config.Argument;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.http.util.HTTPFileArg;
import org.apache.jmeter.protocol.http.util.HTTPFileArgs;
import org.apache.jmeter.testelement.property.JMeterProperty;

import java.util.Locale;

final class ParameterPropertyPolicy {
    private ParameterPropertyPolicy() {}

    static boolean skip(JMeterProperty property, boolean config) {
        if (property.getObjectValue() instanceof HTTPFileArgs
                || property.getObjectValue() instanceof HTTPFileArg) {
            return true;
        }
        if (property.getObjectValue() instanceof CSVDataSet) {
            return true;
        }
        if (property.getObjectValue() instanceof Arguments
                && "TestPlan.user_defined_variables".equals(property.getName())) {
            return true;
        }
        if (!config) {
            return false;
        }
        if (property.getObjectValue() instanceof Argument) {
            Argument argument = (Argument) property.getObjectValue();
            return fileProperty(argument.getName());
        }
        return fileProperty(property.getName());
    }

    private static boolean fileProperty(String name) {
        return name.replaceAll("([a-z])([A-Z])", "$1.$2").toLowerCase(Locale.ROOT)
                .matches("(?:.*[._-])?(?:file|filename|filepath|directory|path)");
    }
}
