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

    @Test
    void testAppendTurnHeaderInsertsBoldSender() throws Exception {
        MessageProcessor processor = new MessageProcessor();
        StyledDocument doc = new DefaultStyledDocument();

        processor.appendTurnHeader(doc, "Feather Wand", Color.BLUE);

        String text = doc.getText(0, doc.getLength());
        assertEquals("Feather Wand\n", text);

        // Sender text must be bold with the given color
        javax.swing.text.Element elem = doc.getCharacterElement(0);
        assertTrue(StyleConstants.isBold(elem.getAttributes()));
        assertEquals(Color.BLUE, StyleConstants.getForeground(elem.getAttributes()));
    }

    @Test
    void testAppendTurnHeaderAddsSpacingBetweenTurns() throws Exception {
        MessageProcessor processor = new MessageProcessor();
        StyledDocument doc = new DefaultStyledDocument();
        doc.insertString(0, "previous turn\n", null);

        processor.appendTurnHeader(doc, "You", Color.GRAY);

        String text = doc.getText(0, doc.getLength());
        assertEquals("previous turn\n\nYou\n", text);
    }

    @Test
    void testAppendToolActivityUsesSecondaryMonospaceStyle() throws Exception {
        MessageProcessor processor = new MessageProcessor();
        StyledDocument doc = new DefaultStyledDocument();

        processor.appendToolActivity(doc, "tool add_element started");

        String text = doc.getText(0, doc.getLength());
        assertEquals("tool add_element started\n", text);

        javax.swing.text.Element elem = doc.getCharacterElement(0);
        assertEquals(Font.MONOSPACED, StyleConstants.getFontFamily(elem.getAttributes()));
    }

    @Test
    void testCommandCallbackDefaultToolActivityDelegates() {
        java.util.List<String> captured = new java.util.ArrayList<>();
        CommandCallback cb = new CommandCallback() {
            @Override public void setInputEnabled(boolean enabled) {}
            @Override public void clearMessageField() {}
            @Override public void appendUserMessage(String message) {}
            @Override public void appendLoadingIndicator() {}
            @Override public void removeLoadingIndicator() {}
            @Override public void processAiResponse(String response) {}
            @Override public void appendRedMessage(String message) {}
            @Override public void showStopButton() {}
            @Override public void hideStopButton() {}
            @Override public void appendStreamToken(String token) {}
            @Override public void onStreamComplete(String fullResponse) {}
            @Override public void onStreamError(String l, Exception e, String u) {}
            @Override public Runnable getAiStreamResponse(String m, java.util.function.Consumer<String> t, Runnable c, java.util.function.Consumer<Exception> e) { return () -> {}; }
            @Override public String getSelectedModel() { return null; }
            @Override public java.util.List<String> getConversationHistory() { return java.util.Collections.emptyList(); }
            @Override public void addToConversationHistory(String entry) {}
            @Override public String getAiResponse(String message) { return null; }
            @Override public org.qainsights.jmeter.ai.service.AiService resolveAiService(String selectedModel) { return null; }
            @Override public String getCurrentElementInfo() { return null; }
            @Override public void setLastCommandType(String type) {}
            @Override public void appendMessageToChat(String message) { captured.add(message); }
            @Override public void appendErrorMessageToChat(String context, Exception e) {}
            @Override public void onWorkerSuccess(String response) {}
            @Override public void onWorkerError(String l, Exception e, String u) {}
        };

        cb.appendToolActivity("tool line");
        assertEquals(java.util.List.of("tool line"), captured);
    }

    @Test
    void testBulletLinesRenderAsBullets() throws Exception {
        MessageProcessor processor = new MessageProcessor();
        StyledDocument doc = new DefaultStyledDocument();

        processor.appendMessage(doc, "- first item\n- second item", Color.BLACK, true);

        String text = doc.getText(0, doc.getLength());
        assertTrue(text.contains("• first item"));
        assertTrue(text.contains("• second item"));
        assertFalse(text.contains("- first item"));
    }

    @Test
    void testBulletLinesKeepInlineFormatting() throws Exception {
        MessageProcessor processor = new MessageProcessor();
        StyledDocument doc = new DefaultStyledDocument();

        processor.appendMessage(doc, "- use `@this` to inspect", Color.BLACK, true);

        String text = doc.getText(0, doc.getLength());
        assertTrue(text.contains("• use @this to inspect"));
        assertFalse(text.contains("`"));
    }

    @Test
    void testHorizontalRuleRendersDividerComponent() throws Exception {
        MessageProcessor processor = new MessageProcessor();
        StyledDocument doc = new DefaultStyledDocument();

        processor.appendMessage(doc, "above\n---\nbelow", Color.BLACK, true);

        String text = doc.getText(0, doc.getLength());
        assertTrue(text.contains("above"));
        assertTrue(text.contains("below"));
        assertFalse(text.contains("---"));

        // The rule is an embedded component, not text
        boolean foundComponent = false;
        for (int i = 0; i < doc.getLength(); i++) {
            javax.swing.text.Element elem = doc.getCharacterElement(i);
            if (StyleConstants.getComponent(elem.getAttributes()) != null) {
                foundComponent = true;
                break;
            }
        }
        assertTrue(foundComponent);
    }

    @Test
    void testLinksRenderAsStyledLabelWithoutUrl() throws Exception {
        MessageProcessor processor = new MessageProcessor();
        StyledDocument doc = new DefaultStyledDocument();

        processor.appendMessage(doc, "see [JMeter docs](https://jmeter.apache.org) here", Color.BLACK, true);

        String text = doc.getText(0, doc.getLength());
        assertTrue(text.contains("see JMeter docs here"));
        assertFalse(text.contains("jmeter.apache.org"));

        // The label must be underlined (link styling)
        int labelStart = text.indexOf("JMeter docs");
        javax.swing.text.Element elem = doc.getCharacterElement(labelStart);
        assertTrue(StyleConstants.isUnderline(elem.getAttributes()));
    }

    @Test
    void testNonLinkBracketsPassThroughLiterally() throws Exception {
        MessageProcessor processor = new MessageProcessor();
        StyledDocument doc = new DefaultStyledDocument();

        processor.appendMessage(doc, "array[0] and [bracket]", Color.BLACK, true);

        String text = doc.getText(0, doc.getLength());
        assertTrue(text.contains("array[0] and [bracket]"));
    }
}
