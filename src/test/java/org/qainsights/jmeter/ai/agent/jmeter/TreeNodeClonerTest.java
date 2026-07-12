package org.qainsights.jmeter.ai.agent.jmeter;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.threads.ThreadGroup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TreeNodeCloner}, using real JMeter test elements so
 * the clone is verified against actual {@code TestElement.clone()}/
 * {@code JMeterTreeNode.clone()} behavior, not an assumption about it.
 */
class TreeNodeClonerTest {

    private static JMeterTreeNode node(String name) {
        ConfigTestElement element = new ConfigTestElement();
        element.setName(name);
        return new JMeterTreeNode(element, null);
    }

    @Test
    void cloneSubtree_leafNode_copiesNameAndDetachesFromOriginalParent() {
        JMeterTreeNode parent = node("Parent");
        JMeterTreeNode original = node("Original");
        parent.add(original);

        JMeterTreeNode clone = TreeNodeCloner.cloneSubtree(original);

        assertEquals("Original", clone.getName());
        assertNull(clone.getParent());
        assertNotSame(original, clone);
    }

    @Test
    void cloneSubtree_deepClonesTestElement_notSameInstance() {
        JMeterTreeNode original = node("Original");

        JMeterTreeNode clone = TreeNodeCloner.cloneSubtree(original);

        assertNotSame(original.getTestElement(), clone.getTestElement());
    }

    @Test
    void cloneSubtree_mutatingCloneDoesNotAffectOriginal() {
        JMeterTreeNode original = node("Original");

        JMeterTreeNode clone = TreeNodeCloner.cloneSubtree(original);
        clone.getTestElement().setProperty("Custom.key", "cloned-value");

        assertTrue(original.getTestElement().getProperty("Custom.key")
                instanceof org.apache.jmeter.testelement.property.NullProperty);
    }

    @Test
    void cloneSubtree_recursivelyClonesChildren() {
        JMeterTreeNode threadGroupNode = new JMeterTreeNode(new ThreadGroup(), null);
        JMeterTreeNode child1 = node("Child 1");
        JMeterTreeNode child2 = node("Child 2");
        threadGroupNode.add(child1);
        threadGroupNode.add(child2);

        JMeterTreeNode clone = TreeNodeCloner.cloneSubtree(threadGroupNode);

        assertEquals(2, clone.getChildCount());
        assertEquals("Child 1", ((JMeterTreeNode) clone.getChildAt(0)).getName());
        assertEquals("Child 2", ((JMeterTreeNode) clone.getChildAt(1)).getName());
        assertNotSame(child1, clone.getChildAt(0));
        assertNotSame(child1.getTestElement(), ((JMeterTreeNode) clone.getChildAt(0)).getTestElement());
    }

    @Test
    void cloneSubtree_recursivelyClonesGrandchildren() {
        JMeterTreeNode grandparent = new JMeterTreeNode(new ThreadGroup(), null);
        JMeterTreeNode parent = node("Parent");
        JMeterTreeNode grandchild = node("Grandchild");
        grandparent.add(parent);
        parent.add(grandchild);

        JMeterTreeNode clone = TreeNodeCloner.cloneSubtree(grandparent);

        JMeterTreeNode clonedParent = (JMeterTreeNode) clone.getChildAt(0);
        assertEquals(1, clonedParent.getChildCount());
        JMeterTreeNode clonedGrandchild = (JMeterTreeNode) clonedParent.getChildAt(0);
        assertEquals("Grandchild", clonedGrandchild.getName());
        assertNotSame(grandchild, clonedGrandchild);
    }

    @Test
    void cloneSubtree_noChildren_producesNoChildren() {
        JMeterTreeNode original = node("Leaf");

        JMeterTreeNode clone = TreeNodeCloner.cloneSubtree(original);

        assertEquals(0, clone.getChildCount());
    }
}
