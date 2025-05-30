package org.nfe.jmeter.ai.gui;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.tree.TreeNode;

/**
 * Manages JMeter element operations.
 * This class is responsible for adding, retrieving, and manipulating JMeter elements.
 */
public class JMeterElementManager {
    private static final Logger log = LoggerFactory.getLogger(JMeterElementManager.class);
    
    /**
     * Adds a JMeter element to the test plan.
     * 
     * @param elementType The type of element to add
     * @param elementName The name of the element, or null for default name
     * @return True if the element was added successfully, false otherwise
     */
    public boolean addElement(String elementType, String elementName) {
        log.info("Adding element of type: {} with name: {}", elementType, elementName);
        
        try {
            // Convert our normalized type to JMeter's internal type
            String jmeterType = mapToJMeterType(elementType);
            if (jmeterType == null) {
                log.error("Unknown element type: {}", elementType);
                return false;
            }
            
            log.info("Mapped element type {} to JMeter type: {}", elementType, jmeterType);
            
            // Use the utility class to add the element
            boolean success = org.nfe.jmeter.ai.utils.JMeterElementManager.addElement(jmeterType, elementName);
            
            if (success) {
                log.info("Successfully added element: {}", jmeterType);
            } else {
                log.error("Failed to add element: {}", jmeterType);
            }
            
            return success;
        } catch (Exception e) {
            log.error("Error adding element", e);
            return false;
        }
    }
    
    /**
     * Maps our normalized element type to JMeter's internal type.
     * 
     * @param normalizedType Our normalized element type
     * @return JMeter's internal type, or null if not recognized
     */
    private String mapToJMeterType(String normalizedType) {
        // First try direct mapping with the provided type
        if (normalizedType != null) {
            // Log the mapping attempt
            log.info("Attempting to map element type: {}", normalizedType);
            
            // Convert to lowercase for case-insensitive comparison
            String lowerType = normalizedType.toLowerCase();
            
            // Map common element types to their JMeter action commands
            switch (lowerType) {
                // Thread Groups
                case "threadgroup":
                case "thread":
                case "threads":
                case "users":
                    return "ThreadGroup";
                
                // Samplers
                case "httprequest":
                case "http":
                case "httpsample":
                case "httprequestsampler":
                case "httpsampler":
                case "httptestsample":
                    return "HttpTestSample";
                case "jsr223sampler":
                case "jsr223":
                case "script":
                case "scriptsampler":
                    return "JSR223Sampler";
                case "jdbcsampler":
                case "jdbc":
                case "database":
                case "db":
                    return "JDBCSampler";
                case "debugsampler":
                case "debug":
                    return "DebugSampler";
                
                // Controllers
                case "loopcontroller":
                case "loop":
                    return "LoopController";
                case "ifcontroller":
                case "if":
                case "condition":
                case "conditional":
                    return "IfController";
                case "whilecontroller":
                case "while":
                    return "WhileController";
                case "forloopcontroller":
                case "forloop":
                case "for":
                    return "ForLoopController";
                case "transactioncontroller":
                case "transaction":
                    return "TransactionController";
                
                // Config Elements
                case "cookiemanager":
                case "cookie":
                case "httpcookiemanager":
                    return "CookieManager";
                case "headermanager":
                case "header":
                case "httpheader":
                case "httpheadermanager":
                    return "HeaderManager";
                case "csvdataset":
                case "csv":
                case "csvreader":
                    return "CSVDataSet";
                
                // Post Processors
                case "jsonextractor":
                case "jsonpathextractor":
                case "jsonpath":
                case "jsonpostprocessor":
                    return "JSONPostProcessor";
                case "regexextractor":
                case "regex":
                    return "RegexExtractor";
                case "xpathextractor":
                case "xpath":
                    return "XPathExtractor";
                
                // Assertions
                case "responseassert":
                case "responseasssertion":
                case "responseassertion":
                case "assertion":
                    return "ResponseAssertion";
                case "jsonassertion":
                case "jsonpathassert":
                    return "JSONAssertion";
                case "durationassertion":
                case "durationassert":
                    return "DurationAssertion";
                case "sizeassertion":
                case "sizeassert":
                    return "SizeAssertion";
                case "xpathassertion":
                case "xpathassert":
                    return "XPathAssertion";
                
                // Timers
                case "constanttimer":
                case "constant":
                case "timer":
                    return "ConstantTimer";
                case "uniformrandomtimer":
                case "randomtimer":
                    return "UniformRandomTimer";
                case "gaussianrandomtimer":
                case "gaussiantimer":
                    return "GaussianRandomTimer";
                
                // Listeners
                case "viewresultstree":
                case "resultstree":
                case "viewresults":
                    return "ViewResultsFullVisualizer";
                case "summaryreport":
                case "summary":
                    return "SummaryReport";
                case "aggregatereport":
                case "aggregate":
                    return "AggregateReport";
                
                default:
                    // If we couldn't map it directly, log a warning
                    log.warn("No direct mapping for element type: {}", normalizedType);
                    
                    // Try to use the type as-is (it might be a valid JMeter type already)
                    return normalizedType;
            }
        }
        
        return null;
    }
    
