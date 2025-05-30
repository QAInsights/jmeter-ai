package org.nfe.jmeter.ai.gui;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.Timer; // Added missing import

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Processes and formats messages for display in the chat interface.
 * This class is responsible for handling markdown formatting and code blocks.
 */
public class MessageProcessor {
    private static final Logger log = LoggerFactory.getLogger(MessageProcessor.class);
    
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```([\\w-]*)\\s*([\\s\\S]*?)```");
    private final Map<String, String> codeSnippets = new HashMap<>();
    
    /**
     * Processes a markdown message and applies formatting to the document.
     * 
     * @param doc The document to apply formatting to
     * @param message The markdown message to process
     * @throws BadLocationException If there is an error with the document location
     */
    public void processMarkdownMessage(StyledDocument doc, String message) throws BadLocationException {
        log.info("Processing markdown message");
        
        // Extract code blocks first
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(message);
        StringBuffer sb = new StringBuffer();
        int codeBlockCount = 0;
        
        while (matcher.find()) {
            codeBlockCount++;
            String language = matcher.group(1).trim();
            String code = matcher.group(2);
            
            // Store the code snippet for potential reuse
            String snippetKey = "snippet_" + codeBlockCount;
            codeSnippets.put(snippetKey, code);
            
            // Replace the code block with a placeholder
            // Add extra newlines before and after for better spacing
            String placeholder = "\n[CODE_BLOCK:" + snippetKey + ":" + language + "]\n";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(placeholder));
        }
        matcher.appendTail(sb);
        
