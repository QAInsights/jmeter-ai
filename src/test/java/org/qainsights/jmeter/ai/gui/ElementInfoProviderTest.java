package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeListener;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ElementInfoProviderTest {

    @Mock
    private GuiPackage guiPackage;

    @Mock
    private JMeterTreeListener treeListener;

    @Mock
    private JMeterTreeNode currentNode;

    @Mock
    private JMeterTreeNode parentNode;

    @Mock
    private JMeterTreeNode childNode;

    @Mock
    private TestElement testElement;

    @Mock
    private TestElement parentTestElement;

    @Mock
    private TestElement childTestElement;

    @Mock
    private PropertyIterator propertyIterator;

    @Mock
    private JMeterProperty jmeterProperty;

    @Test
    void testGetContextAwareSuggestions() {
        ElementInfoProvider provider = new ElementInfoProvider();

        // Test Plan
        String[][] suggestions = provider.getContextAwareSuggestions("Test Plan");
        assertEquals(3, suggestions.length);
        assertEquals("Thread Group", suggestions[0][0]);

        // Thread Group
        suggestions = provider.getContextAwareSuggestions("Thread Group");
        assertEquals(3, suggestions.length);
        assertEquals("HTTP Request", suggestions[0][0]);

        // HTTP Request
        suggestions = provider.getContextAwareSuggestions("HTTP Request");
        assertEquals(3, suggestions.length);
        assertEquals("Response Assertion", suggestions[0][0]);

        // Controller
        suggestions = provider.getContextAwareSuggestions("Loop Controller");
        assertEquals(3, suggestions.length);
        assertEquals("HTTP Request", suggestions[0][0]);

        // Default
        suggestions = provider.getContextAwareSuggestions("Unknown");
        assertEquals(3, suggestions.length);
        assertEquals("Thread Group", suggestions[0][0]);
    }

    @Test
    void testGetCurrentElementInfo_GuiPackageNull() {
        ElementInfoProvider provider = new ElementInfoProvider();
        try (MockedStatic<GuiPackage> guiPackageMockedStatic = mockStatic(GuiPackage.class)) {
            guiPackageMockedStatic.when(GuiPackage::getInstance).thenReturn(null);
            assertNull(provider.getCurrentElementInfo());
        }
    }

    @Test
    void testGetCurrentElementInfo_CurrentNodeNull() {
        ElementInfoProvider provider = new ElementInfoProvider();
        try (MockedStatic<GuiPackage> guiPackageMockedStatic = mockStatic(GuiPackage.class)) {
            guiPackageMockedStatic.when(GuiPackage::getInstance).thenReturn(guiPackage);
            when(guiPackage.getTreeListener()).thenReturn(treeListener);
            when(treeListener.getCurrentNode()).thenReturn(null);

            assertNull(provider.getCurrentElementInfo());
        }
    }

    @Test
    void testGetCurrentElementInfo_TestElementNull() {
        ElementInfoProvider provider = new ElementInfoProvider();
        try (MockedStatic<GuiPackage> guiPackageMockedStatic = mockStatic(GuiPackage.class)) {
            guiPackageMockedStatic.when(GuiPackage::getInstance).thenReturn(guiPackage);
            when(guiPackage.getTreeListener()).thenReturn(treeListener);
            when(treeListener.getCurrentNode()).thenReturn(currentNode);
            when(currentNode.getTestElement()).thenReturn(null);

            assertNull(provider.getCurrentElementInfo());
        }
    }

    @Test
    void testGetCurrentElementInfo_Success() {
        ElementInfoProvider provider = new ElementInfoProvider();
        try (MockedStatic<GuiPackage> guiPackageMockedStatic = mockStatic(GuiPackage.class)) {
            guiPackageMockedStatic.when(GuiPackage::getInstance).thenReturn(guiPackage);
            when(guiPackage.getTreeListener()).thenReturn(treeListener);
            when(treeListener.getCurrentNode()).thenReturn(currentNode);
            when(currentNode.getTestElement()).thenReturn(testElement);
            when(currentNode.getName()).thenReturn("My HTTP Request");

            // Mock properties
            when(testElement.propertyIterator()).thenReturn(propertyIterator);
            when(propertyIterator.hasNext()).thenReturn(true, false);
            when(propertyIterator.next()).thenReturn(jmeterProperty);
            when(jmeterProperty.getName()).thenReturn("HTTPSampler.domain");
            when(jmeterProperty.getStringValue()).thenReturn("example.com");

            // Mock parent
            when(currentNode.getParent()).thenReturn(parentNode);
            when(parentNode.getName()).thenReturn("My Thread Group");
            when(parentNode.getTestElement()).thenReturn(parentTestElement);

            // Mock children
            when(currentNode.getChildCount()).thenReturn(1);
            when(currentNode.getChildAt(0)).thenReturn(childNode);
            when(childNode.getName()).thenReturn("JSON Extractor");
            when(childNode.getTestElement()).thenReturn(childTestElement);

            String info = provider.getCurrentElementInfo();

            assertNotNull(info);
            assertTrue(info.contains("# My HTTP Request"));
            assertTrue(info.contains("HTTPSampler.domain".replace(".", " ").replace("_", " ").substring(0, 1).toUpperCase()));
            assertTrue(info.contains("example.com"));
            assertTrue(info.contains("My Thread Group"));
            assertTrue(info.contains("JSON Extractor"));
        }
    }
}
