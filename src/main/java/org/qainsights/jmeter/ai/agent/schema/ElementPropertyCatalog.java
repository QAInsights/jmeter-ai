package org.qainsights.jmeter.ai.agent.schema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Curated catalog of the most commonly edited JMeter property keys per element
 * type, so the agent can set values with {@code update_element_property} using
 * exact keys instead of guessing. Keys match JMeter's real property names (the
 * same keys {@code get_element_config} reports for a live element).
 * <p>
 * This is a representative subset of high-value types, not an exhaustive list.
 * For any type not covered here, the agent should add the element and inspect it
 * live with {@code get_element_config}.
 */
public final class ElementPropertyCatalog {

    /** A single settable property: its exact key, value type and a short description. */
    public static final class Property {
        private final String key;
        private final String type;
        private final String description;

        Property(String key, String type, String description) {
            this.key = key;
            this.type = type;
            this.description = description;
        }

        public String getKey() {
            return key;
        }

        public String getType() {
            return type;
        }

        public String getDescription() {
            return description;
        }
    }

    private static final Map<String, List<Property>> BY_TYPE = build();

    private ElementPropertyCatalog() {
    }

    /** Common properties for a logical type (case-insensitive); empty if none curated. */
    public static List<Property> propertiesFor(String type) {
        if (type == null) {
            return Collections.emptyList();
        }
        return BY_TYPE.getOrDefault(type.toLowerCase(Locale.ROOT), Collections.emptyList());
    }