    /**
     * Gets the default display name for an element type.
     * 
     * @param elementType The element type
     * @return The default display name
     */
    public static String getDefaultNameForElement(String elementType) {
        if (elementType == null) {
            return "Unknown Element";
        }
        
        String lowerType = elementType.toLowerCase();
        
        switch (lowerType) {
            case "threadgroup":
                return "Thread Group";
            case "httprequest":
            case "httpsampler":
            case "httptestsample":
                return "HTTP Request";
            case "loopcontroller":
                return "Loop Controller";
            case "ifcontroller":
                return "If Controller";
            case "whilecontroller":
                return "While Controller";
            case "forloopcontroller":
                return "For Loop Controller";
            case "transactioncontroller":
                return "Transaction Controller";
            case "cookiemanager":
                return "HTTP Cookie Manager";
            case "headermanager":
                return "HTTP Header Manager";
            case "csvdataset":
                return "CSV Data Set";
            case "jsonextractor":
            case "jsonpostprocessor":
                return "JSON Extractor";
            case "regexextractor":
                return "Regular Expression Extractor";
            case "xpathextractor":
                return "XPath Extractor";
            case "responseassert":
            case "responseassertion":
                return "Response Assertion";
            case "jsonassertion":
                return "JSON Assertion";
            case "durationassertion":
                return "Duration Assertion";
            case "sizeassertion":
                return "Size Assertion";
            case "xpathassertion":
                return "XPath Assertion";
            case "constanttimer":
                return "Constant Timer";
            case "uniformrandomtimer":
                return "Uniform Random Timer";
            case "gaussianrandomtimer":
                return "Gaussian Random Timer";
            case "viewresultstree":
                return "View Results Tree";
            case "summaryreport":
                return "Summary Report";
            case "aggregatereport":
                return "Aggregate Report";
            case "jsr223sampler":
                return "JSR223 Sampler";
            case "debugsampler":
                return "Debug Sampler";
            case "jdbcsampler":
                return "JDBC Request";
            default:
                // Format the type by adding spaces before capital letters
                String formatted = elementType.replaceAll("([A-Z])", " $1").trim();
                return formatted.substring(0, 1).toUpperCase() + formatted.substring(1);
        }
    }
    
