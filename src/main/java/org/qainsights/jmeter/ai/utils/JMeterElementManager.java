package org.qainsights.jmeter.ai.utils;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.action.ActionRouter;
import org.apache.jmeter.gui.JMeterGUIComponent;
import org.apache.jmeter.testelement.TestElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.event.ActionEvent;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;
import javax.swing.tree.TreePath;

/**
 * Utility class for managing JMeter elements (add/delete) in the test plan
 * programmatically.
 */
public class JMeterElementManager {
    private static final Logger log = LoggerFactory.getLogger(JMeterElementManager.class);

    // Map of common element names to their fully qualified class names
    private static final Map<String, String> ELEMENT_CLASS_MAP = new HashMap<>();

    static {
        // Keep only the most frequently used JMeter elements

        // Samplers (most common)
        ELEMENT_CLASS_MAP.put("httpsampler", "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui");

        // Controllers (most common)
        ELEMENT_CLASS_MAP.put("loopcontroller", "org.apache.jmeter.control.gui.LoopControlPanel");
        ELEMENT_CLASS_MAP.put("ifcontroller", "org.apache.jmeter.control.gui.IfControllerPanel");
        ELEMENT_CLASS_MAP.put("whilecontroller", "org.apache.jmeter.control.gui.WhileControllerGui");
        ELEMENT_CLASS_MAP.put("transactioncontroller", "org.apache.jmeter.control.gui.TransactionControllerGui");
        ELEMENT_CLASS_MAP.put("runtimecontroller", "org.apache.jmeter.control.gui.RunTimeGui");

        // Config Elements
        ELEMENT_CLASS_MAP.put("csvdataset", "org.apache.jmeter.testbeans.gui.TestBeanGUI");
        ELEMENT_CLASS_MAP.put("headermanager", "org.apache.jmeter.protocol.http.gui.HeaderPanel");

        // Thread Groups (essential)
        ELEMENT_CLASS_MAP.put("threadgroup", "org.apache.jmeter.threads.gui.ThreadGroupGui");

        // Assertions (most common)
        ELEMENT_CLASS_MAP.put("responseassert", "org.apache.jmeter.assertions.gui.AssertionGui");
        ELEMENT_CLASS_MAP.put("jsonassertion", "org.apache.jmeter.assertions.gui.JSONPathAssertionGui");
        ELEMENT_CLASS_MAP.put("durationassertion", "org.apache.jmeter.assertions.gui.DurationAssertionGui");
        ELEMENT_CLASS_MAP.put("sizeassertion", "org.apache.jmeter.assertions.gui.SizeAssertionGui");
        ELEMENT_CLASS_MAP.put("xpathassertion", "org.apache.jmeter.assertions.gui.XPathAssertionGui");

        // Timers (most common)
        ELEMENT_CLASS_MAP.put("constanttimer", "org.apache.jmeter.timers.gui.ConstantTimerGui");
        ELEMENT_CLASS_MAP.put("uniformrandomtimer", "org.apache.jmeter.timers.gui.UniformRandomTimerGui");
        ELEMENT_CLASS_MAP.put("gaussianrandomtimer", "org.apache.jmeter.timers.gui.GaussianRandomTimerGui");
        ELEMENT_CLASS_MAP.put("poissonrandomtimer", "org.apache.jmeter.timers.gui.PoissonRandomTimerGui");

        // Extractors (most common)
        ELEMENT_CLASS_MAP.put("regexextractor", "org.apache.jmeter.extractor.gui.RegexExtractorGui");
        ELEMENT_CLASS_MAP.put("xpathextractor", "org.apache.jmeter.extractor.gui.XPathExtractorGui");
        ELEMENT_CLASS_MAP.put("jsonpathextractor",
                "org.apache.jmeter.extractor.json.jsonpath.gui.JSONPostProcessorGui");
        ELEMENT_CLASS_MAP.put("boundaryextractor", "org.apache.jmeter.extractor.gui.BoundaryExtractorGui");

        // Listeners (most common)
        ELEMENT_CLASS_MAP.put("viewresultstree", "org.apache.jmeter.visualizers.ViewResultsFullVisualizer");
        ELEMENT_CLASS_MAP.put("aggregatereport", "org.apache.jmeter.report.gui.AggregateReportGui");
    }

