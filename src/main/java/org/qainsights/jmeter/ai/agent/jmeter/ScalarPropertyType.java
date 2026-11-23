package org.qainsights.jmeter.ai.agent.jmeter;

import java.util.regex.Pattern;

import org.apache.jmeter.assertions.DurationAssertion;
import org.apache.jmeter.assertions.JSONPathAssertion;
import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.assertions.SizeAssertion;
import org.apache.jmeter.config.CSVDataSet;
import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.extractor.RegexExtractor;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestElementSchema;
import org.apache.jmeter.testelement.property.BooleanProperty;
import org.apache.jmeter.testelement.property.DoubleProperty;
import org.apache.jmeter.testelement.property.FloatProperty;
import org.apache.jmeter.testelement.property.IntegerProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.LongProperty;
import org.apache.jmeter.testelement.property.StringProperty;
import org.apache.jmeter.testelement.schema.BooleanPropertyDescriptor;
import org.apache.jmeter.testelement.schema.DoublePropertyDescriptor;
import org.apache.jmeter.testelement.schema.FloatPropertyDescriptor;
import org.apache.jmeter.testelement.schema.IntegerPropertyDescriptor;
import org.apache.jmeter.testelement.schema.LongPropertyDescriptor;
import org.apache.jmeter.testelement.schema.PropertyDescriptor;
import org.apache.jmeter.testelement.schema.StringPropertyDescriptor;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.timers.ConstantThroughputTimer;
import org.apache.jmeter.timers.ConstantTimer;

enum ScalarPropertyType {
    STRING("text"),
    BOOLEAN("true or false"),
    INTEGER("a 32-bit integer"),
    LOOP_COUNT("a 32-bit integer"),
    LONG("a 64-bit integer"),
    FLOAT("a finite number"),
    DOUBLE("a finite number");

    private static final Pattern EXPRESSION = Pattern.compile("\\$\\{[^}]*}");

    private final String expected;

    ScalarPropertyType(String expected) {
        this.expected = expected;
    }

    static ScalarPropertyType resolve(TestElement element, String key, JMeterProperty existing) {
        if ((element instanceof ConstantTimer && ConstantTimer.DELAY.equals(key))
                || (element instanceof SizeAssertion && "SizeAssertion.size".equals(key))
                || (element instanceof RegexExtractor && "RegexExtractor.match_number".equals(key))
                || isHttpPort(element, key)) {
            return STRING;
        }
        if (isHttpElement(element) && isHttpTimeout(key)) {
            return INTEGER;
        }
        if (element instanceof LoopController && LoopController.LOOPS.equals(key)) {
            return LOOP_COUNT;
        }

        TestElementSchema schema = element.getSchema();
        PropertyDescriptor<?, ?> descriptor = schema == null ? null : schema.getProperties().get(key);
        if (descriptor instanceof StringPropertyDescriptor<?>) return STRING;
        if (descriptor instanceof BooleanPropertyDescriptor<?>) return BOOLEAN;
        if (descriptor instanceof IntegerPropertyDescriptor<?>) return INTEGER;
        if (descriptor instanceof LongPropertyDescriptor<?>) return LONG;
        if (descriptor instanceof FloatPropertyDescriptor<?>) return FLOAT;
        if (descriptor instanceof DoublePropertyDescriptor<?>) return DOUBLE;

        if (element instanceof ResponseAssertion) {
            if ("Assertion.test_type".equals(key)) return INTEGER;
            if ("Assertion.assume_success".equals(key)) return BOOLEAN;
        }
        if (element instanceof SizeAssertion && "SizeAssertion.operator".equals(key)) return INTEGER;
        if (element instanceof DurationAssertion && DurationAssertion.DURATION_KEY.equals(key)) return LONG;
        if (element instanceof ConstantThroughputTimer) {
            if (ConstantThroughputTimer.CALC_MODE.equals(key)) return INTEGER;
            if (ConstantThroughputTimer.THROUGHPUT.equals(key)) return DOUBLE;
        }
        if (element instanceof CSVDataSet && switch (key) {
            case "ignoreFirstLine", "quotedData", "recycle", "stopThread" -> true;
            default -> false;
        }) return BOOLEAN;
        if (element instanceof JSONPathAssertion && switch (key) {
            case JSONPathAssertion.JSONVALIDATION, JSONPathAssertion.EXPECT_NULL,
                    JSONPathAssertion.INVERT, JSONPathAssertion.ISREGEX -> true;
            default -> false;
        }) return BOOLEAN;

        if (existing instanceof BooleanProperty) return BOOLEAN;
        if (existing instanceof IntegerProperty) return INTEGER;
        if (existing instanceof LongProperty) return LONG;
        if (existing instanceof FloatProperty) return FLOAT;
        if (existing instanceof DoubleProperty) return DOUBLE;
        return STRING;
    }

