package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeListener;
import org.apache.jmeter.gui.tree.JMeterTreeNode;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TreeNavigationButtonsTest {

    @Mock
    private GuiPackage guiPackage;

    @Mock
    private JMeterTreeListener treeListener;

    @Mock
    private JTree jTree;

    @Mock
    private JMeterTreeNode currentNode;

    @Mock
    private JMeterTreeNode parentNode;

    @Test
    void testConstructorAndGetters() {
        TreeNavigationButtons buttons = new TreeNavigationButtons();
        assertNotNull(buttons.getUpButton());
        assertNotNull(buttons.getDownButton());
    }

    @Test
    void testSetUpButtonActionListener_GuiPackageNull() {
        TreeNavigationButtons buttons = new TreeNavigationButtons();
        try (MockedStatic<GuiPackage> guiPackageMockedStatic = mockStatic(GuiPackage.class)) {
            guiPackageMockedStatic.when(GuiPackage::getInstance).thenReturn(null);

            // This should not throw any exception
            assertDoesNotThrow(buttons::setUpButtonActionListener);
            assertFalse(buttons.getUpButton().isEnabled());
        }
    }

    @Test
    void testSetUpButtonActionListener_WithGuiPackage() {
        TreeNavigationButtons buttons = new TreeNavigationButtons();
        try (MockedStatic<GuiPackage> guiPackageMockedStatic = mockStatic(GuiPackage.class)) {
            guiPackageMockedStatic.when(GuiPackage::getInstance).thenReturn(guiPackage);
            when(guiPackage.getTreeListener()).thenReturn(treeListener);
            when(treeListener.getJTree()).thenReturn(jTree);
            when(treeListener.getCurrentNode()).thenReturn(currentNode);
            when(currentNode.getParent()).thenReturn(parentNode);
            when(currentNode.getName()).thenReturn("Child Node");
            when(currentNode.getStaticLabel()).thenReturn("HTTP Request");

            assertDoesNotThrow(buttons::setUpButtonActionListener);
            assertTrue(buttons.getUpButton().isEnabled());
        }
    }

    @Test
    void testSetDownButtonActionListener_GuiPackageNull() {
        TreeNavigationButtons buttons = new TreeNavigationButtons();
        assertDoesNotThrow(buttons::setDownButtonActionListener);

        // Click the down button and ensure it doesn't crash when GuiPackage is null
        JButton downButton = buttons.getDownButton();
        try (MockedStatic<GuiPackage> guiPackageMockedStatic = mockStatic(GuiPackage.class)) {
            guiPackageMockedStatic.when(GuiPackage::getInstance).thenReturn(null);

            for (ActionListener al : downButton.getActionListeners()) {
                assertDoesNotThrow(() -> al.actionPerformed(new ActionEvent(downButton, ActionEvent.ACTION_PERFORMED, "down")));
            }
        }
    }
}
