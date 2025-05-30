package org.nfe.jmeter.ai.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.nfe.jmeter.ai.optimizer.OptimizeRequestHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for handling natural language requests to add JMeter
 * elements to the test plan.
 */
public class JMeterElementRequestHandler {
    private static final Logger log = LoggerFactory.getLogger(JMeterElementRequestHandler.class);

    // Pattern to match requests to add a JMeter element
    private static final Pattern ADD_ELEMENT_PATTERN = Pattern.compile(
            "(?i)\\b(add|create|insert|include)\\b\\s+(?:\\b(?:a|an)\\b\\s+)?([a-z0-9\\s-]{2,}?)(?:\\s+(?:called|named|with name|with the name)?\\s+[\"']?([^\"']+?)[\"']?)?(?:\\s*$|\\s+(?:to|in)\\b)");

    // Common synonyms and variations for element types
    private static final Map<String, List<String>> ELEMENT_SYNONYMS = new LinkedHashMap<>();

    static {
        // Initialize element synonyms
        initializeElementSynonyms();
    }

    /**
     * Initializes the map of element synonyms.
     */
    private static void initializeElementSynonyms() {
        // Samplers (most common)
        addSynonyms("httpsampler", "http sampler", "http request", "web request", "http", "request",
                "http request sampler", "web sampler", "rest request", "api request", "http api", "rest api");

        // Controllers (most common)
        addSynonyms("loopcontroller", "loop controller", "loop", "repeat controller", "for loop",
                "iteration controller", "repeat");
        addSynonyms("ifcontroller", "if controller", "conditional controller", "condition", "if statement",
                "if condition", "branch controller");
        addSynonyms("whilecontroller", "while controller", "while loop", "while", "do while", "repeat until",
                "conditional loop");
        addSynonyms("transactioncontroller", "transaction controller", "transaction", "tx controller", "tx",
                "business transaction");
        addSynonyms("runtimecontroller", "runtime controller", "runtime", "timed controller", "duration controller",
                "time-based controller");

        // Config Elements (most common)
        addSynonyms("headermanager", "header manager", "http headers", "headers", "request headers", "custom headers",
                "http header config");
        addSynonyms("csvdataset", "csv data set", "csv", "data set", "csv config", "test data", "data source",
                "csv data source");

        // Thread Groups (essential)
        addSynonyms("threadgroup", "thread group", "users", "virtual users", "vusers", "user group", "load generator",
                "user threads", "concurrent users");

        // Assertions (most common)
        addSynonyms("responseassert", "response assertion", "response validator", "text assertion", "content assertion",
                "verify response", "check response");
        addSynonyms("jsonassertion", "json assertion", "json path assertion", "json validator", "json check",
                "verify json", "json response check");
        addSynonyms("durationassertion", "duration assertion", "response time assertion", "time assertion",
                "timeout assertion", "performance assertion", "timing check");
        addSynonyms("sizeassertion", "size assertion", "response size assertion", "byte size assertion",
                "content length assertion", "payload size check");
        addSynonyms("xpathassertion", "xpath assertion", "xml assertion", "xml validator", "xml check", "verify xml",
                "xml response check");

        // Timers (most common)
        addSynonyms("constanttimer", "constant timer", "fixed timer", "delay", "wait", "timer", "pause", "sleep",
                "think time");
        addSynonyms("uniformrandomtimer", "uniform random timer", "random timer", "uniform timer", "random delay",
                "variable delay");
        addSynonyms("gaussianrandomtimer", "gaussian random timer", "gaussian timer", "normal distribution timer",
                "bell curve timer");
        addSynonyms("poissonrandomtimer", "poisson random timer", "poisson timer", "poisson distribution");

        // Extractors (most common)
        addSynonyms("regexextractor", "regex extractor", "regular expression extractor", "regex", "regexp",
                "pattern extractor", "text extractor");
        addSynonyms("xpathextractor", "xpath extractor", "xml extractor", "xpath", "xml path extractor", "xml parser");
        addSynonyms("jsonpathextractor", "json extractor", "jsonpath extractor", "json path extractor", "jsonpath",
                "json parser", "json data extractor");
        addSynonyms("boundaryextractor", "boundary extractor", "text extractor", "boundary", "string extractor",
                "substring extractor");

        // Listeners (most common)
        addSynonyms("viewresultstree", "listener", "view results tree", "results tree", "view results",
                "results viewer", "response viewer", "debug viewer", "request response viewer");
        addSynonyms("aggregatereport", "aggregate report", "summary report", "statistics", "stats",
                "performance report", "metrics report");

        // JSR223 Elements
        addSynonyms("jsr223sampler", "jsr223 sampler", "jsr223", "jsr 223", "jsr 223 sampler", "javascript sampler",
                "groovy sampler", "java sampler", "script sampler", "custom script", "code sampler");
        addSynonyms("jsr223preprocessor", "jsr223 preprocessor", "jsr223 pre processor", "jsr223 pre",
                "jsr 223 pre processor", "javascript preprocessor", "groovy preprocessor", "script preprocessor",
                "pre-request script");
        addSynonyms("jsr223postprocessor", "jsr223 postprocessor", "jsr223 post processor", "jsr223 post",
                "jsr 223 post processor", "javascript postprocessor", "groovy postprocessor", "script postprocessor",
                "post-request script");
        addSynonyms("jsr223assertion", "jsr223 assertion", "script assertion", "code assertion", "custom assertion",
                "programmatic assertion", "groovy assertion");
        addSynonyms("jsr223listener", "jsr223 listener", "script listener", "custom listener", "programmatic listener",
                "groovy listener");
        addSynonyms("jsr223timer", "jsr223 timer", "script timer", "custom timer", "programmatic timer",
                "groovy timer");

        // Additional elements from ELEMENT_CLASS_MAP
        addSynonyms("ftprequest", "ftp request", "ftp sampler", "ftp", "ftp client");
        addSynonyms("httprequest", "http request", "web request", "http", "request", "web test", "http test");
        addSynonyms("jdbcrequest", "jdbc request", "database request", "sql request", "db request", "database query",
                "sql query");
        addSynonyms("javarequest", "java request", "java sampler", "java test", "java method call");
        addSynonyms("ldaprequest", "ldap request", "ldap sampler", "directory request", "ldap query");
        addSynonyms("ldapeextendedrequest", "ldap extended request", "ldap ext sampler", "extended directory request");
        addSynonyms("accesslogsampler", "access log sampler", "log sampler", "log replay");
        addSynonyms("beanshellsampler", "beanshell sampler", "bsh sampler", "beanshell script", "bsh script");
        addSynonyms("tcpsampler", "tcp sampler", "tcp request", "socket request", "network request");
        addSynonyms("jmspublisher", "jms publisher", "jms publish", "message publisher", "queue publisher");
        addSynonyms("jmssubscriber", "jms subscriber", "jms subscribe", "message subscriber", "queue subscriber");
        addSynonyms("jmspointtopoint", "jms point to point", "jms p2p", "point to point messaging", "direct messaging");
        addSynonyms("junitrequest", "junit request", "junit sampler", "junit test", "java unit test");
        addSynonyms("mailreadersampler", "mail reader sampler", "email sampler", "pop3 sampler", "imap sampler",
                "email reader");
        addSynonyms("flowcontrolaction", "flow control action", "test action", "flow action", "test flow control");
        addSynonyms("smtpsampler", "smtp sampler", "smtp request", "email sender", "mail sender");
        addSynonyms("osprocesssampler", "os process sampler", "system sampler", "shell command", "command line",
                "process executor");
        addSynonyms("mongodbscript", "mongodb script", "mongo script", "mongodb query", "nosql query");
        addSynonyms("boltrequest", "bolt request", "bolt sampler", "neo4j request");
        addSynonyms("graphqlhttprequest", "graphql http request", "graphql request", "graphql sampler", "graphql api");
        addSynonyms("debugsampler", "debug sampler", "debug request", "debug info", "variable viewer");
        addSynonyms("ajpsampler", "ajp sampler", "ajp request", "apache jserv protocol", "tomcat connector");

        // Controllers
        addSynonyms("simplecontroller", "simple controller", "generic controller", "container", "group controller");
        addSynonyms("onceonlycontroller", "once only controller", "once controller", "one time controller",
                "single execution");
        addSynonyms("interleavecontroller", "interleave controller", "interleave", "alternate controller",
                "round robin controller");
        addSynonyms("randomcontroller", "random controller", "random", "random selection controller");
        addSynonyms("randomordercontroller", "random order controller", "random order", "shuffled order controller");
        addSynonyms("throughputcontroller", "throughput controller", "throughput", "percentage controller",
                "execution percentage");
        addSynonyms("switchcontroller", "switch controller", "switch", "case controller", "switch case");
        addSynonyms("foreachcontroller", "foreach controller", "for each controller", "iterator controller",
                "loop over items");
        addSynonyms("modulecontroller", "module controller", "module", "test fragment controller", "reusable section");
        addSynonyms("includecontroller", "include controller", "include", "external test plan", "test plan inclusion");
        addSynonyms("recordingcontroller", "recording controller", "recording", "proxy recorder", "http recorder");
        addSynonyms("criticalsectioncontroller", "critical section controller", "critical section", "mutex controller",
                "synchronized section");

        // Listeners and Visualizers
        addSynonyms("sampleresultsaveconfiguration", "sample result save configuration", "save configuration",
                "result saving options");
        addSynonyms("graphresults", "graph results", "graph visualizer", "results graph", "performance graph");
        addSynonyms("assertionresults", "assertion results", "assertion visualizer", "assertion failures",
                "validation results");
        addSynonyms("viewresultsintable", "view results in table", "table visualizer", "results table",
                "tabular results");
        addSynonyms("simpledatawriter", "simple data writer", "data writer", "results writer", "writer");
        addSynonyms("aggregategraph", "aggregate graph", "stat graph visualizer", "statistics graph", "metrics graph");
        addSynonyms("responsetimegraph", "response time graph", "response time visualizer", "latency graph",
                "timing graph");
        addSynonyms("mailervisualizer", "mailer visualizer", "mailer", "email notifier", "mail alert");
        addSynonyms("beanshelllistener", "beanshell listener", "bsh listener", "beanshell results processor");
        addSynonyms("summaryreport", "summary report", "summary", "test summary", "results summary");
        addSynonyms("saveresponsestoafile", "save responses", "result saver", "response saver");
        addSynonyms("generatesummaryresults", "generate summary results", "summariser", "console summarizer",
                "log summarizer");
        addSynonyms("comparisonassertionvisualizer", "comparison assertion visualizer", "comparison visualizer",
                "diff visualizer");
        addSynonyms("backendlistener", "backend listener", "backend", "metrics sender", "influxdb listener",
                "grafana exporter");

        // Config Elements
        addSynonyms("csvdatasetconfig", "csv data set config", "csv config", "test data config");
        addSynonyms("ftprequestdefaults", "ftp request defaults", "ftp defaults", "ftp config", "ftp settings");
        addSynonyms("dnscachemanager", "dns cache manager", "dns manager", "hostname resolver", "dns resolver");
        addSynonyms("httpauthorizationmanager", "http authorization manager", "auth manager", "authentication manager",
                "http auth");
        addSynonyms("cookiemanager", "cookie manager", "cookies", "http cookies", "browser cookies", "session cookies");
        addSynonyms("cachemanager", "cache manager", "http cache", "browser cache", "web cache");
        addSynonyms("httpdefaults", "http defaults", "http default config", "default http settings", "http config");
        addSynonyms("boltconnection", "bolt connection", "neo4j connection", "graph db connection");
        addSynonyms("counterconfig", "counter config", "counter", "sequence generator", "incrementing variable");
        addSynonyms("ftpconfig", "ftp config", "ftp configuration", "ftp connection settings");
        addSynonyms("authmanager", "auth manager", "authentication manager", "credentials manager", "login manager");
        addSynonyms("jdbcdatasource", "jdbc datasource", "database connection", "db connection", "sql connection");
        addSynonyms("javaconfig", "java config", "java configuration", "java sampler config");
        addSynonyms("keystoreconfig", "keystore config", "ssl config", "certificate config", "tls config");
        addSynonyms("ldapextconfig", "ldap ext config", "ldap extended config", "extended directory config");
        addSynonyms("ldapconfig", "ldap config", "directory config", "ldap connection config");
        addSynonyms("loginconfig", "login config", "login configuration", "authentication config");
        addSynonyms("randomvariable", "random variable", "random value", "random string", "random number");
        addSynonyms("simpleconfig", "simple config", "basic config", "generic config");
        addSynonyms("tcpconfig", "tcp config", "tcp configuration", "socket config", "network config");
        addSynonyms("arguments", "arguments", "variables", "user defined variables", "parameters", "test variables");

        // Assertions
        addSynonyms("jmespathassert", "jmespath assertion", "jmespath validator", "json jmespath check");
        addSynonyms("md5assertion", "md5 assertion", "md5 hex assertion", "hash assertion", "checksum assertion");
        addSynonyms("smimeassertion", "smime assertion", "secure email assertion", "email signature assertion");
        addSynonyms("xmlassertion", "xml assertion", "xml validation", "xml well-formed check");
        addSynonyms("xmlschemaassertion", "xml schema assertion", "xsd assertion", "xml schema validation");
        addSynonyms("beanshellassertion", "beanshell assertion", "bsh assertion", "script assertion");
        addSynonyms("compareassert", "compare assertion", "comparison assertion", "response comparison");
        addSynonyms("htmlassertion", "html assertion", "html validator", "html check", "web page validator");
        addSynonyms("xpath2assertion", "xpath2 assertion", "xpath 2.0 assertion", "xml xpath2 validator");

        // Pre-processors
        addSynonyms("userparameters", "user parameters", "user vars", "user variables", "parameter input");
        addSynonyms("anchormodifier", "anchor modifier", "html link modifier", "url anchor processor");
        addSynonyms("urlrewritingmodifier", "url rewriting modifier", "url rewrite", "url processor");
        addSynonyms("jdbcpreprocessor", "jdbc preprocessor", "sql preprocessor", "database pre-request");
        addSynonyms("regexuserparameters", "regex user parameters", "regex variables", "pattern variables");
        addSynonyms("sampletimeout", "sample timeout", "request timeout", "execution timeout");
        addSynonyms("beanshellpreprocessor", "beanshell preprocessor", "bsh preprocessor", "script pre-request");

        // Post-processors
        addSynonyms("htmlextractor", "html extractor", "html parser", "dom extractor", "css extractor");
        addSynonyms("jmespathextractor", "jmespath extractor", "jmespath parser", "json jmespath extractor");
        addSynonyms("debugpostprocessor", "debug postprocessor", "debug post processor", "debug extractor");
        addSynonyms("jdbcpostprocessor", "jdbc postprocessor", "sql postprocessor", "database post-request");
        addSynonyms("resultaction", "result action", "response action", "conditional action");
        addSynonyms("xpath2extractor", "xpath2 extractor", "xpath 2.0 extractor", "xml xpath2 parser");
        addSynonyms("beanshellpostprocessor", "beanshell postprocessor", "bsh postprocessor", "script post-request");
    }