        // Process the text without code blocks
        String processedText = sb.toString();
        processBasicMarkdown(doc, processedText);
    }
    
    /**
     * Processes basic markdown formatting and code block placeholders.
     * 
     * @param doc The document to apply formatting to
     * @param text The text to process
     * @throws BadLocationException If there is an error with the document location
     */
    private void processBasicMarkdown(StyledDocument doc, String text) throws BadLocationException {
        // Split the text by lines to process each line separately
        String[] lines = text.split("\n");
        
        // Define styles
        SimpleAttributeSet normal = new SimpleAttributeSet();
        StyleConstants.setFontFamily(normal, "SansSerif");
        
        SimpleAttributeSet bold = new SimpleAttributeSet(normal);
        StyleConstants.setBold(bold, true);
        
        SimpleAttributeSet italic = new SimpleAttributeSet(normal);
        StyleConstants.setItalic(italic, true);
        
        SimpleAttributeSet heading1 = new SimpleAttributeSet(bold);
        StyleConstants.setFontSize(heading1, StyleConstants.getFontSize(normal) + 6);
        
        SimpleAttributeSet heading2 = new SimpleAttributeSet(bold);
        StyleConstants.setFontSize(heading2, StyleConstants.getFontSize(normal) + 4);
        
        SimpleAttributeSet heading3 = new SimpleAttributeSet(bold);
        StyleConstants.setFontSize(heading3, StyleConstants.getFontSize(normal) + 2);
        
        SimpleAttributeSet codeStyle = new SimpleAttributeSet();
        StyleConstants.setFontFamily(codeStyle, "Monospaced");
        StyleConstants.setBackground(codeStyle, new Color(240, 240, 240));
        
        // Process each line
        for (String line : lines) {
            // Check for code block placeholder
            if (line.trim().startsWith("[CODE_BLOCK:") && line.trim().endsWith("]")) {
                // Extract snippet key and language
                String[] parts = line.trim().substring(12, line.trim().length() - 1).split(":");
                String snippetKey = parts[0];
                String language = parts.length > 1 ? parts[1] : "";
                
                // Get the code snippet
                String code = codeSnippets.get(snippetKey);
                
                if (code != null) {
                    // Add extra spacing before the code block
                    doc.insertString(doc.getLength(), "\n", normal);
                    
                    // Create a direct text-based code block with styling
                    renderCodeBlock(doc, code, language, codeStyle);
                    
                    // Add extra spacing after the code block
                    doc.insertString(doc.getLength(), "\n", normal);
                }
                continue;
            }
            
            // Check for headings
            if (line.startsWith("# ")) {
                doc.insertString(doc.getLength(), line.substring(2) + "\n", heading1);
            } else if (line.startsWith("## ")) {
                doc.insertString(doc.getLength(), line.substring(3) + "\n", heading2);
            } else if (line.startsWith("### ")) {
                doc.insertString(doc.getLength(), line.substring(4) + "\n", heading3);
            } else {
                // Process inline formatting
                StringBuilder currentText = new StringBuilder();
                AttributeSet currentStyle = normal;
                
                for (int i = 0; i < line.length(); i++) {
                    char c = line.charAt(i);
                    
                    // Check for bold (**text**)
                    if (c == '*' && i + 1 < line.length() && line.charAt(i + 1) == '*') {
                        // Insert accumulated text with current style
                        doc.insertString(doc.getLength(), currentText.toString(), currentStyle);
                        currentText.setLength(0);
                        
                        // Toggle bold style
                        if (currentStyle == bold) {
                            currentStyle = normal;
                        } else {
                            currentStyle = bold;
                        }
                        
                        // Skip the second asterisk
                        i++;
                    }
                    // Check for italic (*text*)
                    else if (c == '*') {
                        // Insert accumulated text with current style
                        doc.insertString(doc.getLength(), currentText.toString(), currentStyle);
                        currentText.setLength(0);
                        
                        // Toggle italic style
                        if (currentStyle == italic) {
                            currentStyle = normal;
                        } else {
                            currentStyle = italic;
                        }
                    }
                    // Check for inline code (`text`)
                    else if (c == '`') {
                        // Insert accumulated text with current style
                        doc.insertString(doc.getLength(), currentText.toString(), currentStyle);
                        currentText.setLength(0);
                        
                        // Toggle code style
                        if (currentStyle == codeStyle) {
                            currentStyle = normal;
                        } else {
                            currentStyle = codeStyle;
                        }
                    }
                    // Regular character
                    else {
                        currentText.append(c);
                    }
                }
                
                // Insert any remaining text
                doc.insertString(doc.getLength(), currentText.toString() + "\n", currentStyle);
            }
        }
    }
    
    /**
     * Renders a code block directly as text with styling.
     * 
     * @param doc The document to render the code block in
     * @param code The code to render
     * @param language The language of the code
     * @param codeStyle The style to apply to the code
     * @throws BadLocationException If there is an error with the document location
     */
    private void renderCodeBlock(StyledDocument doc, String code, String language, SimpleAttributeSet codeStyle) throws BadLocationException {
        // Create a panel for the code block with a border layout
        JPanel codePanel = new JPanel(new BorderLayout());
        codePanel.setBackground(new Color(245, 245, 245));
        codePanel.setBorder(BorderFactory.createEmptyBorder(10, 0, 10, 0));
        
        // Create a header panel for language and copy button
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(new Color(245, 245, 245));
        headerPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        
        // Add language label if present
        if (!language.isEmpty()) {
            JLabel languageLabel = new JLabel(language);
            languageLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
            headerPanel.add(languageLabel, BorderLayout.WEST);
        }
        
        // Create a copy button
        JButton copyButton = new JButton("Copy");
        copyButton.setToolTipText("Copy code to clipboard");
        copyButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // Copy code to clipboard
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                    new java.awt.datatransfer.StringSelection(code), null);
                
                // Provide visual feedback
                copyButton.setText("Copied!");
                Timer timer = new Timer(1500, event -> copyButton.setText("Copy"));
                timer.setRepeats(false);
                timer.start();
            }
        });
        
        // Add the copy button to the header
        headerPanel.add(copyButton, BorderLayout.EAST);
        
        // Add the header panel to the code panel
        codePanel.add(headerPanel, BorderLayout.NORTH);
        
        // Create a text area for the code
        JTextArea codeArea = new JTextArea(code.trim()); // Trim to remove extra lines
        codeArea.setFont(UIManager.getFont("TextField.font")); // Use default font
        codeArea.setEditable(false);
        codeArea.setBackground(new Color(245, 245, 245));
        codeArea.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        
        // Add the code area to the panel
        codePanel.add(codeArea, BorderLayout.CENTER);
        
        // Insert the code panel into the document
        SimpleAttributeSet panelStyle = new SimpleAttributeSet();
        StyleConstants.setComponent(panelStyle, codePanel);
        doc.insertString(doc.getLength(), " ", panelStyle);
        
        // Add extra spacing after the code block
        doc.insertString(doc.getLength(), "\n", codeStyle);
    }
    
    /**
     * Gets the stored code snippets.
     * 
     * @return The map of code snippets
     */
    public Map<String, String> getCodeSnippets() {
        return codeSnippets;
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
    public void appendMessage(StyledDocument doc, String message, Color color, boolean parseMarkdown) throws BadLocationException {
        // Create a style for the message
        SimpleAttributeSet messageStyle = new SimpleAttributeSet();        
        StyleConstants.setForeground(messageStyle, color);
        
        if (parseMarkdown) {
            // Process markdown formatting
            processMarkdownMessage(doc, message);
        } else {
            // Check if the message starts with "You: " to make it bold
            if (message.startsWith("You: ")) {
                // Create a bold style for "You:"
                SimpleAttributeSet boldStyle = new SimpleAttributeSet(messageStyle);
                StyleConstants.setBold(boldStyle, true);
                
                // Insert "You:" with bold style
                doc.insertString(doc.getLength(), "You:", boldStyle);
                
                // Insert the rest of the message with regular style
                doc.insertString(doc.getLength(), message.substring(4) + "\n", messageStyle);
            } else {
                // Add the message without formatting
                doc.insertString(doc.getLength(), message + "\n", messageStyle);
            }
        }
        
        // Scroll to the bottom of the document
        // This is handled by the caller
    }
}
