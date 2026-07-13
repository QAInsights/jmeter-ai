package org.qainsights.jmeter.ai.agent.schema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Provider-neutral "schema grounding" catalog for JMeter elements.
 * <p>
 * Holds a curated level-1 hierarchy (category to element types with their valid
 * parent roles) that the agent receives up front, plus lazy per-type detail via
 * {@link #schemaFor(String)}. The catalog is intentionally a representative
 * summary rather than an exhaustive list of all 200+ JMeter element types.
 */
public final class SchemaGrounding {

    /** Element categories, each with the plural label used in the hierarchy summary. */
    public enum Category {
        THREAD_GROUP("thread_groups"),
        SAMPLER("samplers"),
        CONTROLLER("controllers"),
        TIMER("timers"),
        ASSERTION("assertions"),
        LISTENER("listeners"),
        CONFIG("config_elements"),
        PRE_PROCESSOR("preprocessors"),
        POST_PROCESSOR("postprocessors");

        private final String label;

        Category(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** A single catalog entry: a logical element type plus its grounding metadata. */
    public static final class ElementType {
        private final String type;
        private final Category category;
        private final String addAlias;
        private final String description;
        private final List<String> validParents;

        ElementType(String type, Category category, String addAlias, String description, List<String> validParents) {
            this.type = type;
            this.category = category;
            this.addAlias = addAlias;
            this.description = description;
            this.validParents = Collections.unmodifiableList(new ArrayList<>(validParents));
        }

        public String getType() {
            return type;
        }

        public Category getCategory() {
            return category;
        }

        /** The {@code JMeterElementManager} synonym key used to actually add this element. */
        public String getAddAlias() {
            return addAlias;
        }

        public String getDescription() {
            return description;
        }

        public List<String> getValidParents() {
            return validParents;
        }
    }

    private static final Map<Category, List<String>> DEFAULT_PARENTS = defaultParents();

    private final Map<String, ElementType> byName = new LinkedHashMap<>();
    private final Map<Category, List<ElementType>> byCategory = new EnumMap<>(Category.class);

    public SchemaGrounding() {
        buildCatalog();
    }

    /** Case-insensitive lookup by logical type name; {@code null} if unknown. */
    public ElementType get(String type) {
        return type == null ? null : byName.get(type.toLowerCase(Locale.ROOT));
    }

    public boolean isKnown(String type) {
        return get(type) != null;
    }

    public List<ElementType> byCategory(Category category) {
        return Collections.unmodifiableList(byCategory.getOrDefault(category, Collections.emptyList()));
    }

    public Collection<ElementType> all() {
        return Collections.unmodifiableCollection(byName.values());
    }

    /** Valid parent roles for a type, or an empty list if the type is unknown. */
    public List<String> validParentsFor(String type) {
        ElementType et = get(type);
        return et == null ? Collections.emptyList() : et.getValidParents();
    }

    /**
     * Compact JSON-like level-1 hierarchy suitable for the agent system prompt:
     * category to type to {@code valid_parents}.
     */
    public String hierarchySummary() {
        StringBuilder sb = new StringBuilder("{\n");
        boolean firstCategory = true;
        for (Category category : Category.values()) {
            List<ElementType> list = byCategory.get(category);
            if (list == null || list.isEmpty()) {
                continue;
            }
            if (!firstCategory) {
                sb.append(",\n");
            }
            firstCategory = false;
            sb.append("  \"").append(category.label()).append("\": {");
            for (int i = 0; i < list.size(); i++) {
                ElementType et = list.get(i);
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append("\"").append(et.getType()).append("\": {\"valid_parents\": ")
                        .append(jsonArray(et.getValidParents())).append("}");
            }
            sb.append("}");
        }
        sb.append("\n}");
        return sb.toString();
    }

    /** Lazy per-type detail; {@code null} if the type is unknown. */
    public String schemaFor(String type) {
        ElementType et = get(type);
        if (et == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder()
                .append("Element type: ").append(et.getType()).append("\n")
                .append("Category: ").append(et.getCategory().label()).append("\n")
                .append("Valid parents: ").append(jsonArray(et.getValidParents())).append("\n")
                .append("Description: ").append(et.getDescription()).append("\n");
        String properties = ElementPropertyCatalog.describe(et.getType());
        if (properties.isEmpty()) {
            sb.append("Properties: add the element first (defaults), then inspect the live instance with "
                    + "get_element_config and set values with update_element_property.");
        } else {
            sb.append(properties).append("\n")
                    .append("For anything not listed, inspect the live element with get_element_config.");
        }
        return sb.toString();
    }

    private static String jsonArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("\"").append(values.get(i)).append("\"");
        }
        return sb.append("]").toString();
    }

    private void add(Category category, String type, String addAlias, String description) {
        add(category, type, addAlias, description, DEFAULT_PARENTS.get(category));
    }

    private void add(Category category, String type, String addAlias, String description, List<String> parents) {
        ElementType et = new ElementType(type, category, addAlias, description, parents);
        byName.put(type.toLowerCase(Locale.ROOT), et);
        byCategory.computeIfAbsent(category, k -> new ArrayList<>()).add(et);
    }

    private static Map<Category, List<String>> defaultParents() {
        Map<Category, List<String>> map = new EnumMap<>(Category.class);
        map.put(Category.THREAD_GROUP, Arrays.asList("TestPlan"));
        map.put(Category.SAMPLER, Arrays.asList("ThreadGroup", "Controller"));
        map.put(Category.CONTROLLER, Arrays.asList("ThreadGroup", "Controller"));
        map.put(Category.TIMER, Arrays.asList("ThreadGroup", "Controller", "Sampler"));
        map.put(Category.ASSERTION, Arrays.asList("ThreadGroup", "Controller", "Sampler"));
        map.put(Category.LISTENER, Arrays.asList("TestPlan", "ThreadGroup", "Controller", "Sampler"));
        map.put(Category.CONFIG, Arrays.asList("TestPlan", "ThreadGroup", "Controller", "Sampler"));
        map.put(Category.PRE_PROCESSOR, Arrays.asList("ThreadGroup", "Controller", "Sampler"));
        map.put(Category.POST_PROCESSOR, Arrays.asList("ThreadGroup", "Controller", "Sampler"));
        return map;
    }

    private void buildCatalog() {
        // Thread groups
        add(Category.THREAD_GROUP, "ThreadGroup", "threadgroup", "Defines concurrency: threads, ramp-up, loops.");

        // Samplers
        add(Category.SAMPLER, "HTTPSamplerProxy", "httpsampler", "Sends an HTTP/HTTPS request.");
        add(Category.SAMPLER, "JDBCSampler", "jdbcrequest", "Executes a SQL query against a JDBC connection.");
        add(Category.SAMPLER, "JSR223Sampler", "jsr223sampler", "Runs a JSR223 (e.g. Groovy) script as a sampler.");
        add(Category.SAMPLER, "DebugSampler", "debugsampler", "Emits JMeter/variable values for debugging.");
        add(Category.SAMPLER, "TCPSampler", "tcpsampler", "Sends a request over a raw TCP socket.");
        add(Category.SAMPLER, "FTPSampler", "ftprequest", "Performs an FTP get/put request.");
        add(Category.SAMPLER, "JavaSampler", "javarequest", "Invokes a Java sampler client class.");
        add(Category.SAMPLER, "SystemSampler", "osprocesssampler", "Runs an OS process / shell command.");

        // Controllers
        add(Category.CONTROLLER, "LoopController", "loopcontroller", "Repeats children a fixed number of times.");
        add(Category.CONTROLLER, "IfController", "ifcontroller", "Runs children when a condition is true.");
        add(Category.CONTROLLER, "WhileController", "whilecontroller", "Loops children while a condition holds.");
        add(Category.CONTROLLER, "TransactionController", "transactioncontroller",
                "Groups children into a measured transaction.");
        add(Category.CONTROLLER, "RunTime", "runtimecontroller", "Runs children for a fixed duration.");
        add(Category.CONTROLLER, "OnceOnlyController", "onceonlycontroller", "Runs children only on the first iteration.");
        add(Category.CONTROLLER, "ForeachController", "foreachcontroller", "Iterates over a set of variables.");
        add(Category.CONTROLLER, "ModuleController", "modulecontroller", "Runs a referenced test fragment.");
        add(Category.CONTROLLER, "InterleaveControl", "interleavecontroller", "Alternates among children per iteration.");
        add(Category.CONTROLLER, "RandomController", "randomcontroller", "Runs one random child per iteration.");
        add(Category.CONTROLLER, "ThroughputController", "throughputcontroller", "Runs children a percentage of the time.");
        add(Category.CONTROLLER, "SwitchController", "switchcontroller", "Runs the child selected by a switch value.");
        add(Category.CONTROLLER, "GenericController", "simplecontroller", "Plain container for grouping elements.");

        // Timers
        add(Category.TIMER, "ConstantTimer", "constanttimer", "Fixed think-time delay before each sampler.");
        add(Category.TIMER, "UniformRandomTimer", "uniformrandomtimer", "Random delay with a uniform distribution.");
        add(Category.TIMER, "GaussianRandomTimer", "gaussianrandomtimer", "Random delay with a gaussian distribution.");
        add(Category.TIMER, "PoissonRandomTimer", "poissonrandomtimer", "Random delay with a poisson distribution.");
        add(Category.TIMER, "ConstantThroughputTimer", "constantthroughputtimer",
                "Paces samplers to a target throughput; applies at the ThreadGroup scope.",
                Arrays.asList("ThreadGroup"));
        add(Category.TIMER, "JSR223Timer", "jsr223timer", "Computes the delay via a JSR223 script.");

        // Assertions
        add(Category.ASSERTION, "ResponseAssertion", "responseassert", "Validates response text/codes against patterns.");
        add(Category.ASSERTION, "JSONPathAssertion", "jsonassertion", "Validates a JSON response via JSONPath.");
        add(Category.ASSERTION, "DurationAssertion", "durationassertion", "Fails if response time exceeds a limit.");
        add(Category.ASSERTION, "SizeAssertion", "sizeassertion", "Validates the response size in bytes.");
        add(Category.ASSERTION, "XPathAssertion", "xpathassertion", "Validates an XML response via XPath.");
        add(Category.ASSERTION, "JSR223Assertion", "jsr223assertion", "Validates the response via a JSR223 script.");

        // Listeners (logical type ids; many share the ResultCollector model)
        add(Category.LISTENER, "ViewResultsTree", "viewresultstree", "Shows per-sample request/response details.");
        add(Category.LISTENER, "AggregateReport", "aggregatereport", "Aggregated latency/throughput statistics.");
        add(Category.LISTENER, "SummaryReport", "summaryreport", "Summary statistics per sampler.");
        add(Category.LISTENER, "BackendListener", "backendlistener", "Streams metrics to a backend (e.g. InfluxDB).");

        // Config elements
        add(Category.CONFIG, "CSVDataSet", "csvdataset", "Reads parametrization data from a CSV file.");
        add(Category.CONFIG, "HeaderManager", "headermanager", "Adds HTTP headers to in-scope samplers.");
        add(Category.CONFIG, "CookieManager", "cookiemanager", "Manages HTTP cookies for the thread.");
        add(Category.CONFIG, "CacheManager", "cachemanager", "Simulates browser caching behaviour.");
        add(Category.CONFIG, "ConfigTestElement", "httpdefaults", "HTTP Request Defaults for in-scope samplers.");
        add(Category.CONFIG, "Arguments", "arguments", "User Defined Variables for the scope.");
        add(Category.CONFIG, "AuthManager", "httpauthorizationmanager", "Supplies HTTP authorization credentials.");
        add(Category.CONFIG, "CounterConfig", "counterconfig", "Generates an incrementing counter variable.");
        add(Category.CONFIG, "RandomVariableConfig", "randomvariable", "Generates a random variable value.");

        // Pre-processors
        add(Category.PRE_PROCESSOR, "JSR223PreProcessor", "jsr223preprocessor", "Runs a JSR223 script before the sampler.");
        add(Category.PRE_PROCESSOR, "UserParameters", "userparameters", "Sets per-thread user parameter values.");
        add(Category.PRE_PROCESSOR, "RegExUserParameters", "regexuserparameters",
                "Sets parameters from regex capture groups.");

        // Post-processors / extractors
        add(Category.POST_PROCESSOR, "RegexExtractor", "regexextractor", "Extracts a value via a regular expression.");
        add(Category.POST_PROCESSOR, "XPathExtractor", "xpathextractor", "Extracts a value from XML via XPath.");
        add(Category.POST_PROCESSOR, "JSONPostProcessor", "jsonpathextractor", "Extracts a value from JSON via JSONPath.");
        add(Category.POST_PROCESSOR, "BoundaryExtractor", "boundaryextractor", "Extracts text between two boundaries.");
        add(Category.POST_PROCESSOR, "HtmlExtractor", "htmlextractor", "Extracts a value from HTML via a CSS/JQuery query.");
        add(Category.POST_PROCESSOR, "JSR223PostProcessor", "jsr223postprocessor",
                "Runs a JSR223 script after the sampler.");
        add(Category.POST_PROCESSOR, "DebugPostProcessor", "debugpostprocessor",
                "Adds debugging variables to the sample result.");
    }
}