    /**
     * Adds synonyms for an element type.
     * 
     * @param elementType The normalized element type
     * @param synonyms    The synonyms for the element type
     */
    private static void addSynonyms(String elementType, String... synonyms) {
        List<String> synonymList = new ArrayList<>();
        for (String synonym : synonyms) {
            synonymList.add(synonym.toLowerCase());
        }
        ELEMENT_SYNONYMS.put(elementType, synonymList);
    }

    /**
     * Processes a user message to determine if it's an element request.
     * 
     * @param message The user message
     * @return A response message if the request was handled, null otherwise
     */
    public static String processElementRequest(String message) {
        if (message == null || message.trim().isEmpty()) {
            return null;
        }

        log.info("JMeterElementRequestHandler processing message: '{}'", message);

        // Check if the message contains multiple instructions
        List<String> instructions = splitIntoInstructions(message);

        if (instructions.size() > 1) {
            log.info("Message contains multiple instructions: {}", instructions.size());
            return processMultipleInstructions(instructions);
        }

        // Process single instruction
        String response = processAddElementRequest(message);
        if (response != null) {
            log.info("Message handled as add element request");
            return response;
        }

        // log.info("Checking if message is an optimize request: '{}'", message);
        // response = processOptimizeTestPlanRequest(message);
        // if (response != null) {
        //     log.info("Message handled as optimize request, response: '{}'",
        //             response.length() > 50 ? response.substring(0, 50) + "..." : response);
        //     return response;
        // }

        log.info("Message not recognized as an element request: '{}'", message);
        // If the message doesn't match any of the patterns, return null
        // This will cause the message to be sent to the AI for processing
        return null;
    }