    /**
     * Adds a JMeter element to the currently selected node in the test plan.
     * 
     * @param elementType The type of element to add (case-insensitive, spaces
     *                    ignored)
     * @param elementName The name to give the new element (optional, will use
     *                    default if null)
     * @return true if the element was added successfully, false otherwise
     */
    public static boolean addElement(String elementType, String elementName) {
        try {
            log.info("Adding element of type: {} with name: {}", elementType, elementName);

            GuiPackage guiPackage = GuiPackage.getInstance();
            if (guiPackage == null) {
                log.error("GuiPackage is null, cannot add element");
                return false;
            }

            // Get the currently selected node
            JMeterTreeNode currentNode = guiPackage.getTreeListener().getCurrentNode();
            if (currentNode == null) {
                log.error("No node is currently selected in the test plan");
                return false;
            }
            log.info("Current node: {}", currentNode.getName());

            // Normalize the element type for lookup
            String normalizedType = normalizeElementType(elementType);
            log.info("Normalized element type: {}", normalizedType);

            // Special handling for HTTP samplers
            if (normalizedType.equals("httpsampler")) {
                log.info("Special handling for HTTP sampler");
                try {
                    // Create the GUI component first using reflection
                    Class<?> guiClass = Class.forName("org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui");
                    Constructor<?> constructor = guiClass.getDeclaredConstructor();
                    constructor.setAccessible(true);
                    JMeterGUIComponent guiComponent = (JMeterGUIComponent) constructor.newInstance();
                    log.info("Successfully created HTTP sampler GUI component: {}", guiComponent.getClass().getName());

                    // Create the model element from the GUI component
                    TestElement newElement = guiComponent.createTestElement();
                    log.info("Successfully created HTTP sampler model element: {}", newElement.getClass().getName());

                    // Set a name for the element
                    if (elementName != null && !elementName.isEmpty()) {
                        newElement.setName(elementName);
                    } else {
                        // Use a default name
                        newElement.setName("HTTP Request");
                    }

                    log.info("Adding HTTP sampler to node: {}", currentNode.getName());

                    // Add the element to the test plan
                    try {
                        guiPackage.getTreeModel().addComponent(newElement, currentNode);
                        log.info("Successfully added HTTP sampler to the tree model");

                        // Configure the GUI for the new element
                        guiPackage.getCurrentGui(); // This forces JMeter to update the GUI

                        // Refresh the tree to show the new element
                        try {
                            guiPackage.getTreeModel().nodeStructureChanged(currentNode);
                            log.info("Successfully refreshed the tree");
                        } catch (Exception e) {
                            log.error("Failed to refresh the tree", e);
                        }

                        return true;
                    } catch (Exception e) {
                        log.error("Failed to add HTTP sampler to the tree model", e);
                        return false;
                    }
                } catch (Exception e) {
                    log.error("Failed to create HTTP sampler", e);
                    // Continue with normal element creation
                }
            }

            // Special handling for CSV Data Set
            if (normalizedType.equals("csvdataset")) {
                log.info("Special handling for CSV Data Set");
                try {
                    // Create the CSV Data Set directly
                    TestElement csvDataSet = (TestElement) Class.forName("org.apache.jmeter.config.CSVDataSet").getDeclaredConstructor().newInstance();
                    csvDataSet.setName(elementName != null && !elementName.isEmpty() ? elementName : "CSV Data Set");

                    // Set the required properties for TestElement
                    csvDataSet.setProperty(TestElement.TEST_CLASS, "org.apache.jmeter.config.CSVDataSet");
                    csvDataSet.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.testbeans.gui.TestBeanGUI");

                    // Configure the CSV Data Set properties
                    csvDataSet.setProperty("filename", "");
                    csvDataSet.setProperty("fileEncoding", "UTF-8");
                    csvDataSet.setProperty("variableNames", "");
                    csvDataSet.setProperty("delimiter", ",");
                    csvDataSet.setProperty("quotedData", false);
                    csvDataSet.setProperty("recycle", true);
                    csvDataSet.setProperty("stopThread", false);
                    csvDataSet.setProperty("shareMode", "shareMode.all");
                    csvDataSet.setProperty("ignoreFirstLine", false);

                    log.info("Adding CSV Data Set to node: {}", currentNode.getName());

                    // Add the element to the test plan
                    try {
                        guiPackage.getTreeModel().addComponent(csvDataSet, currentNode);
                        log.info("Successfully added CSV Data Set to the tree model");

                        // Configure the GUI for the new element
                        guiPackage.getCurrentGui(); // This forces JMeter to update the GUI

                        // Refresh the tree to show the new element
                        try {
                            guiPackage.getTreeModel().nodeStructureChanged(currentNode);
                            log.info("Successfully refreshed the tree");
                        } catch (Exception e) {
                            log.error("Failed to refresh the tree", e);
                        }

                        return true;
                    } catch (Exception e) {
                        log.error("Failed to add CSV Data Set to the tree model", e);
                        return false;
                    }
                } catch (Exception e) {
                    log.error("Failed to create CSV Data Set", e);
                    // Continue with normal element creation
                }
            }

            // Get the class name for the element type
            String className = ELEMENT_CLASS_MAP.get(normalizedType);
            if (className == null) {
                log.error("Unknown element type: {}", elementType);
                return false;
            }
            log.info("Attempting to create element of type: {} with class: {}", normalizedType, className);

            // Create the element using various approaches
            TestElement newElement = null;

            // Approach 1: Try using MenuFactory
            try {
                log.info("Approach 1: Trying to create element using MenuFactory");
                Class<?> guiClass = Class.forName(className);
                log.info("Found GUI class: {}", guiClass.getName());
                Constructor<?> constructor = guiClass.getDeclaredConstructor();
                constructor.setAccessible(true);
                JMeterGUIComponent guiComponent = (JMeterGUIComponent) constructor.newInstance();
                log.info("Created GUI component: {}", guiComponent.getClass().getName());

                // Get the test element from the GUI component
                newElement = guiComponent.createTestElement();
                log.info("Successfully created element using MenuFactory: {}", newElement.getClass().getName());
            } catch (Exception e) {
                log.error("Failed to create element using MenuFactory", e);

                // Approach 2: Try using GuiPackage
                try {
                    log.info("Approach 2: Trying to create element using GuiPackage");
                    newElement = guiPackage.createTestElement(className);
                    log.info("Successfully created element using GuiPackage: {}", newElement.getClass().getName());
                } catch (Exception ex) {
                    log.error("Failed to create element using GuiPackage", ex);

                    // Approach 3: Try direct instantiation of the model class
                    try {
                        log.info("Approach 3: Trying to create element via direct model instantiation");
                        // Try to get the model class name from the GUI class name
                        String modelClassName = getModelClassNameFromGuiClassName(className);
                        log.info("Derived model class name: {}", modelClassName);
                        if (modelClassName != null) {
                            Class<?> modelClass = Class.forName(modelClassName);
                            log.info("Found model class: {}", modelClass.getName());
                            Constructor<?> constructor = modelClass.getDeclaredConstructor();
                            constructor.setAccessible(true);
                            newElement = (TestElement) constructor.newInstance();
                            log.info("Successfully created element via direct model instantiation: {}",
                                    newElement.getClass().getName());
                        } else {
                            log.error("Could not determine model class name from GUI class: {}", className);
                            return false;
                        }
                    } catch (Exception exc) {
                        log.error("Failed to create element via direct model instantiation", exc);
                        return false;
                    }
                }
            }

            // Set a name for the element
            if (elementName != null && !elementName.isEmpty()) {
                newElement.setName(elementName);
            } else {
                // Use a default name based on the element type
                newElement.setName(getDefaultNameForElement(normalizedType));
            }

            log.info("Adding element to node: {}", currentNode.getName());

            // Add the element to the test plan
            try {
                guiPackage.getTreeModel().addComponent(newElement, currentNode);
                log.info("Successfully added element to the tree model");
            } catch (Exception e) {
                log.error("Failed to add element to the tree model", e);
                return false;
            }

            // Refresh the tree to show the new element
            try {
                guiPackage.getTreeModel().nodeStructureChanged(currentNode);
                log.info("Successfully refreshed the tree");
            } catch (Exception e) {
                log.error("Failed to refresh the tree", e);
                // Not returning false here as the element might have been added successfully
            }

            log.info("Successfully added {} element to the test plan", normalizedType);
            return true;
        } catch (Exception e) {
            log.error("Error adding element to the test plan", e);
            return false;
        }
    }