    /**
     * Gets information about the currently selected element.
     * 
     * @return Information about the currently selected element, or null if no element is selected
     */
    public String getCurrentElementInfo() {
        try {
            GuiPackage guiPackage = GuiPackage.getInstance();
            if (guiPackage == null) {
                log.warn("Cannot get element info: GuiPackage is null");
                return null;
            }
            
            JMeterTreeNode currentNode = guiPackage.getTreeListener().getCurrentNode();
            if (currentNode == null) {
                log.warn("No node is currently selected in the test plan");
                return null;
            }
            
            // Get the test element
            TestElement element = currentNode.getTestElement();
            if (element == null) {
                log.warn("Selected node does not have a test element");
                return null;
            }
            
            // Build information about the element
            StringBuilder info = new StringBuilder();
            info.append("# ").append(currentNode.getName()).append(" (").append(element.getClass().getSimpleName())
                    .append(")\n\n");
            
            // Add description based on element type
            String elementType = element.getClass().getSimpleName();
            info.append(getElementDescription(elementType)).append("\n\n");
            
            // Add properties
            info.append("## Properties\n\n");
            
            // Get all property names
            PropertyIterator propertyIterator = element.propertyIterator();
            while (propertyIterator.hasNext()) {
                JMeterProperty property = propertyIterator.next();
                String propertyName = property.getName();
                String propertyValue = property.getStringValue();
                
                // Skip empty properties and internal JMeter properties
                if (!propertyValue.isEmpty() && !propertyName.startsWith("TestElement.")
                        && !propertyName.equals("guiclass")) {
                    // Format the property name for better readability
                    String formattedName = propertyName.replace(".", " ").replace("_", " ");
                    formattedName = formattedName.substring(0, 1).toUpperCase() + formattedName.substring(1);
                    
                    info.append("- **").append(formattedName).append("**: ").append(propertyValue).append("\n");
                }
            }
            
            // Add hierarchical information
            info.append("\n## Location in Test Plan\n\n");
            
            // Get parent node
            TreeNode parent = currentNode.getParent();
            if (parent instanceof JMeterTreeNode) {
                JMeterTreeNode parentNode = (JMeterTreeNode) parent;
                info.append("- Parent: **").append(parentNode.getName()).append("** (")
                        .append(parentNode.getTestElement().getClass().getSimpleName()).append(")\n");
            }
            
            // Get child nodes
            if (currentNode.getChildCount() > 0) {
                info.append("- Children: ").append(currentNode.getChildCount()).append("\n");
                for (int i = 0; i < currentNode.getChildCount(); i++) {
                    JMeterTreeNode childNode = (JMeterTreeNode) currentNode.getChildAt(i);
                    info.append("  - **").append(childNode.getName()).append("** (")
                            .append(childNode.getTestElement().getClass().getSimpleName()).append(")\n");
                }
            } else {
                info.append("- No children\n");
            }
            
            // Add suggestions for what can be added to this element
            info.append("\n## Suggested Elements\n\n");
            String[][] suggestions = getContextAwareSuggestions(currentNode.getStaticLabel());
            if (suggestions.length > 0) {
                info.append("You can add the following elements to this node:\n\n");
                for (String[] suggestion : suggestions) {
                    info.append("- ").append(suggestion[0]).append("\n");
                }
            } else {
                info.append("No specific suggestions for this element type.\n");
            }
            
            return info.toString();
        } catch (Exception e) {
            log.error("Error getting current element info", e);
            return "Error retrieving element information: " + e.getMessage();
        }
    }
    
