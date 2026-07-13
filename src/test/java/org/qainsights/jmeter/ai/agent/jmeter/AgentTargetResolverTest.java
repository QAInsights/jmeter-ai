package org.qainsights.jmeter.ai.agent.jmeter;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgentTargetResolver}. Uses real {@link JMeterTreeNode}s
 * wrapping lightweight {@link ConfigTestElement}s under an internal wrapper root,
 * mirroring JMeter's live tree shape (see {@link ElementIdResolverTest}).
 */
class AgentTargetResolverTest {

    private AgentTargetResolver resolver;
    private JMeterTreeNode wrapperRoot;
    private JMeterTreeNode testPlan;
    private JMeterTreeNode threadGroup;
    private JMeterTreeNode httpRequest;

    private static JMeterTreeNode node(String name) {
        ConfigTestElement element = new ConfigTestElement();
        element.setName(name);
        return new JMeterTreeNode(element, null);
    }

    @BeforeEach
    void setUp() {
        resolver = new AgentTargetResolver();
        wrapperRoot = new JMeterTreeNode();
        testPlan = node("Test Plan");
        threadGroup = node("Thread Group");
        httpRequest = node("HTTP Request");
        wrapperRoot.add(testPlan);
        testPlan.add(threadGroup);
        threadGroup.add(httpRequest);
    }

    @Test
    void resolve_knownElementId_returnsThatNode() {
        assertSame(httpRequest, resolver.resolve(wrapperRoot, "Test Plan/Thread Group/HTTP Request"));
    }

    @Test
    void resolve_nullElementId_fallsBackToTestPlanNode() {
        assertSame(testPlan, resolver.resolve(wrapperRoot, null));
    }

    @Test
    void resolve_blankElementId_fallsBackToTestPlanNode() {
        assertSame(testPlan, resolver.resolve(wrapperRoot, "   "));
    }

    @Test
    void resolve_unknownElementId_fallsBackToTestPlanNode() {
        assertSame(testPlan, resolver.resolve(wrapperRoot, "Test Plan/Nonexistent"));
    }

    @Test
    void resolve_elementIdForTestPlanItself_returnsTestPlanNode() {
        assertSame(testPlan, resolver.resolve(wrapperRoot, "Test Plan"));
    }

    @Test
    void resolve_nullRoot_returnsNull() {
        assertNull(resolver.resolve(null, "Test Plan"));
    }

    @Test
    void resolve_rootWithNoChildren_returnsNullFallback() {
        JMeterTreeNode emptyWrapper = new JMeterTreeNode();
        assertNull(resolver.resolve(emptyWrapper, "anything"));
        assertNull(resolver.resolve(emptyWrapper, null));
    }
}
