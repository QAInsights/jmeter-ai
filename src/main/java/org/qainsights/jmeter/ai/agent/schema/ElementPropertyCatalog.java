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

    /** A single settable property: its exact key, value type, description and (if enum-like) allowed values. */
    public static final class Property {
        private final String key;
        private final String type;
        private final String description;
        private final List<String> allowedValues;

        Property(String key, String type, String description, List<String> allowedValues) {
            this.key = key;
            this.type = type;
            this.description = description;
            this.allowedValues = allowedValues;
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

        /** Literal values (or "value (meaning)" pairs) this property accepts; empty when free-form. */
        public List<String> getAllowedValues() {
            return allowedValues;
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

    private static final Map<String, List<String>> LIST_PROPERTIES_BY_TYPE = buildListProperties();

    /**
     * True when {@code property} is a curated flat string-list property for
     * {@code type} - i.e. safe to write with {@code set_property_list}. Kept
     * as an explicit allowlist (rather than sniffing the live property's
     * runtime shape) so an accidentally-empty structured collection (e.g. a
     * header/argument list with zero entries) is never mistaken for one.
     */
    public static boolean isFlatStringListProperty(String type, String property) {
        if (type == null || property == null) {
            return false;
        }
        return LIST_PROPERTIES_BY_TYPE
                .getOrDefault(type.toLowerCase(Locale.ROOT), Collections.emptyList())
                .contains(property);
    }

    private static Map<String, List<String>> buildListProperties() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("responseassertion", Collections.singletonList("Asserion.test_strings"));
        return Collections.unmodifiableMap(map);
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
                    .append(" - ").append(p.getDescription());
            if (!p.getAllowedValues().isEmpty()) {
                sb.append(" Allowed: ").append(String.join(", ", p.getAllowedValues())).append('.');
            }
            sb.append('\n');
        }
        sb.append("Set any of these with update_element_property; keys are case-sensitive.");
        return sb.toString();
    }

    private static Map<String, List<Property>> build() {
        Map<String, List<Property>> map = new LinkedHashMap<>();

        put(map, "HTTPSamplerProxy",
                p("HTTPSampler.domain", "string", "Server name or IP (no scheme), e.g. example.com."),
                p("HTTPSampler.port", "int", "Server port; blank for protocol default."),
                p("HTTPSampler.protocol", "string", "Scheme used to build the request URL.", "http", "https"),
                p("HTTPSampler.path", "string", "Request path, e.g. /login."),
                p("HTTPSampler.method", "string", "HTTP method to use.",
                        "GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS", "TRACE", "PATCH"),
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
                p("calcMode", "int", "Throughput scope for pacing (JMeter 5.6.x uses the plain int form below).",
                        "0 (this thread only)", "1 (all active threads)",
                        "2 (all active threads in current thread group)",
                        "3 (all active threads, shared)",
                        "4 (all active threads in current thread group, shared)"));

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
                p("shareMode", "string",
                        "Sharing scope; use one of the literal values below, or a specific thread group name "
                                + "to share with just that group.",
                        "shareMode.all", "shareMode.group", "shareMode.thread"));

        put(map, "ResponseAssertion",
                p("Assertion.test_field", "string", "Field of the sample result to test.",
                        "Assertion.response_data", "Assertion.response_code", "Assertion.response_message",
                        "Assertion.response_headers", "Assertion.request_headers", "Assertion.request_data",
                        "Assertion.response_data_as_document", "Assertion.sample_label"),
                p("Assertion.test_type", "int",
                        "Bitmask: pick exactly one base value, optionally add 4 and/or 32 as modifiers.",
                        "1 (Matches)", "2 (Contains)", "8 (Equals)", "16 (Substring)",
                        "4 (Not, additive modifier)", "32 (Or, additive modifier)"),
                p("Asserion.test_strings", "list<string>",
                        "Patterns to test (note: JMeter's own property name has a typo, 'Asserion'). "
                                + "Not settable via update_element_property - use set_property_list instead."));

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
                p("scriptLanguage", "string",
                        "Scripting engine; availability depends on installed JSR223 engines. groovy is bundled and recommended.",
                        "groovy", "beanshell", "javascript", "jexl3"),
                p("parameters", "string", "String available to the script as the Parameters/args variable."),
                p("cacheKey", "string", "Compiled-script cache key; blank disables caching."),
                p("filename", "string", "Optional external script file path (used instead of script)."));

        put(map, "JSR223PreProcessor",
                p("script", "string", "Script body to execute before the sampler runs."),
                p("scriptLanguage", "string",
                        "Scripting engine; availability depends on installed JSR223 engines. groovy is bundled and recommended.",
                        "groovy", "beanshell", "javascript", "jexl3"),
                p("parameters", "string", "String available to the script as the Parameters/args variable."),
                p("cacheKey", "string", "Compiled-script cache key; blank disables caching."),
                p("filename", "string", "Optional external script file path (used instead of script)."));

        put(map, "JSR223PostProcessor",
                p("script", "string", "Script body to execute after the sampler runs."),
                p("scriptLanguage", "string",
                        "Scripting engine; availability depends on installed JSR223 engines. groovy is bundled and recommended.",
                        "groovy", "beanshell", "javascript", "jexl3"),
                p("parameters", "string", "String available to the script as the Parameters/args variable."),
                p("cacheKey", "string", "Compiled-script cache key; blank disables caching."),
                p("filename", "string", "Optional external script file path (used instead of script)."));

        put(map, "DurationAssertion",
                p("DurationAssertion.duration", "long", "Maximum allowed sample duration, in milliseconds."));

        put(map, "SizeAssertion",
                p("SizeAssertion.size", "long", "Response size to compare against, in bytes."),
                p("SizeAssertion.operator", "int", "Comparison operator.",
                        "1 (equal)", "2 (not equal)", "3 (greater than)", "4 (less than)",
                        "5 (greater-or-equal)", "6 (less-or-equal)"));

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
        return new Property(key, type, description, Collections.emptyList());
    }

    /** Overload for enum-like properties; each entry is a literal value or a "value (meaning)" pair. */
    private static Property p(String key, String type, String description, String... allowedValues) {
        return new Property(key, type, description,
                Collections.unmodifiableList(new ArrayList<>(Arrays.asList(allowedValues))));
    }
}
