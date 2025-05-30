package org.nfe.jmeter.ai.gui;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages element suggestions for the JMeter AI chat interface.
 * This class is responsible for creating and managing element suggestion buttons.
 */
public class ElementSuggestionManager {
    private static final Logger log = LoggerFactory.getLogger(ElementSuggestionManager.class);
    
    private static final Pattern ELEMENT_SUGGESTION_PATTERN = Pattern.compile(
            "(?i)(?:add|create|use|include|try|implement|insert)\\s+(?:a|an)?\\s+([a-z\\s-]+?)(?:\\s+(?:called|named|with name|with the name)?\\s+[\"']?([^\"']+?)[\"']?)?(?:\\s*$|\\s+(?:to|in|for|as)\\b)");
    
    private final JPanel navigationPanel;
    
    /**
     * Constructs a new ElementSuggestionManager.
     * 
     * @param navigationPanel The panel to add element buttons to
     */
    public ElementSuggestionManager(JPanel navigationPanel) {
        this.navigationPanel = navigationPanel;
    }
    
    /**
     * Creates element buttons based on the message content.
     * 
     * @param message The message to extract element suggestions from
     */
    public void createElementButtons(String message) {
        for (Component component : navigationPanel.getComponents()) {
            if (component instanceof JButton) {
                JButton button = (JButton) component;
                log.info("Button found: text='{}', tooltip='{}'", 
                         button.getText(), button.getToolTipText());
            } else {
                log.info("Non-button component found: {}", component.getClass().getSimpleName());
            }
        }
        
        // Clear existing buttons but preserve navigation buttons and labels
        Component[] components = navigationPanel.getComponents();
        for (Component component : components) {
            // Keep navigation buttons (which have tooltips containing "Navigate") and labels
            if (component instanceof JButton) {
                JButton button = (JButton) component;
                String tooltip = button.getToolTipText();
                if (tooltip == null || !tooltip.contains("Navigate")) {
                    navigationPanel.remove(component);
                    log.debug("Removed button: {}", button.getText());
                } else {
                    log.debug("Preserved navigation button with tooltip: {}", tooltip);
                }
            } else if (!(component instanceof JLabel)) {
                // Keep labels but remove other non-button components
                navigationPanel.remove(component);
            }
        }
        
        // Extract element suggestions from the message
        Matcher matcher = ELEMENT_SUGGESTION_PATTERN.matcher(message);
        boolean foundSuggestions = false;
        
        while (matcher.find()) {
            foundSuggestions = true;
            String elementType = matcher.group(1).trim().toLowerCase();
            String elementName = matcher.group(2);
            
            if (elementName != null) {
                elementName = elementName.trim();
            }
            
            // Normalize element type
            String normalizedType = mapToNormalizedElementType(elementType);
            if (normalizedType == null) {
                log.warn("Unknown element type: {}", elementType);
                continue;
            }
            
            // Create additional info text if element name is provided
            String additionalInfo = elementName != null ? " named \"" + elementName + "\"" : null;
            
            // Create a button for the element
            String displayName = formatElementType(normalizedType);
            createElementButton(displayName, normalizedType, additionalInfo);

        }
        
        // If no suggestions found, add some context-aware suggestions
        if (!foundSuggestions) {
            String[][] contextSuggestions = getContextAwareElementSuggestions();
            if (contextSuggestions != null && contextSuggestions.length > 0) {
                for (String[] suggestion : contextSuggestions) {
                    createElementButton(suggestion[0], suggestion[1], null);
                }
            }
        }
        
        // Make sure the navigation panel is visible
        navigationPanel.setVisible(true);
        
        // Force the panel to update its layout
        navigationPanel.revalidate();
        navigationPanel.repaint();
    }
    