    /**
     * Checks if the test plan is ready for operations.
     * 
     * @return A TestPlanStatus object indicating if the test plan is ready and any
     *         error message
     */
    public static TestPlanStatus isTestPlanReady() {
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            return new TestPlanStatus(false, "JMeter GUI is not available");
        }

        // Check if a test plan is open
        if (guiPackage.getTreeModel() == null || guiPackage.getTreeModel().getRoot() == null) {
            return new TestPlanStatus(false, "No test plan is currently open");
        }

        return new TestPlanStatus(true, null);
    }

    /**
     * Ensures that a test plan exists, creating one if necessary.
     * 
     * @return true if a test plan exists or was created successfully, false
     *         otherwise
     */
    public static boolean ensureTestPlanExists() {
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            log.error("GuiPackage is null, cannot ensure test plan exists");
            return false;
        }

        // Check if a test plan is already open
        if (guiPackage.getTreeModel() != null && guiPackage.getTreeModel().getRoot() != null) {
            log.info("Test plan already exists");
            return true;
        }

        try {
            // Create a new test plan
            ActionRouter.getInstance().doActionNow(new ActionEvent(guiPackage.getMainFrame(), 0, "new"));
            log.info("Created a new test plan");
            return true;
        } catch (Exception e) {
            log.error("Error creating a new test plan", e);
            return false;
        }
    }

    /**
     * Selects the test plan node in the tree.
     * 
     * @return true if the test plan node was selected successfully, false otherwise
     */
    public static boolean selectTestPlanNode() {
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            log.error("GuiPackage is null, cannot select test plan node");
            return false;
        }

        try {
            // Get the root node (test plan)
            JMeterTreeNode root = (JMeterTreeNode) guiPackage.getTreeModel().getRoot();
            if (root == null) {
                log.error("Root node is null, cannot select test plan node");
                return false;
            }

            // Select the test plan node
            guiPackage.getTreeListener().getJTree().setSelectionPath(new TreePath(root.getPath()));
            log.info("Selected the test plan node");
            return true;
        } catch (Exception e) {
            log.error("Error selecting the test plan node", e);
            return false;
        }
    }

    /**
     * Normalizes the element type by removing spaces and converting to lowercase.
     * 
     * @param elementType The element type to normalize
     * @return The normalized element type
     */
    public static String normalizeElementType(String elementType) {
        if (elementType == null) {
            return "";
        }
        return elementType.toLowerCase().replaceAll("[\\s-]+", "");
    }

    /**
     * Gets a default name for an element based on its type.
     * 
     * @param elementType The normalized element type
     * @return A default name for the element
     */
    public static String getDefaultNameForElement(String elementType) {
        if (elementType == null) {
            return "New Element";
        }

        String normalizedType = normalizeElementType(elementType);

        switch (normalizedType) {
            case "httpsampler":
                return "HTTP Request";
            case "loopcontroller":
                return "Loop Controller";
            case "ifcontroller":
                return "If Controller";
            case "whilecontroller":
                return "While Controller";
            case "transactioncontroller":
                return "Transaction Controller";
            case "runtimecontroller":
                return "Runtime Controller";
            case "headermanager":
                return "HTTP Header Manager";
            case "csvdataset":
                return "CSV Data Set";
            case "threadgroup":
                return "Thread Group";
            case "responseassert":
                return "Response Assertion";
            case "jsonassertion":
                return "JSON Path Assertion";
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
            case "poissonrandomtimer":
                return "Poisson Random Timer";
            case "regexextractor":
                return "Regular Expression Extractor";
            case "xpathextractor":
                return "XPath Extractor";
            case "jsonpathextractor":
                return "JSON Path Extractor";
            case "boundaryextractor":
                return "Boundary Extractor";
            case "viewresultstree":
                return "View Results Tree";
            case "aggregatereport":
                return "Aggregate Report";
            default:
                // Convert camelCase to Title Case with spaces
                String name = normalizedType.replaceAll("([a-z])([A-Z])", "$1 $2");
                name = name.substring(0, 1).toUpperCase() + name.substring(1);

                return name;
        }
    }

    /**
     * Gets the map of element types to class names.
     * 
     * @return The element class map
     */
    public static Map<String, String> getElementClassMap() {
        return ELEMENT_CLASS_MAP;
    }

    /**
     * Checks if the given element type is supported.
     * 
     * @param elementType The element type to check
     * @return true if the element type is supported, false otherwise
     */
    public static boolean isElementTypeSupported(String elementType) {
        String normalizedType = normalizeElementType(elementType);
        return ELEMENT_CLASS_MAP.containsKey(normalizedType);
    }

    /**
     * Gets a list of all supported element types.
     * 
     * @return A string containing all supported element types
     */
    public static String getSupportedElementTypes() {
        StringBuilder sb = new StringBuilder();

        // Group elements by category
        Map<String, StringBuilder> categories = new HashMap<>();
        categories.put("Samplers", new StringBuilder());
        categories.put("Controllers", new StringBuilder());
        categories.put("Config Elements", new StringBuilder());
        categories.put("Thread Groups", new StringBuilder());
        categories.put("Assertions", new StringBuilder());
        categories.put("Timers", new StringBuilder());
        categories.put("Extractors", new StringBuilder());
        categories.put("Listeners", new StringBuilder());

        // Add elements to their respective categories
        for (String key : ELEMENT_CLASS_MAP.keySet()) {
            String className = ELEMENT_CLASS_MAP.get(key);
            if (className.contains("sampler")) {
                categories.get("Samplers").append("- ").append(getDefaultNameForElement(key)).append("\n");
            } else if (className.contains("control")) {
                categories.get("Controllers").append("- ").append(getDefaultNameForElement(key)).append("\n");
            } else if (className.contains("config") || className.contains("manager")) {
                categories.get("Config Elements").append("- ").append(getDefaultNameForElement(key)).append("\n");
            } else if (className.contains("threads")) {
                categories.get("Thread Groups").append("- ").append(getDefaultNameForElement(key)).append("\n");
            } else if (className.contains("assertions")) {
                categories.get("Assertions").append("- ").append(getDefaultNameForElement(key)).append("\n");
            } else if (className.contains("timers")) {
                categories.get("Timers").append("- ").append(getDefaultNameForElement(key)).append("\n");
            } else if (className.contains("extractor")) {
                categories.get("Extractors").append("- ").append(getDefaultNameForElement(key)).append("\n");
            } else if (className.contains("visualizers") || className.contains("report")) {
                categories.get("Listeners").append("- ").append(getDefaultNameForElement(key)).append("\n");
            }
        }

        // Build the final string
        for (String category : categories.keySet()) {
            if (categories.get(category).length() > 0) {
                sb.append(category).append(":\n");
                sb.append(categories.get(category));
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * Gets the JMeter GUI class for an element type.
     * 
     * @param elementType The element type
     * @return The JMeter GUI class for the element type, or null if not found
     */
    public static Class<?> getJMeterGuiClass(String elementType) {
        String normalizedType = normalizeElementType(elementType);
        String className = ELEMENT_CLASS_MAP.get(normalizedType);

        if (className == null) {
            return null;
        }

        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            log.error("Class not found: {}", className, e);
            return null;
        }
    }

    /**
     * Gets the model class name from a GUI class name.
     * 
     * @param guiClassName The GUI class name
     * @return The model class name, or null if it cannot be determined
     */
    private static String getModelClassNameFromGuiClassName(String guiClassName) {
        // Common patterns for GUI class names and their corresponding model class names
        if (guiClassName.endsWith("Gui") || guiClassName.endsWith("GUI")) {
            // For example: org.apache.jmeter.threads.gui.ThreadGroupGui ->
            // org.apache.jmeter.threads.ThreadGroup
            String baseClassName = guiClassName.substring(0, guiClassName.length() - 3);

            // Handle special cases
            if (baseClassName.endsWith("TestSample")) {
                // For HTTP Test Sample Gui -> HTTPSamplerProxy
                if (baseClassName.contains("Http")) {
                    return "org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy";
                }
                return baseClassName + "r";
            }

            // Extract the package and class name
            int lastDot = baseClassName.lastIndexOf('.');
            if (lastDot > 0 && lastDot < baseClassName.length() - 1) {
                String packageName = baseClassName.substring(0, lastDot);
                String className = baseClassName.substring(lastDot + 1);

                // Handle gui package
                if (packageName.endsWith(".gui")) {
                    packageName = packageName.substring(0, packageName.length() - 4);
                }

                // Handle control.gui package
                if (packageName.endsWith(".control.gui")) {
                    packageName = packageName.substring(0, packageName.length() - 11);
                }

                // Handle special cases for class names
                if (className.endsWith("Panel")) {
                    className = className.substring(0, className.length() - 5);
                }

                if (className.equals("Assertion")) {
                    className = "ResponseAssertion";
                } else if (className.equals("HttpTestSample")) {
                    className = "HTTPSamplerProxy";
                }

                // Special handling for timers
                if (packageName.contains("timers")) {
                    log.info("Processing timer class: {}.{}", packageName, className);

                    // Handle specific timer types
                    if (className.equals("ConstantTimer")) {
                        return "org.apache.jmeter.timers.ConstantTimer";
                    } else if (className.equals("UniformRandomTimer")) {
                        return "org.apache.jmeter.timers.UniformRandomTimer";
                    } else if (className.equals("GaussianRandomTimer")) {
                        return "org.apache.jmeter.timers.GaussianRandomTimer";
                    } else if (className.contains("Timer")) {
                        // Generic handling for other timer types
                        return packageName + "." + className;
                    }
                }

                return packageName + "." + className;
            }
        }

        // Special case for HTTP Sampler
        if (guiClassName.equals("org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui")) {
            return "org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy";
        }

        // Special case for TestBeanGUI which is used by multiple components
        if (guiClassName.equals("org.apache.jmeter.testbeans.gui.TestBeanGUI")) {
            // We need context to determine which specific component this is
            String normalizedType = null;
            for (Map.Entry<String, String> entry : ELEMENT_CLASS_MAP.entrySet()) {
                if (entry.getValue().equals(guiClassName)) {
                    normalizedType = entry.getKey();
                    break;
                }
            }

            if (normalizedType != null) {
                if (normalizedType.equals("csvdataset")) {
                    return "org.apache.jmeter.config.CSVDataSet";
                }
                // Add more TestBeanGUI-based components as needed
            }

            // Default to CSV Data Set if we can't determine the specific type
            return "org.apache.jmeter.config.CSVDataSet";
        }

        return null;
    }

    /**
     * Main method for testing the functionality.
     * 
     * @param args Command line arguments
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: JMeterElementManager <elementType> [elementName]");
            System.out.println("Supported element types:");
            System.out.println(getSupportedElementTypes());
            return;
        }

        String elementType = args[0];
        String elementName = args.length > 1 ? args[1] : null;

        boolean success = addElement(elementType, elementName);

        if (success) {
            System.out.println("Successfully added " + elementType +
                    (elementName != null ? " named \"" + elementName + "\"" : "") +
                    " to the test plan.");
        } else {
            System.out.println("Failed to add " + elementType + " to the test plan.");
        }
    }

    /**
     * Status class for test plan readiness.
     */
    public static class TestPlanStatus {
        private final boolean ready;
        private final String errorMessage;

        public TestPlanStatus(boolean ready, String errorMessage) {
            this.ready = ready;
            this.errorMessage = errorMessage;
        }

        public boolean isReady() {
            return ready;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }
}
