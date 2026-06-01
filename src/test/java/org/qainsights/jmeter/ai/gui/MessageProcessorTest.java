package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MessageProcessorTest {

    @Test
    void testAppendMessage_NoMarkdown() throws Exception {
        MessageProcessor processor = new MessageProcessor();
        StyledDocument doc = new DefaultStyledDocument();

        processor.appendMessage(doc, "Hello World", Color.BLACK, false);

        String text = doc.getText(0, doc.getLength());
        assertEquals("Hello World\n", text);
    }

    @Test
    void testAppendMessage_YouPrefix() throws Exception {
        MessageProcessor processor = new MessageProcessor();
        StyledDocument doc = new DefaultStyledDocument();

        processor.appendMessage(doc, "You: Hello World", Color.BLUE, false);

        String text = doc.getText(0, doc.getLength());
        assertEquals("You: Hello World\n", text);
    }

    @Test
    void testBasicMarkdownFormatting() throws Exception {
        MessageProcessor processor = new MessageProcessor();
        StyledDocument doc = new DefaultStyledDocument();

        String message = "# Heading 1\n## Heading 2\n### Heading 3\n**bold** text and *italic* text with `inline code`.";
        processor.appendMessage(doc, message, Color.BLACK, true);

        String text = doc.getText(0, doc.getLength());
        assertTrue(text.contains("Heading 1"));
        assertTrue(text.contains("Heading 2"));
        assertTrue(text.contains("Heading 3"));
        assertTrue(text.contains("bold"));
        assertTrue(text.contains("italic"));
        assertTrue(text.contains("inline code"));
    }

    @Test
    void testCodeBlockExtractionAndRendering() throws Exception {
        MessageProcessor processor = new MessageProcessor();
        StyledDocument doc = new DefaultStyledDocument();

        String message = "Here is some code:\n```java\nSystem.out.println(\"Hello\");\n```\nEnjoy!";
        processor.appendMessage(doc, message, Color.BLACK, true);

        String text = doc.getText(0, doc.getLength());
        assertTrue(text.contains("Here is some code:"));
        assertTrue(text.contains("Enjoy!"));

        // Check stored snippets
        Map<String, String> snippets = processor.getCodeSnippets();
        assertFalse(snippets.isEmpty());
        assertTrue(snippets.containsKey("snippet_1"));
        assertEquals("System.out.println(\"Hello\");\n", snippets.get("snippet_1"));
    }

    @Test
    void testThemeBackgroundLuminance() {
        // Test light theme default
        Color lightColor = UIManager.getColor("Panel.background");
        try {
            UIManager.put("Panel.background", Color.WHITE);
            MessageProcessor processor = new MessageProcessor();
            StyledDocument doc = new DefaultStyledDocument();
            
            // Trigger background calculation
            assertDoesNotThrow(() -> processor.appendMessage(doc, "```java\nint x = 5;\n```", Color.BLACK, true));
        } finally {
            UIManager.put("Panel.background", lightColor);
        }

        // Test dark theme
        try {
            UIManager.put("Panel.background", Color.BLACK);
            MessageProcessor processor = new MessageProcessor();
            StyledDocument doc = new DefaultStyledDocument();
            
            // Trigger background calculation
            assertDoesNotThrow(() -> processor.appendMessage(doc, "```java\nint x = 5;\n```", Color.BLACK, true));
        } finally {
            UIManager.put("Panel.background", lightColor);
        }
    }

    @Test
    void testCopyButtonAction() throws Exception {
        MessageProcessor processor = new MessageProcessor();
        StyledDocument doc = new DefaultStyledDocument();

        // Render code block
        processor.appendMessage(doc, "```java\nint x = 100;\n```", Color.BLACK, true);

        // Find the copy button inside the component
        JPanel codePanel = null;
        for (int i = 0; i < doc.getLength(); i++) {
            javax.swing.text.Element elem = doc.getCharacterElement(i);
            Object comp = StyleConstants.getComponent(elem.getAttributes());
            if (comp instanceof JPanel) {
                codePanel = (JPanel) comp;
                break;
            }
        }

        assertNotNull(codePanel);
        
        // Find the copy button
        JButton copyButton = null;
        for (Component component : codePanel.getComponents()) {
            if (component instanceof JPanel) { // Header panel
                for (Component subComp : ((JPanel) component).getComponents()) {
                    if (subComp instanceof JButton && "Copy".equals(((JButton) subComp).getText())) {
                        copyButton = (JButton) subComp;
                        break;
                    }
                }
            }
        }

        assertNotNull(copyButton);

        // Click the copy button
        JButton finalCopyButton = copyButton;
        assertDoesNotThrow(() -> {
            for (ActionListener al : finalCopyButton.getActionListeners()) {
                al.actionPerformed(new ActionEvent(finalCopyButton, ActionEvent.ACTION_PERFORMED, "copy"));
            }
        });

        assertEquals("Copied!", copyButton.getText());
    }
}