    /**
     * Renders a human-readable "Common properties" block for the given type, or an
     * empty string when no properties are curated for it.
     */
    public static String describe(String type) {
        List<Property> props = propertiesFor(type);
        if (props.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("Common properties (key : type - description):\n");
        for (Property p : props) {
            sb.append("- ").append(p.getKey()).append(" : ").append(p.getType())
                    .append(" - ").append(p.getDescription()).append('\n');
        }
        sb.append("Set any of these with update_element_property; keys are case-sensitive.");
        return sb.toString();
    }

    private static Map<String, List<Property>> build() {
        Map<String, List<Property>> map = new LinkedHashMap<>();

        put(map, "HTTPSamplerProxy",
                p("HTTPSampler.domain", "string", "Server name or IP (no scheme), e.g. example.com."),
                p("HTTPSampler.port", "int", "Server port; blank for protocol default."),
                p("HTTPSampler.protocol", "string", "http or https."),
                p("HTTPSampler.path", "string", "Request path, e.g. /login."),
                p("HTTPSampler.method", "string", "HTTP method: GET, POST, PUT, DELETE, ..."),
                p("HTTPSampler.contentEncoding", "string", "Request body/content encoding, e.g. UTF-8."),
                p("HTTPSampler.follow_redirects", "bool", "Follow HTTP redirects (true/false)."),
                p("HTTPSampler.use_keepalive", "bool", "Reuse the connection (Keep-Alive)."),
                p("HTTPSampler.postBodyRaw", "bool", "Send the raw body instead of form parameters."),
                p("HTTPSampler.connect_timeout", "int", "Connect timeout in milliseconds."),
                p("HTTPSampler.response_timeout", "int", "Response timeout in milliseconds."));

        put(map, "ThreadGroup",
                p("ThreadGroup.num_threads", "int", "Number of virtual users (threads)."),
                p("ThreadGroup.ramp_time", "int", "Ramp-up period in seconds."),
                p("ThreadGroup.scheduler", "bool", "Enable the scheduler (needed for duration/delay)."),
                p("ThreadGroup.duration", "int", "Run duration in seconds (scheduler must be true)."),
                p("ThreadGroup.delay", "int", "Startup delay in seconds (scheduler must be true)."),
                p("ThreadGroup.same_user_on_next_iteration", "bool", "Reuse the same user across iterations."));

        put(map, "ConstantTimer",
                p("ConstantTimer.delay", "int", "Fixed delay before each sampler, in milliseconds."));

        put(map, "UniformRandomTimer",
                p("ConstantTimer.delay", "int", "Constant offset added to the random delay, in ms."),
                p("RandomTimer.range", "double", "Maximum random component of the delay, in ms."));

        put(map, "ConstantThroughputTimer",
                p("throughput", "double", "Target throughput in samples per minute."),
                p("calcMode", "int", "Throughput scope: 0=this thread only, others share across threads."));

        put(map, "LoopController",
                p("LoopController.loops", "int", "Iteration count; -1 or continue_forever loops indefinitely."),
                p("LoopController.continue_forever", "bool", "Loop forever regardless of the loop count."));

        put(map, "IfController",
                p("IfController.condition", "string", "Condition expression or (with useExpression) a __groovy/JS expr."),
                p("IfController.useExpression", "bool", "Interpret the condition as an expression (recommended)."),
                p("IfController.evaluateAll", "bool", "Evaluate the condition for every child."));

        put(map, "WhileController",
                p("WhileController.condition", "string", "Loop while this expression is true (blank/LAST supported)."));

        put(map, "TransactionController",
                p("TransactionController.parent", "bool", "Generate a parent sample for the transaction."),
                p("TransactionController.includeTimers", "bool", "Include timer/processor time in the transaction."));

        put(map, "CSVDataSet",
                p("filename", "string", "Path to the CSV file."),
                p("variableNames", "string", "Comma-separated variable names for the columns."),
                p("delimiter", "string", "Column delimiter (default ,)."),
                p("ignoreFirstLine", "bool", "Skip the first line (header)."),
                p("recycle", "bool", "Restart from the top at end of file."),
                p("stopThread", "bool", "Stop the thread at end of file."),
                p("shareMode", "string", "Sharing scope: all threads, current thread group, or current thread."));

        put(map, "ResponseAssertion",
                p("Assertion.test_field", "string", "Field to test, e.g. Assertion.response_data or Assertion.response_code."),
                p("Assertion.test_type", "int", "Match mode bitmask (2=Contains, 8=Equals, 16=Substring, +4 to negate)."));

        put(map, "RegexExtractor",
                p("RegexExtractor.refname", "string", "Variable name to store the captured value."),
                p("RegexExtractor.regex", "string", "Regular expression with a capture group."),
                p("RegexExtractor.template", "string", "Group template, e.g. $1$."),
                p("RegexExtractor.match_number", "int", "Match to use: 0=random, n=nth, -1=all."),
                p("RegexExtractor.default", "string", "Default value when no match is found."));

        put(map, "JSONPostProcessor",
                p("JSONPostProcessor.referenceNames", "string", "Variable name(s) to store extracted value(s)."),
                p("JSONPostProcessor.jsonPathExprs", "string", "JSONPath expression(s), e.g. $.token."),
                p("JSONPostProcessor.match_numbers", "int", "Match to use: 0=random, n=nth, -1=all."),
                p("JSONPostProcessor.defaultValues", "string", "Default value when no match is found."));

        put(map, "DebugSampler",
                p("displayJMeterProperties", "bool", "Include JMeter properties in the output."),
                p("displayJMeterVariables", "bool", "Include JMeter variables in the output."),
                p("displaySystemProperties", "bool", "Include system properties in the output."));

        put(map, "JSR223Sampler",
                p("script", "string", "Script body to execute."),
                p("scriptLanguage", "string", "Scripting language, e.g. groovy, javascript, beanshell."),
                p("parameters", "string", "String available to the script as the Parameters/args variable."),
                p("cacheKey", "string", "Compiled-script cache key; blank disables caching."),
                p("filename", "string", "Optional external script file path (used instead of script)."));

        put(map, "JSR223PreProcessor",
                p("script", "string", "Script body to execute before the sampler runs."),
                p("scriptLanguage", "string", "Scripting language, e.g. groovy, javascript, beanshell."),
                p("parameters", "string", "String available to the script as the Parameters/args variable."),
                p("cacheKey", "string", "Compiled-script cache key; blank disables caching."),
                p("filename", "string", "Optional external script file path (used instead of script)."));

        put(map, "JSR223PostProcessor",
                p("script", "string", "Script body to execute after the sampler runs."),
                p("scriptLanguage", "string", "Scripting language, e.g. groovy, javascript, beanshell."),
                p("parameters", "string", "String available to the script as the Parameters/args variable."),
                p("cacheKey", "string", "Compiled-script cache key; blank disables caching."),
                p("filename", "string", "Optional external script file path (used instead of script)."));

        put(map, "DurationAssertion",
                p("DurationAssertion.duration", "long", "Maximum allowed sample duration, in milliseconds."));

        put(map, "SizeAssertion",
                p("SizeAssertion.size", "long", "Response size to compare against, in bytes."),
                p("SizeAssertion.operator", "int",
                        "Comparison: 1=equal, 2=not equal, 3=greater than, 4=less than, 5=greater-or-equal, 6=less-or-equal."));

        put(map, "JSONPathAssertion",
                p("JSON_PATH", "string", "JSONPath expression to evaluate, e.g. $.status."),
                p("EXPECTED_VALUE", "string", "Expected value at that path (only checked if JSONVALIDATION is true)."),
                p("JSONVALIDATION", "bool", "Enable validating the extracted value against EXPECTED_VALUE."),
                p("EXPECT_NULL", "bool", "Assert the extracted value is null."),
                p("INVERT", "bool", "Invert the assertion result."),
                p("ISREGEX", "bool", "Treat EXPECTED_VALUE as a regular expression."));

        put(map, "GaussianRandomTimer",
                p("ConstantTimer.delay", "int", "Constant offset added to the random delay, in ms."),
                p("RandomTimer.range", "double", "Standard deviation of the Gaussian random delay, in ms."));

        return Collections.unmodifiableMap(map);
    }

    private static void put(Map<String, List<Property>> map, String type, Property... props) {
        map.put(type.toLowerCase(Locale.ROOT),
                Collections.unmodifiableList(new ArrayList<>(Arrays.asList(props))));
    }

    private static Property p(String key, String type, String description) {
        return new Property(key, type, description);
    }
}
