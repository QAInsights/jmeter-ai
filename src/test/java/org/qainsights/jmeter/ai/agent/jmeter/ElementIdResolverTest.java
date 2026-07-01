package org.qainsights.jmeter.ai.agent.jmeter;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ElementIdResolver}. Uses real {@link JMeterTreeNode}s
 * wrapping lightweight {@link ConfigTestElement}s (no running JMeter needed).
 */
class ElementIdResolverTest {

    private ElementIdResolver resolver;
    private JMeterTreeNode root;
    private JMeterTreeNode threadGroup;
    private JMeterTreeNode http1;
    private JMeterTreeNode http2;
    private JMeterTreeNode timer;

    private static JMeterTreeNode node(String name) {
        ConfigTestElement element = new ConfigTestElement();
        element.setName(name);
        return new JMeterTreeNode(element, null);
    }

    @BeforeEach
    void setUp() {
        resolver = new ElementIdResolver();
        root = node("Test Plan");
        threadGroup = node("Thread Group");
        http1 = node("HTTP Request");
        http2 = node("HTTP Request");
        timer = node("Constant Timer");
        root.add(threadGroup);
        threadGroup.add(http1);
        threadGroup.add(http2);
        threadGroup.add(timer);
        // Mirror JMeter's live tree: the real Test Plan is the child of an internal
        // wrapper root, which the resolver treats as the (excluded) origin.
        JMeterTreeNode wrapperRoot = new JMeterTreeNode();
        wrapperRoot.add(root);
    }

    @Test
    void idOf_rootIsJustItsName() {
        assertEquals("Test Plan", resolver.idOf(root));
    }

    @Test
    void idOf_buildsNestedPath() {
        assertEquals("Test Plan/Thread Group", resolver.idOf(threadGroup));
    }

    @Test
    void idOf_uniqueSibling_hasNoIndexSuffix() {
        assertEquals("Test Plan/Thread Group/Constant Timer", resolver.idOf(timer));
    }

    @Test
    void idOf_duplicateSiblings_getOneBasedIndices() {
        assertEquals("Test Plan/Thread Group/HTTP Request[1]", resolver.idOf(http1));
        assertEquals("Test Plan/Thread Group/HTTP Request[2]", resolver.idOf(http2));
    }

    @Test
    void idOf_nullOrElementlessNode_returnsNull() {
        assertNull(resolver.idOf(null));
        assertNull(resolver.idOf(new JMeterTreeNode()));
    }

    @Test
    void resolve_roundTripsEveryNode() {
        for (JMeterTreeNode n : new JMeterTreeNode[]{root, threadGroup, http1, http2, timer}) {
            assertSame(n, resolver.resolve(root, resolver.idOf(n)));
        }
    }

    @Test
    void resolve_unknownId_returnsNull() {
        assertNull(resolver.resolve(root, "Test Plan/Nope"));
    }

    @Test
    void resolve_nullArguments_returnNull() {
        assertNull(resolver.resolve(null, "Test Plan"));
        assertNull(resolver.resolve(root, null));
        assertNull(resolver.resolve(root, ""));
    }
}