    /**
     * Creates a button for adding a JMeter element.
     * 
     * @param displayName The display name of the element
     * @param normalizedType The normalized type of the element
     * @param additionalInfo Additional information to display in the tooltip
     * @return The created button
     */
    public JButton createElementButton(String displayName, String normalizedType, String additionalInfo) {
        // Create a button for the element
        JButton addButton = new JButton("Add " + displayName);

        // Set button appearance
        addButton.setForeground(Color.BLACK); // Black text
        addButton.setFocusPainted(false);
        addButton.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(34, 139, 34), 1, true), // Forest green border
                BorderFactory.createEmptyBorder(5, 10, 5, 10)));

        // Add tooltip if additional info is provided
        if (additionalInfo != null && !additionalInfo.isEmpty()) {
            addButton.setToolTipText(additionalInfo);
        } else {
            addButton.setToolTipText("Add " + displayName + " to the test plan");
        }

        // Add action listener
        String finalNormalizedType = normalizedType;
        addButton.addActionListener(e -> {
            try {
                // Process the request directly without involving AI
                boolean success = org.nfe.jmeter.ai.utils.JMeterElementManager.addElement(finalNormalizedType, 
                    additionalInfo != null ? additionalInfo.replace(" named \"", "").replace("\"", "") : null);

                // Select the newly added element
                selectLastAddedElement();
                
                // Process the response - log the outcome for now
                if (success) {
                    log.info("Successfully added {} to the test plan", 
                            org.nfe.jmeter.ai.utils.JMeterElementManager.getDefaultNameForElement(finalNormalizedType));
                } else {
                    log.warn("Failed to add {} to the test plan", 
                            org.nfe.jmeter.ai.utils.JMeterElementManager.getDefaultNameForElement(finalNormalizedType));
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(null, 
                    "Error adding element: " + ex.getMessage(), 
                    "Error", 
                    JOptionPane.ERROR_MESSAGE);
            }
        });

        // Add the button to the navigation panel
        navigationPanel.add(addButton);
        
        return addButton;
    }
    
    /**
     * Selects the last added element in the JMeter tree.
     */
    private void selectLastAddedElement() {
        try {
            GuiPackage guiPackage = GuiPackage.getInstance();
            if (guiPackage == null) {
                log.warn("Cannot select element: GuiPackage is null");
                return;
            }

            JMeterTreeNode currentNode = guiPackage.getTreeListener().getCurrentNode();
            if (currentNode == null) {
                log.warn("Cannot select element: Current node is null");
                return;
            }

            // If the current node has children, select the last child
            if (currentNode.getChildCount() > 0) {
                JMeterTreeNode lastChild = (JMeterTreeNode) currentNode.getChildAt(currentNode.getChildCount() - 1);
                // Use setSelectionPath instead of selectNode
                guiPackage.getTreeListener().getJTree().setSelectionPath(new javax.swing.tree.TreePath(lastChild.getPath()));
            } else {
                log.info("Current node has no children to select");
            }
        } catch (Exception e) {
            log.error("Error selecting last added element", e);
        }
    }
    
    /**
     * Maps a user-friendly element type to a normalized JMeter element type.
     * 
     * @param elementType The user-friendly element type
     * @return The normalized JMeter element type, or null if not recognized
     */
    private String mapToNormalizedElementType(String elementType) {
        elementType = elementType.toLowerCase();

        // Generic element types
        if (elementType.contains("sampler")) {
            if (elementType.contains("http")) {
                return "httpsampler";
            }
            if (elementType.contains("jsr223") || elementType.contains("script")) {
                return "jsr223sampler";
            }
            if (elementType.contains("jdbc") || elementType.contains("database")) {
                return "jdbcsampler";
            }
            if (elementType.contains("debug")) {
                return "debugsampler";
            }
            // Default to HTTP sampler if no specific type is mentioned
            return "httpsampler";
        }

        if (elementType.contains("controller")) {
            if (elementType.contains("loop")) {
                return "loopcontroller";
            }
            if (elementType.contains("if")) {
                return "ifcontroller";
            }
            if (elementType.contains("while")) {
                return "whilecontroller";
            }
            if (elementType.contains("transaction")) {
                return "transactioncontroller";
            }
            if (elementType.contains("runtime")) {
                return "runtimecontroller";
            }
            // Default to loop controller if no specific type is mentioned
            return "loopcontroller";
        }

        if (elementType.contains("timer")) {
            if (elementType.contains("constant")) {
                return "constanttimer";
            }
            if (elementType.contains("uniform")) {
                return "uniformrandomtimer";
            }
            if (elementType.contains("gaussian")) {
                return "gaussianrandomtimer";
            }
            if (elementType.contains("poisson")) {
                return "poissonrandomtimer";
            }
            // Default to constant timer if no specific type is mentioned
            return "constanttimer";
        }

        if (elementType.contains("assertion")) {
            if (elementType.contains("response")) {
                return "responseassert";
            }
            if (elementType.contains("json")) {
                return "jsonassertion";
            }
            if (elementType.contains("duration")) {
                return "durationassertion";
            }
            if (elementType.contains("size")) {
                return "sizeassertion";
            }
            if (elementType.contains("xpath")) {
                return "xpathassertion";
            }
            // Default to response assertion if no specific type is mentioned
            return "responseassert";
        }

        if (elementType.contains("extractor")) {
            if (elementType.contains("regex")) {
                return "regexextractor";
            }
            if (elementType.contains("xpath")) {
                return "xpathextractor";
            }
            if (elementType.contains("json")) {
                return "jsonpathextractor";
            }
            if (elementType.contains("boundary")) {
                return "boundaryextractor";
            }
            // Default to regex extractor if no specific type is mentioned
            return "regexextractor";
        }

        if (elementType.contains("listener")) {
            if (elementType.contains("view") && elementType.contains("tree")) {
                return "viewresultstree";
            }
            if (elementType.contains("aggregate")) {
                return "aggregatereport";
            }
            if (elementType.contains("summary")) {
                return "summaryreport";
            }
            // Default to view results tree if no specific type is mentioned
            return "viewresultstree";
        }

        if (elementType.contains("jsr223") || elementType.contains("jsr 223") ||
                elementType.contains("javascript") || elementType.contains("groovy")) {
            if (elementType.contains("sampler")) {
                return "jsr223sampler";
            }
            if (elementType.contains("pre") && elementType.contains("processor")) {
                return "jsr223preprocessor";
            }
            if (elementType.contains("post") && elementType.contains("processor")) {
                return "jsr223postprocessor";
            }
            // Default to JSR223 sampler if no specific type is mentioned
            return "jsr223sampler";
        }

        // Specific element types
        if (elementType.contains("thread") && elementType.contains("group")) {
            return "threadgroup";
        }

        if (elementType.contains("csv") || elementType.contains("data set")) {
            return "csvdataset";
        }

        if (elementType.contains("header") && elementType.contains("manager")) {
            return "headermanager";
        }

        if (elementType.contains("cookie") && elementType.contains("manager")) {
            return "cookiemanager";
        }

        // Try to match partial element types
        if (elementType.contains("http")) {
            return "httpsampler";
        }

        if (elementType.contains("thread")) {
            return "threadgroup";
        }

        if (elementType.contains("csv")) {
            return "csvdataset";
        }

        if (elementType.contains("header")) {
            return "headermanager";
        }

        if (elementType.contains("cookie")) {
            return "cookiemanager";
        }

        // If we couldn't match the element type, return null
        return null;
    }
    
    /**
     * Formats a normalized element type into a user-friendly display name.
     * 
     * @param normalizedType The normalized element type
     * @return The user-friendly display name
     */
    private String formatElementType(String normalizedType) {
        if (normalizedType == null) {
            return "Unknown Element";
        }
        
        // Use the JMeterElementManager's getDefaultNameForElement method if available
        try {
            return org.nfe.jmeter.ai.utils.JMeterElementManager.getDefaultNameForElement(normalizedType);
        } catch (Exception e) {
            log.warn("Error using JMeterElementManager.getDefaultNameForElement", e);
        }
        
        // Fallback formatting logic
        switch (normalizedType.toLowerCase()) {
            // Thread Groups
            case "threadgroup":
                return "Thread Group";
            
            // Samplers
            case "httpsampler":
            case "httptestsample":
            case "httprequest":
                return "HTTP Request";
            case "jsr223sampler":
                return "JSR223 Sampler";
            case "jdbcsampler":
                return "JDBC Request";
            case "debugsampler":
                return "Debug Sampler";
            
            // Controllers
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
            case "runtimecontroller":
                return "Runtime Controller";
            
            // Config Elements
            case "cookiemanager":
            case "httpcookiemanager":
                return "HTTP Cookie Manager";
            case "headermanager":
            case "httpheadermanager":
                return "HTTP Header Manager";
            case "csvdataset":
                return "CSV Data Set";
            
            // Post Processors
            case "jsonextractor":
            case "jsonpathextractor":
            case "jsonpostprocessor":
                return "JSON Extractor";
            case "regexextractor":
                return "Regular Expression Extractor";
            case "xpathextractor":
                return "XPath Extractor";
            case "boundaryextractor":
                return "Boundary Extractor";
            
            // Assertions
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
            
            // Timers
            case "constanttimer":
                return "Constant Timer";
            case "uniformrandomtimer":
                return "Uniform Random Timer";
            case "gaussianrandomtimer":
                return "Gaussian Random Timer";
            case "poissonrandomtimer":
                return "Poisson Random Timer";
            
            // Listeners
            case "viewresultstree":
                return "View Results Tree";
            case "summaryreport":
                return "Summary Report";
            case "aggregatereport":
                return "Aggregate Report";
            
            default:
                // For other types, add spaces before capital letters and capitalize the first letter
                String formatted = normalizedType.replaceAll("([A-Z])", " $1").trim();
                if (formatted.length() > 0) {
                    return formatted.substring(0, 1).toUpperCase() + formatted.substring(1);
                } else {
                    return normalizedType;
                }
        }
    }
    
    /**
     * Gets context-aware element suggestions based on the current JMeter tree state.
     * 
     * @return An array of string arrays, each containing [displayName, normalizedType]
     */
    private String[][] getContextAwareElementSuggestions() {
        // Get the current JMeter node
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            return getDefaultSuggestions();
        }
        
        JMeterTreeNode currentNode = guiPackage.getTreeListener().getCurrentNode();
        if (currentNode == null) {
            return getDefaultSuggestions();
        }
        
        // Get the class name of the current node
        String className = currentNode.getStaticLabel();
        
        // Provide context-aware suggestions based on the current node
        switch (className) {
            case "Test Plan":
                return new String[][] {
                    {"Thread Group", "threadgroup"},
                    {"HTTP Cookie Manager", "cookiemanager"},
                    {"HTTP Header Manager", "headermanager"}
                };
                
            case "Thread Group":
                return new String[][] {
                    {"HTTP Request", "httptestsample"},
                    {"Loop Controller", "LoopController"},
                    {"If Controller", "ifcontroller"}
                };
                
            case "HTTP Request":
                return new String[][] {
                    {"Response Assertion", "responseassert"},
                    {"JSON Extractor", "jsonpostprocessor"},
                    {"Constant Timer", "constanttimer"}
                };
                
            case "Loop Controller":
            case "If Controller":
            case "While Controller":
                return new String[][] {
                    {"HTTP Request", "httptestsample"},
                    {"Debug Sampler", "debugsampler"},
                    {"JSR223 Sampler", "jsr223sampler"}
                };
                
            default:
                return getDefaultSuggestions();
        }
    }
    
    /**
     * Gets default element suggestions when no context is available.
     * 
     * @return An array of string arrays, each containing [displayName, normalizedType]
     */
    private String[][] getDefaultSuggestions() {
        return new String[][] {
            {"Thread Group", "threadgroup"},
            {"HTTP Request", "httptestsample"},
            {"Response Assertion", "responseassert"}
        };
    }
}
