package org.qainsights.jmeter.ai.claudecode;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Enumeration;

/**
 * Serializes the current JMeter test plan tree into a human-readable
 * text format suitable for providing context to Claude Code.
 */
public class TestPlanSerializer {
    private static final Logger log = LoggerFactory.getLogger(TestPlanSerializer.class);

    private TestPlanSerializer() {
        // utility class
    }

    /**
     * Serializes the entire test plan tree to a readable text format.
     *
     * @return the serialized test plan, or a message if no test plan is available
     */
    public static String serializeTestPlan() {
        try {
            GuiPackage guiPackage = GuiPackage.getInstance();
            if (guiPackage == null) {
                return "No test plan is currently open in JMeter.";
            }

            JMeterTreeModel treeModel = guiPackage.getTreeModel();
            if (treeModel == null) {
                return "No test plan tree model available.";
            }

            JMeterTreeNode rootNode = (JMeterTreeNode) treeModel.getRoot();
            if (rootNode == null) {
                return "Test plan tree is empty.";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("=== JMeter Test Plan Structure ===\n\n");
            serializeNode(rootNode, sb, 0);
            return sb.toString();

        } catch (Exception e) {
            log.error("Error serializing test plan", e);
            return "Error reading test plan: " + e.getMessage();
        }
    }

    /**
     * Recursively serializes a tree node and its children.
     *
     * @param node  the tree node to serialize
     * @param sb    the StringBuilder to append to
     * @param depth the current indentation depth
     */
    private static void serializeNode(JMeterTreeNode node, StringBuilder sb, int depth) {
        String indent = getIndent(depth);
        TestElement element = node.getTestElement();

        if (element == null) {
            return;
        }

        String elementType = element.getClass().getSimpleName();
        String nodeName = node.getName();

        // Write element header
        sb.append(indent).append("[").append(elementType).append("] ").append(nodeName).append("\n");

        // Write non-empty, non-internal properties
        PropertyIterator propertyIterator = element.propertyIterator();
        boolean hasProperties = false;
        while (propertyIterator.hasNext()) {
            JMeterProperty property = propertyIterator.next();
            String propName = property.getName();
            String propValue = property.getStringValue();

            // Skip empty, internal, and GUI-related properties
            if (propValue != null && !propValue.isEmpty()
                    && !propName.startsWith("TestElement.")
                    && !propName.equals("guiclass")
                    && !propName.equals("testclass")
                    && !propName.equals("testname")
                    && !propName.equals("enabled")) {
                if (!hasProperties) {
                    hasProperties = true;
                }
                sb.append(indent).append("  | ").append(propName).append(" = ").append(truncateValue(propValue))
                        .append("\n");
            }
        }

        // Check if element is enabled
        if (!element.isEnabled()) {
            sb.append(indent).append("  | [DISABLED]\n");
        }

        // Recurse into children
        Enumeration<?> children = node.children();
        while (children.hasMoreElements()) {
            Object child = children.nextElement();
            if (child instanceof JMeterTreeNode) {
                serializeNode((JMeterTreeNode) child, sb, depth + 1);
            }
        }
    }

    /**
     * Creates an indentation string for the given depth.
     */
    private static String getIndent(int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        return sb.toString();
    }

    /**
     * Truncates long property values to keep the output manageable.
     *
     * @param value the property value
     * @return the truncated value
     */
    private static String truncateValue(String value) {
        int maxLength = 500;
        if (value.length() > maxLength) {
            return value.substring(0, maxLength) + "... [truncated]";
        }
        // Replace newlines for single-line display
        return value.replace("\n", "\\n").replace("\r", "");
    }
}
