package org.qainsights.jmeter.ai.gui;

import java.awt.*;
import java.util.Map;
import javax.swing.text.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Processes and formats messages for display in the chat interface.
 * This class is responsible for handling markdown formatting and code blocks.
 */
public class MessageProcessor {

    private static final Logger log = LoggerFactory.getLogger(
        MessageProcessor.class
    );

    private final MarkdownRenderer markdownRenderer = new MarkdownRenderer();

    /**
     * Processes a markdown message and applies formatting to the document.
     *
     * @param doc The document to apply formatting to
     * @param message The markdown message to process
     * @throws BadLocationException If there is an error with the document location
     */
    public void processMarkdownMessage(StyledDocument doc, String message)
        throws BadLocationException {
        log.info("Processing markdown message");
        markdownRenderer.process(doc, message);
    }

    /**
     * Gets the stored code snippets.
     *
     * @return The map of code snippets
     */
    public Map<String, String> getCodeSnippets() {
        return markdownRenderer.getCodeSnippets();
    }

    /**
     * Adds a message to the document with the specified color and formatting.
     *
     * @param doc The document to add the message to
     * @param message The message to add
     * @param color The color of the message
     * @param parseMarkdown Whether to parse markdown in the message
     * @throws BadLocationException If there is an error with the document location
     */
    public void appendMessage(
        StyledDocument doc,
        String message,
        Color color,
        boolean parseMarkdown
    ) throws BadLocationException {
        // Create a style for the message
        SimpleAttributeSet messageStyle = new SimpleAttributeSet();
        if (color != null) {
            StyleConstants.setForeground(messageStyle, color);
        }

        if (parseMarkdown) {
            // Process markdown formatting
            processMarkdownMessage(doc, message);
        } else {
            // Check if the message starts with "You: " to make it bold
            if (message.startsWith("You: ")) {
                // Create a bold style for "You:"
                SimpleAttributeSet boldStyle = new SimpleAttributeSet(
                    messageStyle
                );
                StyleConstants.setBold(boldStyle, true);

                // Insert "You:" with bold style
                doc.insertString(doc.getLength(), "You:", boldStyle);

                // Insert the rest of the message with regular style
                doc.insertString(
                    doc.getLength(),
                    message.substring(4) + "\n",
                    messageStyle
                );
            } else {
                // Add the message without formatting
                doc.insertString(doc.getLength(), message + "\n", messageStyle);
            }
        }

        // Scroll to the bottom of the document
        // This is handled by the caller
    }
}
