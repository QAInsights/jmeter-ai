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

    @Test
    void updateProperty_propertyOwnedByNestedTestElement_delegatesToNestedElement() {
        TestElement nested = mock(TestElement.class);
        TestElementProperty nestedProp = mock(TestElementProperty.class);
        when(nestedProp.getElement()).thenReturn(nested);

        org.apache.jmeter.testelement.property.PropertyIterator iterator =
                mock(org.apache.jmeter.testelement.property.PropertyIterator.class);
        when(iterator.hasNext()).thenReturn(true, false);
        when(iterator.next()).thenReturn(nestedProp);

        when(element.getProperty("LoopController.loops"))
                .thenReturn(new org.apache.jmeter.testelement.property.NullProperty());
        when(element.propertyIterator()).thenReturn(iterator);
        when(nested.getProperty("LoopController.loops"))
                .thenReturn(new org.apache.jmeter.testelement.property.IntegerProperty("LoopController.loops", 1));

        assertTrue(mutator.updateProperty(model, node, "LoopController.loops", "-1"));

        verify(nested).setProperty("LoopController.loops", "-1");
        verify(element, never()).setProperty(anyString(), anyString());
        verify(model).nodeChanged(node);
    }

    @Test
    void updateProperty_propertyNotFoundOnNestedElements_fallsBackToTopLevelElement() {
        org.apache.jmeter.testelement.property.PropertyIterator iterator =
                mock(org.apache.jmeter.testelement.property.PropertyIterator.class);
        when(iterator.hasNext()).thenReturn(false);

        when(element.getProperty("NewKey")).thenReturn(new org.apache.jmeter.testelement.property.NullProperty());
        when(element.propertyIterator()).thenReturn(iterator);

        assertTrue(mutator.updateProperty(model, node, "NewKey", "value"));
        verify(element).setProperty("NewKey", "value");
    }

    @Test
    void updateProperty_nestedPropertyIsNonScalar_isRejectedWithoutMutating() {
        TestElement nested = mock(TestElement.class);
        TestElementProperty nestedProp = mock(TestElementProperty.class);
        when(nestedProp.getElement()).thenReturn(nested);

        org.apache.jmeter.testelement.property.PropertyIterator iterator =
                mock(org.apache.jmeter.testelement.property.PropertyIterator.class);
        when(iterator.hasNext()).thenReturn(true, false);
        when(iterator.next()).thenReturn(nestedProp);

        when(element.getProperty("Foo.bar")).thenReturn(new org.apache.jmeter.testelement.property.NullProperty());
        when(element.propertyIterator()).thenReturn(iterator);
        when(nested.getProperty("Foo.bar")).thenReturn(mock(CollectionProperty.class));

        assertFalse(mutator.updateProperty(model, node, "Foo.bar", "x"));
        verify(nested, never()).setProperty(anyString(), anyString());
        verify(element, never()).setProperty(anyString(), anyString());
        verify(model, never()).nodeChanged(node);
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

    // ── replaceStructuredPropertyList ──────────────────────────────────────────

    @Test
    void replaceStructuredPropertyList_headerManagerHeaders_noExistingProperty_succeeds() {
        when(element.getProperty("HeaderManager.headers")).thenReturn(null);
        java.util.Map<String, String> entry = new java.util.LinkedHashMap<>();
        entry.put("name", "Content-Type");
        entry.put("value", "application/json");

        assertTrue(mutator.replaceStructuredPropertyList(model, node, "HeaderManager.headers",
                java.util.Collections.singletonList(entry)));
        verify(model).nodeChanged(node);
    }

    @Test
    void replaceStructuredPropertyList_argumentsArguments_noExistingProperty_succeeds() {
        when(element.getProperty("Arguments.arguments")).thenReturn(null);
        java.util.Map<String, String> entry = new java.util.LinkedHashMap<>();
        entry.put("name", "userId");
        entry.put("value", "123");

        assertTrue(mutator.replaceStructuredPropertyList(model, node, "Arguments.arguments",
                java.util.Collections.singletonList(entry)));
        verify(model).nodeChanged(node);
    }

    @Test
    void replaceStructuredPropertyList_authManagerAuthList_validMechanism_succeeds() {
        when(element.getProperty("AuthManager.auth_list")).thenReturn(null);
        java.util.Map<String, String> entry = new java.util.LinkedHashMap<>();
        entry.put("url", "https://example.com");
        entry.put("username", "bob");
        entry.put("password", "secret");
        entry.put("mechanism", "digest");

        assertTrue(mutator.replaceStructuredPropertyList(model, node, "AuthManager.auth_list",
                java.util.Collections.singletonList(entry)));
        verify(model).nodeChanged(node);
    }

    @Test
    void replaceStructuredPropertyList_authManagerAuthList_invalidMechanism_isRejected() {
        when(element.getProperty("AuthManager.auth_list")).thenReturn(null);
        java.util.Map<String, String> entry = new java.util.LinkedHashMap<>();
        entry.put("url", "https://example.com");
        entry.put("mechanism", "not-a-real-mechanism");

        assertFalse(mutator.replaceStructuredPropertyList(model, node, "AuthManager.auth_list",
                java.util.Collections.singletonList(entry)));
        verify(model, never()).nodeChanged(node);
    }

    @Test
    void replaceStructuredPropertyList_unsupportedProperty_isRejected() {
        when(element.getProperty("Foo.bar")).thenReturn(null);
        assertFalse(mutator.replaceStructuredPropertyList(model, node, "Foo.bar",
                java.util.Collections.singletonList(java.util.Collections.singletonMap("name", "x"))));
        verify(model, never()).nodeChanged(node);
    }

    @Test
    void replaceStructuredPropertyList_existingFlatStringCollection_isRejected() {
        org.apache.jmeter.testelement.property.CollectionProperty existing =
                new org.apache.jmeter.testelement.property.CollectionProperty(
                        "HeaderManager.headers", new java.util.ArrayList<>(java.util.List.of("not-a-header")));
        when(element.getProperty("HeaderManager.headers")).thenReturn(existing);

        assertFalse(mutator.replaceStructuredPropertyList(model, node, "HeaderManager.headers",
                java.util.Collections.emptyList()));
        verify(model, never()).nodeChanged(node);
    }

    @Test
    void replaceStructuredPropertyList_invalidInputs_returnFalse() {
        assertFalse(mutator.replaceStructuredPropertyList(null, node, "HeaderManager.headers",
                java.util.Collections.emptyList()));
        assertFalse(mutator.replaceStructuredPropertyList(model, null, "HeaderManager.headers",
                java.util.Collections.emptyList()));
        assertFalse(mutator.replaceStructuredPropertyList(model, node, " ", java.util.Collections.emptyList()));
        assertFalse(mutator.replaceStructuredPropertyList(model, node, "HeaderManager.headers", null));
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

    // ── renameElement ──────────────────────────────────────────────────────────

    @Test
    void renameElement_setsNameAndFiresNodeChanged() {
        assertTrue(mutator.renameElement(model, node, "New Name"));
        verify(node).setName("New Name");
        verify(model).nodeChanged(node);
    }

    @Test
    void renameElement_invalidInputs_returnFalse() {
        assertFalse(mutator.renameElement(null, node, "New Name"));
        assertFalse(mutator.renameElement(model, null, "New Name"));
        assertFalse(mutator.renameElement(model, node, null));
        assertFalse(mutator.renameElement(model, node, " "));
        verify(node, never()).setName(any());
    }

    @Test
    void renameElement_whenMutationThrows_returnsFalse() {
        doThrow(new RuntimeException("boom")).when(node).setName("New Name");
        assertFalse(mutator.renameElement(model, node, "New Name"));
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

    // ── duplicateElement ───────────────────────────────────────────────────────

    @Test
    void duplicateElement_invalidInputs_returnNull() {
        assertNull(mutator.duplicateElement(null, node));
        assertNull(mutator.duplicateElement(model, null));
    }

    @Test
    void duplicateElement_nodeWithoutParent_returnsNull() {
        when(node.getParent()).thenReturn(null);
        assertNull(mutator.duplicateElement(model, node));
    }

    @Test
    void duplicateElement_parentNotJMeterTreeNode_returnsNull() {
        when(node.getParent()).thenReturn(mock(TreeNode.class));
        assertNull(mutator.duplicateElement(model, node));
    }
}
