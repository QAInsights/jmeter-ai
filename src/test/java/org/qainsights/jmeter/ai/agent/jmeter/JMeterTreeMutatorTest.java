package org.qainsights.jmeter.ai.agent.jmeter;

import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.MapProperty;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.tree.TreeNode;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link JMeterTreeMutator}. Uses Mockito to verify the correct
 * tree-model events fire and runs mutations synchronously via
 * {@link EdtExecutor#direct()}.
 */
class JMeterTreeMutatorTest {

    private JMeterTreeMutator mutator;
    private JMeterTreeModel model;
    private JMeterTreeNode node;
    private TestElement element;

    @BeforeEach
    void setUp() {
        mutator = new JMeterTreeMutator(EdtExecutor.direct());
        model = mock(JMeterTreeModel.class);
        node = mock(JMeterTreeNode.class);
        element = mock(TestElement.class);
        when(node.getTestElement()).thenReturn(element);
    }

    // ── updateProperty ─────────────────────────────────────────────────────────

    @Test
    void updateProperty_setsValueAndFiresNodeChanged() {
        assertTrue(mutator.updateProperty(model, node, "Foo", "bar"));
        verify(element).setProperty("Foo", "bar");
        verify(model).nodeChanged(node);
    }

    @Test
    void updateProperty_nullValue_storesEmptyString() {
        assertTrue(mutator.updateProperty(model, node, "Foo", null));
        verify(element).setProperty("Foo", "");
    }

    @Test
    void updateProperty_invalidInputs_returnFalse() {
        assertFalse(mutator.updateProperty(null, node, "Foo", "bar"));
        assertFalse(mutator.updateProperty(model, null, "Foo", "bar"));
        assertFalse(mutator.updateProperty(model, node, " ", "bar"));
    }

    @Test
    void updateProperty_whenMutationThrows_returnsFalse() {
        doThrow(new RuntimeException("boom")).when(model).nodeChanged(node);
        assertFalse(mutator.updateProperty(model, node, "Foo", "bar"));
    }

    @Test
    void updateProperty_existingCollectionProperty_isRejectedWithoutMutating() {
        when(element.getProperty("Asserion.test_strings")).thenReturn(mock(CollectionProperty.class));

        assertFalse(mutator.updateProperty(model, node, "Asserion.test_strings", "200"));
        verify(element, never()).setProperty(anyString(), anyString());
        verify(model, never()).nodeChanged(node);
    }

    @Test
    void updateProperty_existingMapProperty_isRejectedWithoutMutating() {
        when(element.getProperty("Foo")).thenReturn(mock(MapProperty.class));

        assertFalse(mutator.updateProperty(model, node, "Foo", "bar"));
        verify(element, never()).setProperty(anyString(), anyString());
    }

    @Test
    void updateProperty_existingTestElementProperty_isRejectedWithoutMutating() {
        when(element.getProperty("Foo")).thenReturn(mock(TestElementProperty.class));

        assertFalse(mutator.updateProperty(model, node, "Foo", "bar"));
        verify(element, never()).setProperty(anyString(), anyString());
    }

    @Test
    void updateProperty_noExistingProperty_stillSucceeds() {
        when(element.getProperty("NewKey")).thenReturn(null);
        assertTrue(mutator.updateProperty(model, node, "NewKey", "value"));
        verify(element).setProperty("NewKey", "value");
    }

    // ── replacePropertyList ────────────────────────────────────────────────────

    @Test
    void replacePropertyList_noExistingProperty_succeeds() {
        when(element.getProperty("Asserion.test_strings")).thenReturn(null);
        assertTrue(mutator.replacePropertyList(model, node, "Asserion.test_strings",
                java.util.Arrays.asList("200", "201")));
        verify(model).nodeChanged(node);
    }

    @Test
    void replacePropertyList_existingFlatStringCollection_succeeds() {
        org.apache.jmeter.testelement.property.CollectionProperty existing =
                new org.apache.jmeter.testelement.property.CollectionProperty(
                        "Asserion.test_strings", new java.util.ArrayList<>(java.util.List.of("old")));
        when(element.getProperty("Asserion.test_strings")).thenReturn(existing);

        assertTrue(mutator.replacePropertyList(model, node, "Asserion.test_strings",
                java.util.Collections.singletonList("200")));
    }

    @Test
    void replacePropertyList_existingStructuredCollection_isRejected() {
        org.apache.jmeter.testelement.property.CollectionProperty existing = mock(
                org.apache.jmeter.testelement.property.CollectionProperty.class);
        org.apache.jmeter.testelement.property.PropertyIterator iterator = mock(
                org.apache.jmeter.testelement.property.PropertyIterator.class);
        when(existing.iterator()).thenReturn(iterator);
        when(iterator.hasNext()).thenReturn(true, false);
        when(iterator.next()).thenReturn(mock(org.apache.jmeter.testelement.property.TestElementProperty.class));
        when(element.getProperty("HeaderManager.headers")).thenReturn(existing);

        assertFalse(mutator.replacePropertyList(model, node, "HeaderManager.headers",
                java.util.Collections.singletonList("x")));
        verify(model, never()).nodeChanged(node);
    }

    @Test
    void replacePropertyList_existingScalarProperty_isRejected() {
        when(element.getProperty("Foo")).thenReturn(mock(org.apache.jmeter.testelement.property.StringProperty.class));
        assertFalse(mutator.replacePropertyList(model, node, "Foo", java.util.Collections.singletonList("x")));
    }

    @Test
    void replacePropertyList_invalidInputs_returnFalse() {
        assertFalse(mutator.replacePropertyList(null, node, "Foo", java.util.Collections.emptyList()));
        assertFalse(mutator.replacePropertyList(model, null, "Foo", java.util.Collections.emptyList()));
        assertFalse(mutator.replacePropertyList(model, node, " ", java.util.Collections.emptyList()));
        assertFalse(mutator.replacePropertyList(model, node, "Foo", null));
    }

    // ── setEnabled ─────────────────────────────────────────────────────────────

    @Test
    void setEnabled_togglesNodeAndFiresNodeChanged() {
        assertTrue(mutator.setEnabled(model, node, false));
        verify(node).setEnabled(false);
        verify(model).nodeChanged(node);
    }

    // ── deleteElement ──────────────────────────────────────────────────────────

    @Test
    void deleteElement_leaf_removesFromParent() {
        when(node.getParent()).thenReturn(mock(TreeNode.class));
        when(node.getChildCount()).thenReturn(0);
        assertTrue(mutator.deleteElement(model, node, false));
        verify(model).removeNodeFromParent(node);
    }

    @Test
    void deleteElement_nonEmptyWithoutForce_isRejected() {
        when(node.getParent()).thenReturn(mock(TreeNode.class));
        when(node.getChildCount()).thenReturn(2);
        assertFalse(mutator.deleteElement(model, node, false));
        verify(model, never()).removeNodeFromParent(any());
    }

    @Test
    void deleteElement_nonEmptyWithForce_removesFromParent() {
        when(node.getParent()).thenReturn(mock(TreeNode.class));
        when(node.getChildCount()).thenReturn(2);
        assertTrue(mutator.deleteElement(model, node, true));
        verify(model).removeNodeFromParent(node);
    }

    @Test
    void deleteElement_root_isRejected() {
        when(node.getParent()).thenReturn(null);
        assertFalse(mutator.deleteElement(model, node, true));
        verify(model, never()).removeNodeFromParent(any());
    }

    // ── moveElement ────────────────────────────────────────────────────────────

    @Test
    void moveElement_appendsUnderNewParent() {
        JMeterTreeNode newParent = mock(JMeterTreeNode.class);
        when(node.getParent()).thenReturn(mock(TreeNode.class));
        when(newParent.getParent()).thenReturn(null);
        when(newParent.getChildCount()).thenReturn(3);
        assertTrue(mutator.moveElement(model, node, newParent));
        verify(model).removeNodeFromParent(node);
        verify(model).insertNodeInto(node, newParent, 3);
    }

    @Test
    void moveElement_underItself_isRejected() {
        when(node.getParent()).thenReturn(mock(TreeNode.class));
        assertFalse(mutator.moveElement(model, node, node));
        verify(model, never()).removeNodeFromParent(any());
    }

    @Test
    void moveElement_underOwnDescendant_isRejected() {
        JMeterTreeNode child = mock(JMeterTreeNode.class);
        when(node.getParent()).thenReturn(mock(TreeNode.class));
        when(child.getParent()).thenReturn(node);
        assertFalse(mutator.moveElement(model, node, child));
        verify(model, never()).removeNodeFromParent(any());
    }

    @Test
    void moveElement_nullNewParent_isRejected() {
        when(node.getParent()).thenReturn(mock(TreeNode.class));
        assertFalse(mutator.moveElement(model, node, null));
    }
}
