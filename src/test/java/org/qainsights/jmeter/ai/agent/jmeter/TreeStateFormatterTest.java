package org.qainsights.jmeter.ai.agent.jmeter;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link TreeStateFormatter}. */
class TreeStateFormatterTest {

    private TreeStateFormatter formatter;
    private JMeterTreeNode root;
    private JMeterTreeNode testPlan;
    private JMeterTreeNode threadGroup;
    private JMeterTreeNode sampler;

    private static JMeterTreeNode node(String name) {
        ConfigTestElement element = new ConfigTestElement();
        element.setName(name);
        return new JMeterTreeNode(element, null);
    }

    @BeforeEach
    void setUp() {
        formatter = new TreeStateFormatter(new ElementIdResolver());
        testPlan = node("Test Plan");
        threadGroup = node("Thread Group");
        sampler = node("HTTP Request");
        testPlan.add(threadGroup);
        threadGroup.add(sampler);
        // The live JMeter tree root is an internal wrapper; the Test Plan is its
        // child. The formatter treats the passed root as the (unrendered) origin.
        root = new JMeterTreeNode();
        root.add(testPlan);
    }

    @Test
    void format_nullRoot_returnsEmptyString() {
        assertEquals("", formatter.format(null, 2));
    }

    @Test
    void format_includesTypeNameAndId() {
        String out = formatter.format(root, -1);
        assertTrue(out.contains("[ConfigTestElement] Test Plan (id=Test Plan)"));
        assertTrue(out.contains("(id=Test Plan/Thread Group)"));
        assertTrue(out.contains("(id=Test Plan/Thread Group/HTTP Request)"));
    }

    @Test
    void format_respectsMaxDepthAndMarksHiddenChildren() {
        String out = formatter.format(root, 1);
        assertTrue(out.contains("Thread Group"));
        assertFalse(out.contains("HTTP Request"), "Nodes below maxDepth must be hidden");
        assertTrue(out.contains("hidden"));
    }

    @Test
    void format_marksDisabledNodes() {
        threadGroup.getTestElement().setEnabled(false);
        String out = formatter.format(root, -1);
        assertTrue(out.contains("Thread Group [disabled]"));
    }

    @Test
    void format_skipsHiddenRootWithNullElement() {
        JMeterTreeNode hiddenRoot = new JMeterTreeNode();
        hiddenRoot.add(testPlan);
        String out = formatter.format(hiddenRoot, -1);
        // Test Plan must render at depth 0 (no leading indentation).
        assertTrue(out.startsWith("[ConfigTestElement] Test Plan"));
    }
}
