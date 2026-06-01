package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeListener;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.qainsights.jmeter.ai.service.AiService;

import java.awt.*;
import javax.swing.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CodeCommandHandlerTest {

    @Mock
    private AiService aiService;

    @Mock
    private GuiPackage guiPackage;

    @Mock
    private JMeterTreeListener treeListener;

    @Mock
    private JMeterTreeNode currentNode;

    @Test
    void testProcessCodeCommand_NoJSR223Element() {
        CodeCommandHandler handler = new CodeCommandHandler(aiService);

        try (MockedStatic<GuiPackage> guiPackageMockedStatic = mockStatic(GuiPackage.class)) {
            guiPackageMockedStatic.when(GuiPackage::getInstance).thenReturn(guiPackage);
            when(guiPackage.getTreeListener()).thenReturn(treeListener);
            when(treeListener.getCurrentNode()).thenReturn(null);

            String result = handler.processCodeCommand("@code improve");
            assertEquals("No JSR223 element is currently selected. Please select a JSR223 element first.", result);
        }
    }

    @Test
    void testFindRSyntaxTextArea_Success() {
        JPanel parent = new JPanel();
        RSyntaxTextArea textArea = new RSyntaxTextArea();
        parent.add(textArea);

        RSyntaxTextArea found = CodeCommandHandler.findRSyntaxTextArea(parent);
        assertNotNull(found);
        assertEquals(textArea, found);
    }

    @Test
    void testFindRSyntaxTextArea_WithName() {
        JPanel parent = new JPanel();
        RSyntaxTextArea textArea = new RSyntaxTextArea();
        textArea.setName("scriptArea");
        parent.add(textArea);

        RSyntaxTextArea found = CodeCommandHandler.findRSyntaxTextArea(parent);
        assertNotNull(found);
        assertEquals(textArea, found);
    }

    @Test
    void testFindRSyntaxTextArea_NotFound() {
        JPanel parent = new JPanel();
        JLabel label = new JLabel("Not a text area");
        parent.add(label);

        RSyntaxTextArea found = CodeCommandHandler.findRSyntaxTextArea(parent);
        assertNull(found);
    }

    @Test
    void testStoreSelectedText_NoEditor() {
        try (MockedStatic<GuiPackage> guiPackageMockedStatic = mockStatic(GuiPackage.class)) {
            guiPackageMockedStatic.when(GuiPackage::getInstance).thenReturn(null);
            
            // Should not throw any exceptions
            assertDoesNotThrow(CodeCommandHandler::storeSelectedText);
        }
    }
}