    /**
     * Gets a description for a JMeter element type.
     * 
     * @param elementType The element type to get a description for
     * @return A description of the element type
     */
    public String getElementDescription(String elementType) {
        // Convert to lowercase for case-insensitive comparison
        String type = elementType.toLowerCase();
        
        if (type.contains("testplan")) {
            return "The Test Plan is the root element of a JMeter test. It defines global settings and variables for the test.";
        } else if (type.contains("threadgroup")) {
            return "Thread Groups simulate users accessing your application. Each thread represents a user, and you can configure the number of threads, ramp-up period, and loop count.";
        } else if (type.contains("httpsampler") || type.contains("httprequest")) {
            return "HTTP Samplers send HTTP/HTTPS requests to a web server. You can configure the URL, method, parameters, and other settings.";
        } else if (type.contains("loopcontroller")) {
            return "Loop Controllers repeat their child elements a specified number of times or indefinitely.";
        } else if (type.contains("ifcontroller")) {
            return "If Controllers execute their child elements only if a condition is true. The condition can be a JavaScript expression or a variable reference.";
        } else if (type.contains("whilecontroller")) {
            return "While Controllers execute their child elements repeatedly as long as a condition is true.";
        } else if (type.contains("transactioncontroller")) {
            return "Transaction Controllers group samplers together to measure the total time taken by all samplers within the transaction.";
        } else if (type.contains("runtimecontroller")) {
            return "Runtime Controllers execute their child elements for a specified amount of time.";
        } else if (type.contains("responseassert")) {
            return "Response Assertions validate the response from a sampler, such as checking for specific text or patterns.";
        } else if (type.contains("jsonpathassert") || type.contains("jsonassertion")) {
            return "JSON Path Assertions validate JSON responses using JSONPath expressions.";
        } else if (type.contains("durationassertion")) {
            return "Duration Assertions validate that a sampler completes within a specified time.";
        } else if (type.contains("sizeassertion")) {
            return "Size Assertions validate the size of a response.";
        } else if (type.contains("xpathassertion")) {
            return "XPath Assertions validate XML responses using XPath expressions.";
        } else if (type.contains("constanttimer")) {
            return "Constant Timers add a fixed delay between sampler executions.";
        } else if (type.contains("uniformrandomtimer")) {
            return "Uniform Random Timers add a random delay between sampler executions, with a uniform distribution.";
        } else if (type.contains("gaussianrandomtimer")) {
            return "Gaussian Random Timers add a random delay between sampler executions, with a Gaussian (normal) distribution.";
        } else if (type.contains("poissonrandomtimer")) {
            return "Poisson Random Timers add a random delay between sampler executions, with a Poisson distribution.";
        } else if (type.contains("csvdataset")) {
            return "CSV Data Set Config elements read data from CSV files to parameterize your test.";
        } else if (type.contains("headermanager")) {
            return "HTTP Header Manager elements add HTTP headers to your requests.";
        } else if (type.contains("viewresultstree")) {
            return "View Results Tree listeners display detailed results for each sampler, including request and response data.";
        } else if (type.contains("aggregatereport")) {
            return "Aggregate Report listeners display summary statistics for each sampler, such as average response time and throughput.";
        } else if (type.contains("jsr223")) {
            if (type.contains("sampler")) {
                return "JSR223 Samplers allow you to create custom requests using scripting languages like Groovy, JavaScript, or BeanShell. They are useful for complex logic that can't be handled by standard samplers.";
            } else if (type.contains("pre") && type.contains("processor")) {
                return "JSR223 PreProcessors execute scripts before a sampler runs. They can be used to set up variables, modify request parameters, or perform other preparation tasks.";
            } else if (type.contains("post") && type.contains("processor")) {
                return "JSR223 PostProcessors execute scripts after a sampler runs. They can extract data from responses, perform calculations, or modify variables based on the sampler's results.";
            }
            return "JSR223 elements allow you to use scripting languages like Groovy, JavaScript, or BeanShell to extend JMeter's functionality.";
        } else if (type.contains("extractor") || type.contains("postprocessor")) {
            if (type.contains("jsonpath")) {
                return "JSON Path Extractors extract values from JSON responses using JSONPath expressions.";
            } else if (type.contains("xpath")) {
                return "XPath Extractors extract values from XML responses using XPath expressions.";
            } else if (type.contains("regex")) {
                return "Regular Expression Extractors extract values from responses using regular expressions.";
            } else if (type.contains("boundary")) {
                return "Boundary Extractors extract values from responses using boundary strings.";
            }
            return "Extractors extract values from responses for use in subsequent requests.";
        }
        
        // Generic description for unknown element types
        return "This is a " + elementType + " element in your JMeter test plan.";
    }
    
    /**
     * Gets context-aware element suggestions based on the node type.
     * 
     * @param nodeType The type of node to get suggestions for
     * @return An array of string arrays, each containing [displayName]
     */
    private String[][] getContextAwareSuggestions(String nodeType) {
        // Convert to lowercase for case-insensitive comparison
        String type = nodeType.toLowerCase();
        
        // Return suggestions based on the node type
        if (type.contains("test plan")) {
            return new String[][] {
                {"Thread Group"}, 
                {"HTTP Cookie Manager"}, 
                {"HTTP Header Manager"}
            };
        } else if (type.contains("thread group")) {
            return new String[][] {
                {"HTTP Request"}, 
                {"Loop Controller"}, 
                {"If Controller"}
            };
        } else if (type.contains("http request")) {
            return new String[][] {
                {"Response Assertion"}, 
                {"JSON Extractor"}, 
                {"Constant Timer"}
            };
        } else if (type.contains("controller")) {
            return new String[][] {
                {"HTTP Request"}, 
                {"Debug Sampler"}, 
                {"JSR223 Sampler"}
            };
        } else {
            // Default suggestions
            return new String[][] {
                {"Thread Group"}, 
                {"HTTP Request"}, 
                {"Response Assertion"}
            };
        }
    }
}