    private static boolean isHttpPort(TestElement target, String key) {
        return HTTPSamplerBase.PORT.equals(key) && isHttpElement(target);
    }

    private static boolean isHttpElement(TestElement target) {
        return target instanceof HTTPSamplerBase
                || (target instanceof ConfigTestElement config && HttpDefaultsSupport.isHttpDefaults(config));
    }

    private static boolean isHttpTimeout(String key) {
        return HTTPSamplerBase.CONNECT_TIMEOUT.equals(key) || HTTPSamplerBase.RESPONSE_TIMEOUT.equals(key);
    }

    private static boolean canRemoveOnBlank(TestElement target, String key) {
        return (target instanceof ThreadGroup && (ThreadGroup.DELAY.equals(key) || ThreadGroup.RAMP_TIME.equals(key)))
                || (isHttpElement(target) && (HTTPSamplerBase.PORT.equals(key) || isHttpTimeout(key)));
    }

    void write(TestElement target, String key, String value) {
        String literal = value.trim();
        if (literal.isEmpty() && canRemoveOnBlank(target, key)) {
            target.removeProperty(key);
            return;
        }
        if (this == STRING || EXPRESSION.matcher(value).find()) {
            target.setProperty(key, value);
            return;
        }
        if (literal.isEmpty()) {
            throw new JMeterTreeMutator.InvalidScalarValueException(
                    "Cannot clear " + key + " with a blank value. Expected " + expected + ".");
        }
        JMeterProperty parsed;
        try {
            parsed = switch (this) {
                case BOOLEAN -> {
                    if (!"true".equalsIgnoreCase(literal) && !"false".equalsIgnoreCase(literal)) {
                        throw new NumberFormatException();
                    }
                    yield new BooleanProperty(key, Boolean.parseBoolean(literal));
                }
                case INTEGER -> new IntegerProperty(key, Integer.parseInt(literal));
                case LOOP_COUNT -> {
                    int count = Integer.parseInt(literal);
                    yield count < 0 ? new IntegerProperty(key, LoopController.INFINITE_LOOP_COUNT)
                            : new StringProperty(key, literal.startsWith("-") ? "0" : literal);
                }
                case LONG -> new LongProperty(key, Long.parseLong(literal));
                case FLOAT -> {
                    float number = Float.parseFloat(literal);
                    if (!Float.isFinite(number)) throw new NumberFormatException();
                    yield new FloatProperty(key, number);
                }
                case DOUBLE -> {
                    double number = Double.parseDouble(literal);
                    if (!Double.isFinite(number)) throw new NumberFormatException();
                    yield new DoubleProperty(key, number);
                }
                case STRING -> throw new IllegalStateException();
            };
        } catch (NumberFormatException e) {
            throw new JMeterTreeMutator.InvalidScalarValueException(
                    "Invalid value '" + value + "' for " + key + ". Expected " + expected + ".");
        }
        target.setProperty(parsed);
    }
}