    /**
     * Splits a message into multiple instructions based on separators.
     * 
     * @param message The message to split
     * @return A list of instructions
     */
    private static List<String> splitIntoInstructions(String message) {
        List<String> instructions = new ArrayList<>();

        log.info("Original message: {}", message);

        // First, check if the message starts with an action verb (add, create, etc.)
        boolean startsWithAction = message.toLowerCase()
                .matches("^\\s*(?:add|insert|create|include)\\b.*");
        log.info("Message starts with action verb: {}", startsWithAction);

        if (startsWithAction) {
            // If the message starts with an action verb, we need to be careful about
            // splitting
            // For example: "add a thread group, HTTP request, and a timer"
            // We want to split this into: ["add a thread group", "HTTP request", "a timer"]
            // But we need to prepend "add" to the second and third instructions

            // First, extract the action verb
            String actionVerb = message.replaceAll("(?i)^\\s*(add|insert|create|include)\\b.*", "$1")
                    .trim();
            log.info("Action verb: {}", actionVerb);

            // Split by common separators
            String[] parts = message.split("(?i)\\s*(?:,|;)\\s+|\\s+and\\s+");
            log.info("Split into {} parts: {}", parts.length, java.util.Arrays.toString(parts));

            // Process each part
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i].trim();

                // Skip empty parts
                if (part.isEmpty()) {
                    continue;
                }

                // If this is not the first part and it doesn't start with an action verb,
                // prepend the action verb
                if (i > 0 && !part.toLowerCase().matches("^\\s*(?:add|insert|create|include)\\b.*")) {
                    part = actionVerb + " " + part;
                    log.info("Prepended action verb to part: {}", part);
                }

                instructions.add(part);
            }
        } else {
            // If the message doesn't start with an action verb, just split by common
            // separators
            String[] parts = message.split("(?i)\\s*(?:,|;)\\s+|\\s+and\\s+");

            // Add each part as an instruction
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    instructions.add(trimmed);
                }
            }
        }

        // If no parts were found, treat the whole message as one instruction
        if (instructions.isEmpty()) {
            instructions.add(message.trim());
        }

        log.info("Split message into {} instructions: {}", instructions.size(), instructions);
        return instructions;
    }

    /**
     * Processes multiple instructions and returns a combined response.
     * 
     * @param instructions The list of instructions to process
     * @return A combined response message
     */
    private static String processMultipleInstructions(List<String> instructions) {
        List<String> responses = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (String instruction : instructions) {
            log.info("Processing instruction: {}", instruction);

            // Skip instructions that are just separators
            if (instruction.matches("(?i)\\b(and|also|then|next)\\b")) {
                log.info("Skipping separator instruction: {}", instruction);
                continue;
            }

            // Process add element request
            String response = processAddElementRequest(instruction);
            if (response != null) {
                log.info("Processed add element request: {}", response);
                if (response.startsWith("I've added")) {
                    responses.add(response);
                } else {
                    errors.add(response);
                }
                continue;
            }

            // Process optimize test plan request
            // response = processOptimizeTestPlanRequest(instruction);
            // if (response != null) {
            //     log.info("Processed optimize test plan request: {}", response);
            //     responses.add(response);
            //     continue;
            // }

            // Try to extract just the element type from the instruction
            String elementType = extractElementType(instruction);
            if (elementType != null) {
                log.info("Extracted element type from instruction: {}", elementType);
                boolean success = JMeterElementManager.addElement(elementType, null);
                if (success) {
                    String elementName = JMeterElementManager.getDefaultNameForElement(elementType);
                    String successResponse = "I've added a " + elementName
                            + " to your test plan. You can now configure it as needed.";
                    log.info("Successfully added element: {}", successResponse);
                    responses.add(successResponse);
                } else {
                    String errorResponse = "I tried to add a "
                            + JMeterElementManager.getDefaultNameForElement(elementType) +
                            ", but encountered an error. " +
                            "Please make sure you have a test plan open and a node selected where the element can be added.";
                    log.info("Failed to add element: {}", errorResponse);
                    errors.add(errorResponse);
                }
                continue;
            }

            // Instruction not recognized
            log.info("Instruction not recognized: {}", instruction);
            errors.add("I couldn't understand what to do with: \"" + instruction + "\"");
        }

        // Build the combined response
        StringBuilder result = new StringBuilder();

        if (!responses.isEmpty()) {
            if (responses.size() == 1) {
                result.append(responses.get(0));
            } else {
                result.append("I've made the following changes:\n\n");
                for (int i = 0; i < responses.size(); i++) {
                    result.append(i + 1).append(". ").append(responses.get(i)).append("\n");
                }
            }
        }

        if (!errors.isEmpty()) {
            if (!responses.isEmpty()) {
                result.append("\n\nHowever, I encountered some issues:\n\n");
            }

            for (int i = 0; i < errors.size(); i++) {
                result.append(i + 1).append(". ").append(errors.get(i)).append("\n");
            }
        }

        log.info("Final response: {}", result.toString());
        return result.toString();
    }

    /**
     * Processes a user message to determine if it's requesting to add a JMeter
     * element.
     * 
     * @param message The user message to process
     * @return A response message if the request was handled, null otherwise
     */
    private static String processAddElementRequest(String message) {
        log.info("Processing add element request: {}", message);

        // Check if the message matches the add element pattern
        Matcher matcher = ADD_ELEMENT_PATTERN.matcher(message);
        if (!matcher.find()) {
            log.info("Message does not match add element pattern: {}", message);
            return null;
        }

        // Extract the element type and name
        String actionVerb = matcher.group(1);
        String requestedElementType = matcher.group(2).trim();
        String elementName = matcher.group(3);

        log.info("Regex match - Action verb: '{}', Element type: '{}', Element name: '{}'",
                actionVerb, requestedElementType, elementName);
        log.info("Extracted element type: {} and name: {}", requestedElementType, elementName);

        // Map the requested element type to a supported type
        String elementType = mapToSupportedElementType(requestedElementType);

        if (elementType == null) {
            log.info("Could not map requested element type: {} to a supported type", requestedElementType);

            // Try to extract the element type directly from the instruction
            elementType = extractElementType(message);

            if (elementType == null) {
                log.info("Could not extract element type from instruction: {}", message);
                return "I couldn't find a JMeter element type \"" + requestedElementType + "\".";
            }
        }

        log.info("Mapped element type: {}", elementType);

        // Check if a test plan is open and a node is selected
        JMeterElementManager.TestPlanStatus status = JMeterElementManager.isTestPlanReady();
        if (!status.isReady()) {
            log.error("Test plan is not ready: {}", status.getErrorMessage());

            // If no test plan exists, try to create one
            if (status.getErrorMessage().contains("No test plan is currently open")) {
                boolean created = JMeterElementManager.ensureTestPlanExists();
                if (created) {
                    log.info("Created a new test plan");

                    // Select the test plan node
                    boolean selected = JMeterElementManager.selectTestPlanNode();

                    // Try again after creating a test plan
                    status = JMeterElementManager.isTestPlanReady();
                    if (status.isReady()) {
                        // Now we can add the element
                        return addElementAfterTestPlanCheck(elementType, elementName);
                    } else {
                        if (selected) {
                            // We selected the test plan node but still can't add the element
                            // This is likely because the element can't be added directly to the test plan
                            return "I've created a new test plan for you and selected it. " +
                                    "However, a " + JMeterElementManager.getDefaultNameForElement(elementType) +
                                    " cannot be added directly to the test plan. " +
                                    "Please add a Thread Group first, then try adding the " +
                                    JMeterElementManager.getDefaultNameForElement(elementType) + " again.";
                        } else {
                            return "I've created a new test plan for you. Please select the Test Plan node and try adding the "
                                    +
                                    JMeterElementManager.getDefaultNameForElement(elementType) + " again.";
                        }
                    }
                } else {
                    return "I'd like to add a " + JMeterElementManager.getDefaultNameForElement(elementType) +
                            " for you, but I couldn't create a test plan. Please create a test plan manually first.";
                }
            }

            return "I'd like to add a " + JMeterElementManager.getDefaultNameForElement(elementType) +
                    " for you, but " + status.getErrorMessage().toLowerCase() +
                    ". Please make sure you have a test plan open.";
        }

        return addElementAfterTestPlanCheck(elementType, elementName);
    }

    /**
     * Adds an element after confirming the test plan is ready.
     * 
     * @param elementType The type of element to add
     * @param elementName The name for the element (can be null)
     * @return A response message
     */
    private static String addElementAfterTestPlanCheck(String elementType, String elementName) {
        log.info("Adding element of type: {} with name: {}", elementType, elementName);

        // Get the currently selected node before adding the element
        GuiPackage guiPackage = GuiPackage.getInstance();
        JMeterTreeNode currentNode = null;
        if (guiPackage != null) {
            currentNode = guiPackage.getTreeListener().getCurrentNode();
        }

        // Try to add the element to the test plan
        boolean success = JMeterElementManager.addElement(elementType, elementName);

        if (success) {
            // Expand the node after adding the element
            if (currentNode != null) {
                try {
                    // Expand the current node to show the newly added element
                    guiPackage.getMainFrame().getTree()
                            .expandPath(new javax.swing.tree.TreePath(currentNode.getPath()));
                    log.info("Successfully expanded node: {}", currentNode.getName());

                    // Select the newly added element (last child of current node)
                    if (currentNode.getChildCount() > 0) {
                        JMeterTreeNode lastChild = (JMeterTreeNode) currentNode
                                .getChildAt(currentNode.getChildCount() - 1);
                        guiPackage.getTreeListener().getJTree()
                                .setSelectionPath(new javax.swing.tree.TreePath(lastChild.getPath()));
                        log.info("Selected newly added element: {}", lastChild.getName());
                    }
                } catch (Exception e) {
                    log.error("Failed to expand node or select element", e);
                    // Not returning an error here as the element was still added successfully
                }
            }

            String response = "I've added a " + JMeterElementManager.getDefaultNameForElement(elementType) +
                    (elementName != null ? " called \"" + elementName + "\"" : "") +
                    " to your test plan. You can now configure it as needed.";
            log.info("Successfully added element: {}", response);
            return response;
        } else {
            String response = "I tried to add a " + JMeterElementManager.getDefaultNameForElement(elementType) +
                    ", but encountered an error. " +
                    "This might be due to compatibility issues with the selected node. Please try selecting a different node in your test plan.";
            log.error("Failed to add element: {}", response);
            return response;
        }
    }

    /**
     * Processes a user message to determine if it's requesting to optimize the test
     * plan.
     * 
     * @param userMessage The user's message
     * @return A response message, or null if the message is not a request to
     *         optimize the test plan
     */
    public static String processOptimizeTestPlanRequest(String userMessage) {
        return OptimizeRequestHandler.processOptimizeTestPlanRequest(userMessage);
    }

    /**
     * Extracts the element type from a given instruction.
     * 
     * @param instruction The instruction to extract the element type from
     * @return The extracted element type, or null if no type was found
     */
    private static String extractElementType(String instruction) {
        log.info("Extracting element type from: {}", instruction);

        // Try to find a word that matches an element type
        String[] words = instruction.split("\\s+");

        // First, check for direct matches with supported element types
        for (String word : words) {
            String normalized = word.toLowerCase().trim();
            // Skip single-letter words as they're likely articles (a, an) or other
            // non-element words
            if (normalized.length() <= 1) {
                continue;
            }
            if (JMeterElementManager.isElementTypeSupported(normalized)) {
                log.info("Found direct match for element type: {}", normalized);
                return normalized;
            }
        }

        // Next, check for matches with element synonyms
        for (String word : words) {
            String normalized = word.toLowerCase().trim();
            // Skip single-letter words as they're likely articles (a, an) or other
            // non-element words
            if (normalized.length() <= 1) {
                continue;
            }
            String mappedType = mapToSupportedElementType(normalized);
            if (mappedType != null) {
                log.info("Found mapped element type: {} for word: {}", mappedType, normalized);
                return mappedType;
            }
        }

        // Try common element types that might be in the instruction
        String normalized = instruction.toLowerCase();
        log.info("Checking for common element types in: {}", normalized);

        // Check for thread group
        if (normalized.contains("thread group") ||
                (normalized.contains("thread") && normalized.contains("group")) ||
                normalized.equals("thread group") ||
                normalized.equals("threadgroup")) {
            log.info("Identified as thread group");
            return "threadgroup";
        }

        // Check for timer types
        if (normalized.contains("timer") || normalized.contains("delay") || normalized.contains("wait")) {
            if (normalized.contains("random")) {
                if (normalized.contains("uniform")) {
                    log.info("Identified as uniform random timer");
                    return "uniformrandomtimer";
                } else if (normalized.contains("gaussian") || normalized.contains("normal")) {
                    log.info("Identified as gaussian random timer");
                    return "gaussianrandomtimer";
                } else if (normalized.contains("poisson")) {
                    log.info("Identified as poisson random timer");
                    return "poissonrandomtimer";
                } else {
                    log.info("Identified as uniform random timer (default random timer)");
                    return "uniformrandomtimer";
                }
            } else {
                log.info("Identified as constant timer");
                return "constanttimer";
            }
        }

        // Check for JSR223 elements
        if (normalized.contains("jsr223") || normalized.contains("jsr 223") ||
                normalized.contains("javascript") || normalized.contains("groovy")) {
            if (normalized.contains("sampler")) {
                log.info("Identified as JSR223 sampler");
                return "jsr223sampler";
            } else if (normalized.contains("pre") && normalized.contains("processor")) {
                log.info("Identified as JSR223 preprocessor");
                return "jsr223preprocessor";
            } else if (normalized.contains("post") && normalized.contains("processor")) {
                log.info("Identified as JSR223 postprocessor");
                return "jsr223postprocessor";
            } else {
                // Default to JSR223 sampler if no specific type is mentioned
                log.info("Defaulting to JSR223 sampler");
                return "jsr223sampler";
            }
        }

        // Check HTTP request
        if (normalized.contains("http") &&
                !normalized.contains("jsr") && !normalized.contains("jsr223") && !normalized.contains("javascript")
                && !normalized.contains("groovy") &&
                (normalized.equals("http request") ||
                        normalized.equals("httprequest") ||
                        normalized.equals("http sampler") ||
                        normalized.equals("httpsampler") ||
                        normalized.equals("http req") ||
                        normalized.contains("http") && normalized.contains("request") ||
                        normalized.contains("http") && normalized.contains("req") ||
                        normalized.contains("http") && normalized.contains("sampler"))) {
            log.info("Identified as HTTP request");
            return "httpsampler";
        }

        // Check for controllers
        if (normalized.contains("controller")) {
            if (normalized.contains("loop")) {
                log.info("Identified as loop controller");
                return "loopcontroller";
            } else if (normalized.contains("if") || normalized.contains("condition")) {
                log.info("Identified as if controller");
                return "ifcontroller";
            } else if (normalized.contains("while")) {
                log.info("Identified as while controller");
                return "whilecontroller";
            } else if (normalized.contains("transaction") || normalized.contains("tx")) {
                log.info("Identified as transaction controller");
                return "transactioncontroller";
            } else if (normalized.contains("runtime") || normalized.contains("time")) {
                log.info("Identified as runtime controller");
                return "runtimecontroller";
            }
        }

        // Check for assertions
        if (normalized.contains("assertion") || normalized.contains("assert") || normalized.contains("validator")) {
            if (normalized.contains("json") || normalized.contains("jsonpath")) {
                log.info("Identified as JSON assertion");
                return "jsonassertion";
            } else if (normalized.contains("duration") || normalized.contains("time")) {
                log.info("Identified as duration assertion");
                return "durationassertion";
            } else if (normalized.contains("size") || normalized.contains("byte")) {
                log.info("Identified as size assertion");
                return "sizeassertion";
            } else if (normalized.contains("xpath") || normalized.contains("xml")) {
                log.info("Identified as xpath assertion");
                return "xpathassertion";
            } else {
                log.info("Identified as response assertion");
                return "responseassert";
            }
        }

        // Check for extractors
        if (normalized.contains("extractor") || normalized.contains("extract")) {
            if (normalized.contains("regex") || normalized.contains("regular expression")) {
                log.info("Identified as regex extractor");
                return "regexextractor";
            } else if (normalized.contains("xpath") || normalized.contains("xml")) {
                log.info("Identified as xpath extractor");
                return "xpathextractor";
            } else if (normalized.contains("json") || normalized.contains("jsonpath")) {
                log.info("Identified as json path extractor");
                return "jsonpathextractor";
            } else if (normalized.contains("boundary") || normalized.contains("text")) {
                log.info("Identified as boundary extractor");
                return "boundaryextractor";
            } else {
                log.info("Identified as regex extractor (default)");
                return "regexextractor";
            }
        }

        // Check for listeners
        if (normalized.contains("listener") || normalized.contains("result") || normalized.contains("view") ||
                normalized.contains("report") || normalized.contains("graph") || normalized.contains("statistics") ||
                normalized.contains("stats")) {

            if (normalized.contains("tree") || normalized.contains("view")) {
                log.info("Identified as view results tree");
                return "viewresultstree";
            } else if (normalized.contains("aggregate") || normalized.contains("statistics")
                    || normalized.contains("stats")) {
                log.info("Identified as aggregate report");
                return "aggregatereport";
            } else {
                // Default to view results tree if no specific type is mentioned
                log.info("Defaulting to view results tree");
                return "viewresultstree";
            }
        }

        // Special case handling for common phrases
        if (normalized.equals("a thread group") || normalized.equals("thread group")) {
            log.info("Special case: identified as thread group");
            return "threadgroup";
        } else if (normalized.equals("a timer") || normalized.equals("timer")) {
            log.info("Special case: identified as constant timer");
            return "constanttimer";
        } else if (normalized.equals("a http request") || normalized.equals("http request") ||
                normalized.equals("a request") || normalized.equals("request")) {
            log.info("Special case: identified as HTTP request");
            return "httpsampler";
        }

        // No element type found
        log.info("No element type found for: {}", instruction);
        return null;
    }

    /**
     * Maps a requested element type to a supported element type using synonyms.
     * 
     * @param requestedType The requested element type
     * @return The mapped element type, or null if no mapping was found
     */
    private static String mapToSupportedElementType(String requestedType) {
        if (requestedType == null || requestedType.trim().isEmpty()) {
            return null;
        }

        // Normalize the requested type
        String normalized = requestedType.toLowerCase().trim();

        // Skip single-letter words as they're likely articles (a, an) or other
        // non-element words
        if (normalized.length() <= 1) {
            log.info("Skipping single-letter word: '{}' as it's not a valid element type", normalized);
            return null;
        }

        log.info("Mapping requested type: '{}' (normalized: '{}')", requestedType, normalized);

        // First, check if the normalized type is directly supported
        String directMatch = JMeterElementManager.normalizeElementType(normalized);
        log.info("Normalized element type: '{}'", directMatch);

        if (JMeterElementManager.isElementTypeSupported(directMatch)) {
            log.info("Direct match found: '{}'", directMatch);
            return directMatch;
        }

        // Special case for common generic terms
        if (normalized.equals("listener")) {
            log.info("Special case: generic term 'listener' detected, mapping to viewresultstree");
            return "viewresultstree";
        } else if (normalized.equals("controller")) {
            log.info("Special case: generic term 'controller' detected, mapping to simplecontroller");
            return "simplecontroller";
        } else if (normalized.equals("sampler")) {
            log.info("Special case: generic term 'sampler' detected, mapping to httpsampler");
            return "httpsampler";
        } else if (normalized.equals("timer")) {
            log.info("Special case: generic term 'timer' detected, mapping to constanttimer");
            return "constanttimer";
        }

        // Check against synonyms with a scoring system
        String bestMatch = null;
        double bestScore = 0.0;

        // First pass: Look for exact matches (highest priority)
        for (Map.Entry<String, List<String>> entry : ELEMENT_SYNONYMS.entrySet()) {
            String elementType = entry.getKey();
            List<String> synonyms = entry.getValue();

            log.info("Checking element type '{}' with synonyms: {}", elementType, synonyms);

            for (String synonym : synonyms) {
                // Check for exact match (highest priority)
                if (normalized.equals(synonym)) {
                    log.info("Exact synonym match found: '{}' -> '{}'", synonym, elementType);
                    return elementType; // Return immediately for exact matches
                }
            }
        }

        // Second pass: Look for word matches (medium priority)
        for (Map.Entry<String, List<String>> entry : ELEMENT_SYNONYMS.entrySet()) {
            String elementType = entry.getKey();
            List<String> synonyms = entry.getValue();

            for (String synonym : synonyms) {
                // Check if the normalized type is a complete word in the synonym
                String[] synonymWords = synonym.split("\\s+");
                for (String word : synonymWords) {
                    if (normalized.equals(word)) {
                        double score = 0.8; // Good score for word match
                        if (score > bestScore) {
                            bestScore = score;
                            bestMatch = elementType;
                            log.info("Word match found: '{}' is a word in '{}', score: {}", normalized, synonym, score);
                        }
                    }
                }
            }
        }

        // Third pass: Look for partial matches (lowest priority)
        if (bestMatch == null) {
            for (Map.Entry<String, List<String>> entry : ELEMENT_SYNONYMS.entrySet()) {
                String elementType = entry.getKey();
                List<String> synonyms = entry.getValue();

                for (String synonym : synonyms) {
                    // Calculate a score based on how specific the match is
                    double score = 0.0;

                    // If the normalized input contains the entire synonym
                    if (normalized.contains(synonym)) {
                        // Score based on how much of the input the synonym covers
                        score = (double) synonym.length() / normalized.length() * 0.7;
                        if (score > bestScore) {
                            bestScore = score;
                            bestMatch = elementType;
                            log.info("Partial match found: '{}' contains '{}', score: {}", normalized, synonym, score);
                        }
                    }
                    // If the synonym contains the normalized input (be careful with this)
                    else if (synonym.contains(normalized)) {
                        // Score based on how specific the input is compared to the synonym
                        // Lower score for generic terms contained in many synonyms
                        score = (double) normalized.length() / synonym.length() * 0.5;

                        // Penalize very generic terms
                        if (normalized.equals("listener") || normalized.equals("controller") ||
                                normalized.equals("sampler") || normalized.equals("timer") ||
                                normalized.equals("assertion") || normalized.equals("extractor")) {
                            score *= 0.5; // Reduce score for generic terms
                        }

                        if (score > bestScore) {
                            bestScore = score;
                            bestMatch = elementType;
                            log.info("Partial match found: '{}' is contained in '{}', score: {}", normalized, synonym,
                                    score);
                        }
                    }
                }
            }
        }

        // Return the best match if the score is above a threshold
        if (bestMatch != null && bestScore > 0.3) {
            log.info("Best match for '{}' is '{}' with score {}", normalized, bestMatch, bestScore);
            return bestMatch;
        }

        // No mapping found
        log.info("No suitable mapping found for '{}'", normalized);
        return null;
    }

    /**
     * Gets a map of element display names to their normalized types
     * 
     * @return Map of element display names to normalized types
     */
    public static Map<String, String> getElementTypeMap() {
        Map<String, String> elementTypeMap = new HashMap<>();

        // Add all supported element types
        elementTypeMap.put("Thread Group", "threadgroup");
        elementTypeMap.put("HTTP Request", "httprequest");
        elementTypeMap.put("Loop Controller", "loopcontroller");
        elementTypeMap.put("If Controller", "ifcontroller");
        elementTypeMap.put("While Controller", "whilecontroller");
        elementTypeMap.put("Transaction Controller", "transactioncontroller");
        elementTypeMap.put("Runtime Controller", "runtimecontroller");
        elementTypeMap.put("CSV Data Set Config", "csvdataset");
        elementTypeMap.put("HTTP Header Manager", "headerManager");
        elementTypeMap.put("Response Assertion", "responseassert");
        elementTypeMap.put("JSON Path Assertion", "jsonpathassert");
        elementTypeMap.put("Duration Assertion", "durationassert");
        elementTypeMap.put("Size Assertion", "sizeassert");
        elementTypeMap.put("XPath Assertion", "xpathassert");
        elementTypeMap.put("Constant Timer", "constanttimer");
        elementTypeMap.put("Uniform Random Timer", "uniformrandomtimer");
        elementTypeMap.put("Gaussian Random Timer", "gaussianrandomtimer");
        elementTypeMap.put("Poisson Random Timer", "poissonrandomtimer");
        elementTypeMap.put("Regex Extractor", "regexextractor");
        elementTypeMap.put("XPath Extractor", "xpathextractor");
        elementTypeMap.put("JSON Path Extractor", "jsonpathextractor");
        elementTypeMap.put("Boundary Extractor", "boundaryextractor");
        elementTypeMap.put("View Results Tree", "viewresultstree");
        elementTypeMap.put("Aggregate Report", "aggregatereport");

        return elementTypeMap;
    }

    /**
     * Checks if a node has a parent of the specified type.
     * 
     * @param node The node to check
     * @param type The type to look for
     * @return True if the node has a parent of the specified type, false otherwise
     */
    private static boolean hasParentOfType(JMeterTreeNode node, String type) {
        if (node == null) {
            return false;
        }

        JMeterTreeNode parent = (JMeterTreeNode) node.getParent();
        while (parent != null) {
            if (parent.getTestElement().getClass().getSimpleName().equals(type)) {
                return true;
            }
            parent = (JMeterTreeNode) parent.getParent();
        }

        return false;
    }
}
