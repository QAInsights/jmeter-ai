package org.qainsights.jmeter.ai.gui;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.qainsights.jmeter.ai.utils.JMeterElementManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.tree.TreeNode;

/**
 * Provides information about the currently selected JMeter test plan element,
 * including properties, hierarchy, and context-aware element suggestions.
 */
public class ElementInfoProvider {
    private static final Logger log = LoggerFactory.getLogger(ElementInfoProvider.class);

    /**
     * Returns a formatted markdown string describing the currently selected element,
     * or {@code null} if no element is selected.
     *
     * @return element info string, or null if nothing is selected
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

            TestElement element = currentNode.getTestElement();
            if (element == null) {
                log.warn("Selected node does not have a test element");
                return null;
            }

            StringBuilder info = new StringBuilder();
            info.append("# ").append(currentNode.getName()).append(" (").append(element.getClass().getSimpleName())
                    .append(")\n\n");

            String elementType = element.getClass().getSimpleName();
            info.append(JMeterElementManager.getElementDescription(elementType)).append("\n\n");

            info.append("## Properties\n\n");
            PropertyIterator propertyIterator = element.propertyIterator();
            while (propertyIterator.hasNext()) {
                JMeterProperty property = propertyIterator.next();
                String propertyName = property.getName();
                String propertyValue = property.getStringValue();

                if (!propertyValue.isEmpty() && !propertyName.startsWith("TestElement.")
                        && !propertyName.equals("guiclass")) {
                    String formattedName = propertyName.replace(".", " ").replace("_", " ");
                    formattedName = formattedName.substring(0, 1).toUpperCase() + formattedName.substring(1);
                    info.append("- **").append(formattedName).append("**: ").append(propertyValue).append("\n");
                }
            }

            info.append("\n## Location in Test Plan\n\n");
            TreeNode parent = currentNode.getParent();
            if (parent instanceof JMeterTreeNode) {
                JMeterTreeNode parentNode = (JMeterTreeNode) parent;
                info.append("- Parent: **").append(parentNode.getName()).append("** (")
                        .append(parentNode.getTestElement().getClass().getSimpleName()).append(")\n");
            }

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
     * Returns context-aware element suggestions based on the node type label.
     *
     * @param nodeType the static label of the node
     * @return array of suggestion name arrays
     */
    public String[][] getContextAwareSuggestions(String nodeType) {
        String type = nodeType.toLowerCase();

        if (type.contains("test plan")) {
            return new String[][]{
                    {"Thread Group"},
                    {"HTTP Cookie Manager"},
                    {"HTTP Header Manager"}
            };
        } else if (type.contains("thread group")) {
            return new String[][]{
                    {"HTTP Request"},
                    {"Loop Controller"},
                    {"If Controller"}
            };
        } else if (type.contains("http request")) {
            return new String[][]{
                    {"Response Assertion"},
                    {"JSON Extractor"},
                    {"Constant Timer"}
            };
        } else if (type.contains("controller")) {
            return new String[][]{
                    {"HTTP Request"},
                    {"Debug Sampler"},
                    {"JSR223 Sampler"}
            };
        } else {
            return new String[][]{
                    {"Thread Group"},
                    {"HTTP Request"},
                    {"Response Assertion"}
            };
        }
    }
}
