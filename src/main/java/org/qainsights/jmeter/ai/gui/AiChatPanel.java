package org.qainsights.jmeter.ai.gui;

import org.qainsights.jmeter.ai.service.ClaudeService;
import org.qainsights.jmeter.ai.utils.JMeterElementRequestHandler;
import org.qainsights.jmeter.ai.utils.Models;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.text.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.anthropic.models.ModelInfo;

public class AiChatPanel extends JPanel {
    private JTextPane chatArea;
    private JTextArea messageField;
    private JButton sendButton;
    private List<String> conversationHistory;
    private ClaudeService claudeService;
    private JComboBox<ModelInfo> modelSelector;
    private static final Logger log = LoggerFactory.getLogger(AiChatPanel.class);

    // Pattern to match code blocks in markdown (```language code ```)
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```([\\w-]*)\\s*([\\s\\S]*?)```");

    // Pattern to match JMeter element suggestions in AI responses
    private static final Pattern ELEMENT_SUGGESTION_PATTERN = Pattern.compile(
        "(?i)(?:add|create|use|include)\\s+(?:a|an)?\\s+([a-z\\s-]+?)(?:\\s+(?:called|named|with name|with the name)?\\s+[\"']?([^\"']+?)[\"']?)?(?:\\s*$|\\s+(?:to|in|for)\\b)"
    );

    // Map to store code snippets for copying
    private Map<String, String> codeSnippets = new HashMap<>();

    public AiChatPanel() {
        claudeService = new ClaudeService();
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(500, 600)); // Increased width for better readability
        setMinimumSize(new Dimension(350, 400));
        
        // Add a margin around the entire panel
        setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));

        // Initialize model selector with loading state
        modelSelector = new JComboBox<>();
        modelSelector.addItem(null); // Add empty item while loading
        modelSelector.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected,
                    boolean cellHasFocus) {
                if (value == null) {
                    return super.getListCellRendererComponent(list, "Loading models...", index, isSelected,
                            cellHasFocus);
                }
                ModelInfo model = (ModelInfo) value;
                return super.getListCellRendererComponent(list, model.id(), index, isSelected, cellHasFocus);
            }
        });
        
        // Load models in background
        new SwingWorker<List<ModelInfo>, Void>() {
            @Override
            protected List<ModelInfo> doInBackground() {
                return Models.getModels(claudeService.getClient()).data();
            }

            @Override
            protected void done() {
                try {
                    List<ModelInfo> models = get();
                    modelSelector.removeAllItems();
                    
                    // Get the default model ID
                    String defaultModelId = claudeService.getCurrentModel();
                    log.info("Default model ID: {}", defaultModelId);
                    
                    ModelInfo defaultModelInfo = null;
                    
                    for (ModelInfo model : models) {
                        modelSelector.addItem(model);
                        if (model.id().equals(defaultModelId)) {
                            defaultModelInfo = model;
                        }
                    }
                    
                    // Select the default model if found
                    if (defaultModelInfo != null) {
                        modelSelector.setSelectedItem(defaultModelInfo);
                        log.info("Selected default model: {}", defaultModelInfo.id());
                    } else if (modelSelector.getItemCount() > 0) {
                        // If default model not found, select the first one
                        modelSelector.setSelectedIndex(0);
                        ModelInfo selectedModel = (ModelInfo) modelSelector.getSelectedItem();
                        claudeService.setModel(selectedModel.id());
                        log.info("Default model not found, selected first available: {}", selectedModel.id());
                    }
                } catch (Exception e) {
                    log.error("Failed to load models", e);
                }
            }
        }.execute();

        // Add a listener to log model changes
        modelSelector.addActionListener(e -> {
            ModelInfo selectedModel = (ModelInfo) modelSelector.getSelectedItem();
            if (selectedModel != null) {
                log.info("Model selected from dropdown: {}", selectedModel.id());
                // Immediately set the model in the service
                claudeService.setModel(selectedModel.id());
            }
        });

        // Create a panel for the chat area with header
        JPanel chatPanel = new JPanel(new BorderLayout());
        chatPanel.setBorder(BorderFactory.createMatteBorder(0, 1, 1, 1, Color.LIGHT_GRAY));
        
        // Create a header panel for the title and new chat button
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 1, 0, Color.LIGHT_GRAY),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)
        ));
        headerPanel.setBackground(new Color(240, 240, 240));
        
        // Add a title to the left side of the header panel
        JLabel titleLabel = new JLabel("Feather Wand - JMeter Agent");
        titleLabel.setFont(new Font(titleLabel.getFont().getName(), Font.BOLD, 14));
        headerPanel.add(titleLabel, BorderLayout.WEST);
        
        // Create the "New Chat" button with a plus icon
        JButton newChatButton = new JButton("+");
        newChatButton.setToolTipText("Start a new conversation");
        newChatButton.setFont(new Font(newChatButton.getFont().getName(), Font.BOLD, 16));
        newChatButton.setFocusPainted(false);
        newChatButton.setMargin(new Insets(0, 8, 0, 8));
        newChatButton.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(200, 200, 200), 1, true),
                BorderFactory.createEmptyBorder(2, 8, 2, 8)
        ));
        
        // Add action listener to reset the conversation
        newChatButton.addActionListener(e -> startNewConversation());
        
        // Add the button to the right side of the header panel
        headerPanel.add(newChatButton, BorderLayout.EAST);
        
        // Add the header panel to the top of the chat panel
        chatPanel.add(headerPanel, BorderLayout.NORTH);

        // Initialize chat area
        chatArea = new JTextPane();
        chatArea.setEditable(false);
        // Use the system default font with larger size
        Font defaultFont = UIManager.getFont("TextField.font");
        Font largerFont = new Font(defaultFont.getFamily(), defaultFont.getStyle(), defaultFont.getSize() + 2);
        chatArea.setFont(largerFont);
        
        // Set default paragraph attributes for left alignment
        StyledDocument doc = chatArea.getStyledDocument();
        SimpleAttributeSet leftAlign = new SimpleAttributeSet();
        StyleConstants.setAlignment(leftAlign, StyleConstants.ALIGN_LEFT);
        doc.setParagraphAttributes(0, doc.getLength(), leftAlign, false);
        
        JScrollPane scrollPane = new JScrollPane(chatArea);
        scrollPane.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        chatPanel.add(scrollPane, BorderLayout.CENTER);
        
        // Add the chat panel to the center of the main panel
        add(chatPanel, BorderLayout.CENTER);

        // Create the bottom panel with model selector and input controls
        JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        
        // Add model selector to the bottom panel
        FlowLayout flowLayout = new FlowLayout(FlowLayout.LEFT);
        JPanel modelPanel = new JPanel(flowLayout);
        JLabel modelLabel = new JLabel("Model: ");
        modelPanel.add(modelLabel);
        modelPanel.add(modelSelector);
        bottomPanel.add(modelPanel, BorderLayout.NORTH);
        
        // Create input panel with text field and send button
        JPanel inputPanel = new JPanel(new BorderLayout(5, 0));
        
        // Create a JTextArea instead of JTextField for top-aligned text
        messageField = new JTextArea(3, 20); // 3 rows tall
        messageField.setLineWrap(true);
        messageField.setWrapStyleWord(true);
        messageField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.LIGHT_GRAY),
                BorderFactory.createEmptyBorder(5, 5, 5, 5)
        ));
        
        // Add key listener to handle Enter key for sending messages
        messageField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && !e.isShiftDown()) {
                    e.consume(); // Prevent newline
                    sendMessage();
                }
            }
        });
        
        // Create a scroll pane for the message field (in case of long text)
        JScrollPane messageScrollPane = new JScrollPane(messageField);
        messageScrollPane.setBorder(BorderFactory.createEmptyBorder());
        
        // Style the send button with better contrast
        sendButton = new JButton("Send");
        sendButton.setBackground(new Color(240, 240, 240)); // Light gray background
        sendButton.setForeground(new Color(0, 0, 0)); // Black text
        sendButton.setFocusPainted(false);
        sendButton.setFont(new Font(sendButton.getFont().getName(), Font.BOLD, 12));
        sendButton.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(200, 200, 200), 1, true),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)
        ));
        
        inputPanel.add(messageScrollPane, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);
        bottomPanel.add(inputPanel, BorderLayout.CENTER);
        
        add(bottomPanel, BorderLayout.SOUTH);

        sendButton.addActionListener(e -> sendMessage());

        conversationHistory = new ArrayList<>();
        
        // Display welcome message
        displayWelcomeMessage();
    }
    
    /**
     * Starts a new conversation by clearing the chat area and conversation history
     */
    private void startNewConversation() {
        // Clear the chat area
        chatArea.setText("");
        
        // Clear the conversation history
        conversationHistory.clear();
        
        // Reset the input field
        messageField.setText("");
        
        // Reset the systemPromptInitialized flag in ClaudeService
        claudeService.resetSystemPromptInitialization();
        
        // Display welcome message
        displayWelcomeMessage();
        
        // Log the action
        log.info("Started new conversation");
    }
    
    /**
     * Displays a welcome message in the chat area
     */
    private void displayWelcomeMessage() {
        String welcomeMessage = "Welcome to Feather Wand - JMeter Agent!\n\n" +
                "I can help you create and modify JMeter test plans. You can ask me to:\n" +
                "- Add JMeter elements to your test plan\n" +
                "- Optimize your test plan for better performance\n" +
                "- Get help with JMeter best practices\n\n" +
                "How can I assist you today?\n\n";
        
        // Create a style for the welcome message
        SimpleAttributeSet welcomeStyle = new SimpleAttributeSet();
        StyleConstants.setForeground(welcomeStyle, new Color(0, 51, 102)); // Dark blue for better contrast
        StyleConstants.setAlignment(welcomeStyle, StyleConstants.ALIGN_LEFT);
        
        try {
            StyledDocument doc = chatArea.getStyledDocument();
            doc.insertString(doc.getLength(), welcomeMessage, welcomeStyle);
            
            // Apply left alignment
            doc.setParagraphAttributes(0, doc.getLength(), welcomeStyle, false);
        } catch (BadLocationException e) {
            log.error("Error displaying welcome message", e);
        }
    }

    /**
     * Sends the message from the input field to the chat
     */
    private void sendMessage() {
        String message = messageField.getText().trim();
        if (message.isEmpty()) {
            return;
        }
        
        // Clear the input field
        messageField.setText("");
        
        // Add the user message to the chat
        appendToChat("You: " + message, Color.BLACK, false);
        
        // Add the message to the conversation history
        conversationHistory.add(message);
        
        // Process the message
        sendUserMessage(message);
        
        // Request focus back to the input field
        SwingUtilities.invokeLater(() -> {
            messageField.requestFocusInWindow();
        });
    }

    private void appendToChat(String message, Color color, boolean parseMarkdown) {
        if (message == null || message.isEmpty()) {
            return;
        }
        
        try {
            StyledDocument doc = chatArea.getStyledDocument();
            
            // Create a style with the specified color
            SimpleAttributeSet style = new SimpleAttributeSet();
            StyleConstants.setForeground(style, color);
            
            // For user messages, make them bold and ensure they're black
            if (message.startsWith("You:")) {
                StyleConstants.setBold(style, true);
                StyleConstants.setForeground(style, Color.BLACK);
            }
            
            // Get the current position
            int length = doc.getLength();
            
            if (parseMarkdown) {
                // Process markdown in the message
                processMarkdownMessage(doc, message);
            } else {
                // Add the message with the specified style
                doc.insertString(length, message + "\n", style);
            }
            
            // Ensure left alignment for all text
            SimpleAttributeSet leftAlign = new SimpleAttributeSet();
            StyleConstants.setAlignment(leftAlign, StyleConstants.ALIGN_LEFT);
            doc.setParagraphAttributes(0, doc.getLength(), leftAlign, false);
            
            // Scroll to the bottom
            chatArea.setCaretPosition(doc.getLength());
        } catch (BadLocationException e) {
            log.error("Error appending to chat", e);
        }
    }

    private void processMarkdownMessage(StyledDocument doc, String message) throws BadLocationException {
        // Extract code blocks
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(message);
        StringBuffer sb = new StringBuffer();
        
        // Map to store code blocks
        Map<String, String> codeBlocks = new HashMap<>();
        int codeBlockCounter = 0;
        
        // Replace code blocks with placeholders
        while (matcher.find()) {
            String language = matcher.group(1).trim();
            String code = matcher.group(2);
            
            // Generate a placeholder
            String placeholder = "CODE_BLOCK_" + codeBlockCounter++;
            
            // Store the code block
            codeBlocks.put(placeholder, code);
            
            // Replace the code block with the placeholder
            matcher.appendReplacement(sb, placeholder);
        }
        matcher.appendTail(sb);
        
        // Process the text without code blocks
        String textWithoutCode = sb.toString();
        
        // First, process basic markdown for the text without code blocks
        processBasicMarkdown(doc, textWithoutCode);
        
        // Now add the code blocks
        for (int i = 0; i < codeBlockCounter; i++) {
            String placeholder = "CODE_BLOCK_" + i;
            String code = codeBlocks.get(placeholder);
            
            if (code != null) {
                // Find the placeholder in the document
                String docText = doc.getText(0, doc.getLength());
                int placeholderPos = docText.indexOf(placeholder);
                
                if (placeholderPos >= 0) {
                    // Remove the placeholder
                    doc.remove(placeholderPos, placeholder.length());
                    
                    // Create a panel for the code block
                    JPanel codePanel = new JPanel(new BorderLayout());
                    codePanel.setBorder(BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(new Color(220, 220, 220), 1, true),
                            BorderFactory.createEmptyBorder(5, 5, 5, 5)
                    ));
                    codePanel.setBackground(new Color(245, 245, 245));
                    
                    // Create a header panel for the copy button
                    JPanel headerPanel = new JPanel(new BorderLayout());
                    headerPanel.setBackground(new Color(245, 245, 245));
                    
                    // Create a copy button
                    JButton copyButton = new JButton("Copy");
                    copyButton.setFocusPainted(false);
                    copyButton.setMargin(new Insets(2, 8, 2, 8));
                    copyButton.addActionListener(e -> {
                        StringSelection selection = new StringSelection(code);
                        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                        clipboard.setContents(selection, null);
                        copyButton.setText("Copied!");
                        
                        // Reset the button text after a delay
                        Timer timer = new Timer(1500, event -> copyButton.setText("Copy"));
                        timer.setRepeats(false);
                        timer.start();
                    });
                    
                    // Add the copy button to the header panel
                    JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
                    buttonPanel.setBackground(new Color(245, 245, 245));
                    buttonPanel.add(copyButton);
                    headerPanel.add(buttonPanel, BorderLayout.EAST);
                    
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
                    
                    // Insert a placeholder for the component
                    SimpleAttributeSet componentStyle = new SimpleAttributeSet();
                    StyleConstants.setComponent(componentStyle, codePanel);
                    
                    // Apply the style to the placeholder
                    doc.insertString(placeholderPos, " ", componentStyle);
                }
            }
        }
    }

    private void processBasicMarkdown(StyledDocument doc, String text) throws BadLocationException {
        // Define the AI response color (dark blue)
        Color aiResponseColor = new Color(0, 51, 102);
        
        // Create a style for regular text
        SimpleAttributeSet regularStyle = new SimpleAttributeSet();
        StyleConstants.setForeground(regularStyle, aiResponseColor);
        
        // Create a style for bold text
        SimpleAttributeSet boldStyle = new SimpleAttributeSet();
        StyleConstants.setForeground(boldStyle, aiResponseColor);
        StyleConstants.setBold(boldStyle, true);
        
        // Create a style for italic text
        SimpleAttributeSet italicStyle = new SimpleAttributeSet();
        StyleConstants.setForeground(italicStyle, aiResponseColor);
        StyleConstants.setItalic(italicStyle, true);
        
        // Create a style for code text
        SimpleAttributeSet codeStyle = new SimpleAttributeSet();
        StyleConstants.setForeground(codeStyle, new Color(0, 0, 0)); // Black for code
        StyleConstants.setBackground(codeStyle, new Color(245, 245, 245)); // Light gray background
        StyleConstants.setFontFamily(codeStyle, UIManager.getFont("TextField.font").getFamily()); // Use default font
        
        // Split the text into lines
        String[] lines = text.split("\n");
        
        // Process each line
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            
            // Process the line
            int pos = 0;
            while (pos < line.length()) {
                // Check for bold text
                if (pos + 1 < line.length() && line.substring(pos, pos + 2).equals("**")) {
                    int endPos = line.indexOf("**", pos + 2);
                    if (endPos != -1) {
                        // Add the bold text
                        String boldText = line.substring(pos + 2, endPos);
                        doc.insertString(doc.getLength(), boldText, boldStyle);
                        pos = endPos + 2;
                    } else {
                        // No end marker, add as regular text
                        doc.insertString(doc.getLength(), line.substring(pos, pos + 2), regularStyle);
                        pos += 2;
                    }
                }
                // Check for italic text
                else if (pos < line.length() && line.charAt(pos) == '*') {
                    int endPos = line.indexOf("*", pos + 1);
                    if (endPos != -1) {
                        // Add the italic text
                        String italicText = line.substring(pos + 1, endPos);
                        doc.insertString(doc.getLength(), italicText, italicStyle);
                        pos = endPos + 1;
                    } else {
                        // No end marker, add as regular text
                        doc.insertString(doc.getLength(), "*", regularStyle);
                        pos++;
                    }
                }
                // Check for inline code
                else if (pos < line.length() && line.charAt(pos) == '`') {
                    int endPos = line.indexOf("`", pos + 1);
                    if (endPos != -1) {
                        // Add the code text
                        String codeText = line.substring(pos + 1, endPos);
                        doc.insertString(doc.getLength(), codeText, codeStyle);
                        pos = endPos + 1;
                    } else {
                        // No end marker, add as regular text
                        doc.insertString(doc.getLength(), "`", regularStyle);
                        pos++;
                    }
                }
                // Regular text
                else {
                    // Find the next special character
                    int nextPos = line.length();
                    int boldPos = line.indexOf("**", pos);
                    int italicPos = line.indexOf("*", pos);
                    int codePos = line.indexOf("`", pos);
                    
                    if (boldPos != -1 && boldPos < nextPos) nextPos = boldPos;
                    if (italicPos != -1 && italicPos < nextPos) nextPos = italicPos;
                    if (codePos != -1 && codePos < nextPos) nextPos = codePos;
                    
                    // Add the regular text
                    doc.insertString(doc.getLength(), line.substring(pos, nextPos), regularStyle);
                    pos = nextPos;
                }
            }
            
            // Add a newline after each line except the last one
            if (i < lines.length - 1) {
                doc.insertString(doc.getLength(), "\n", regularStyle);
            }
        }
    }

    private void createElementButtons(String message) {
        // Get context-aware element suggestions
        String[][] suggestions = getContextAwareElementSuggestions();
        
        if (suggestions.length == 0) {
            log.info("No context-aware element suggestions available");
            return;
        }
        
        try {
            StyledDocument doc = chatArea.getStyledDocument();
            
            // Create a panel for the suggestions
            JPanel suggestionPanel = new JPanel(new BorderLayout());
            suggestionPanel.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0));
            
            // Add a label for the suggestions
            JLabel suggestionLabel = new JLabel("Suggested elements you can add:");
            suggestionLabel.setFont(new Font(suggestionLabel.getFont().getName(), Font.BOLD, 12));
            suggestionLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));
            suggestionPanel.add(suggestionLabel, BorderLayout.NORTH);
            
            // Create a grid panel for the buttons (2x2 grid)
            JPanel gridPanel = new JPanel(new GridLayout(0, 2, 5, 5));
            
            // Add buttons for each suggested element (up to 4)
            int count = 0;
            for (String[] element : suggestions) {
                if (count >= 4) break; // Limit to 4 buttons
                
                String displayName = element[0];
                String normalizedType = element[1];
                
                // Create a button for the element
                JButton addButton = createElementButton(displayName, normalizedType, null);
                
                // Add the button to the panel
                gridPanel.add(addButton);
                count++;
            }
            
            // Add the grid panel to the main panel
            suggestionPanel.add(gridPanel, BorderLayout.CENTER);
            
            // Insert a placeholder for the component at the end of the document
            int pos = doc.getLength();
            doc.insertString(pos, "\n", new SimpleAttributeSet());
            pos = doc.getLength();
            doc.insertString(pos, " ", new SimpleAttributeSet());
            
            // Create a style for the component
            SimpleAttributeSet componentStyle = new SimpleAttributeSet();
            StyleConstants.setComponent(componentStyle, suggestionPanel);
            
            // Apply the style to the placeholder
            doc.setCharacterAttributes(pos, 1, componentStyle, false);
            
            // Add a newline after the buttons
            doc.insertString(doc.getLength(), "\n", new SimpleAttributeSet());
            
            log.info("Added element suggestion buttons: {}", count);
        } catch (BadLocationException e) {
            log.error("Error adding element buttons", e);
        }
    }

    private JButton createElementButton(String displayName, String normalizedType, String additionalInfo) {
        // Create a button for the element
        JButton addButton = new JButton("Add " + displayName);
        
        // Set button appearance
        addButton.setBackground(new Color(135, 206, 250)); // Light blue background
        addButton.setForeground(Color.BLACK); // Black text
        addButton.setFocusPainted(false);
        addButton.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(34, 139, 34), 1, true), // Forest green border
                BorderFactory.createEmptyBorder(5, 10, 5, 10)
        ));
        
        // Add tooltip if additional info is provided
        if (additionalInfo != null && !additionalInfo.isEmpty()) {
            addButton.setToolTipText(additionalInfo);
        }
        
        // Add action listener
        String finalNormalizedType = normalizedType;
        addButton.addActionListener(e -> {
            // Create a request to add the element
            String request = "add " + finalNormalizedType;
            
            // Process the request
            String response = JMeterElementRequestHandler.processElementRequest(request);
            
            // Select the newly added element
            selectLastAddedElement();

            // Process the response
            processAiResponse(response);
        });
        
        return addButton;
    }
    
    /**
     * Selects the last added element in the test plan
     */
    private void selectLastAddedElement() {
        log.info("Selecting last added element");
        
        try {
            GuiPackage guiPackage = GuiPackage.getInstance();
            if (guiPackage == null) {
                log.warn("Cannot select element: GuiPackage is null");
                return;
            }
            
            JMeterTreeNode currentNode = guiPackage.getTreeListener().getCurrentNode();
            if (currentNode == null) {
                log.warn("Cannot select element: Current node is null");
                return;
            }
            
            // If the current node has children, select the last child
            if (currentNode.getChildCount() > 0) {
                JMeterTreeNode lastChild = (JMeterTreeNode) currentNode.getChildAt(currentNode.getChildCount() - 1);
                // Use setSelectionPath instead of selectNode
                guiPackage.getTreeListener().getJTree().setSelectionPath(new TreePath(lastChild.getPath()));
                log.info("Selected last child of current node: {}", lastChild.getName());
            } else {
                log.info("Current node has no children to select");
            }
        } catch (Exception e) {
            log.error("Error selecting last added element", e);
        }
    }
    
    /**
     * Suggests related elements after adding an element
     */
    private void suggestRelatedElements() {
        // Get the currently selected node from JMeter
        JMeterTreeNode selectedNode = GuiPackage.getInstance().getTreeListener().getCurrentNode();
        
        if (selectedNode == null) {
            log.warn("No node selected in test plan, cannot suggest related elements");
            return;
        }
        
        // Get the type of the selected node
        TestElement testElement = selectedNode.getTestElement();
        String nodeType = testElement.getClass().getSimpleName();
        
        log.info("Suggesting related elements for node type: {}", nodeType);
        
        // Get related elements based on the node type
        String[][] relatedElements = getRelatedElements(nodeType);
        
        // If no related elements were found, return
        if (relatedElements.length == 0) {
            log.info("No related elements found for node type: {}", nodeType);
            return;
        }
        
        // Create a panel for the suggestion message and buttons
        JPanel suggestionPanel = new JPanel(new BorderLayout());
        suggestionPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        
        // Add a message about the suggestions
        JLabel suggestionLabel = new JLabel("Here are some elements you might want to add next:");
        suggestionLabel.setFont(new Font(suggestionLabel.getFont().getName(), Font.BOLD, suggestionLabel.getFont().getSize()));
        suggestionPanel.add(suggestionLabel, BorderLayout.NORTH);
        
        // Create a grid panel for the buttons (2x2 grid)
        JPanel gridPanel = new JPanel(new GridLayout(0, 2, 5, 5));
        gridPanel.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));
        
        // Add buttons for each related element (up to 4)
        int buttonCount = 0;
        for (String[] element : relatedElements) {
            if (buttonCount >= 4) break; // Limit to 4 buttons
            
            String displayName = element[0];
            String normalizedType = element[1];
            
            // Create a button for the element
            JButton addButton = createElementButton(displayName, normalizedType, null);
            
            // Add the button to the panel
            gridPanel.add(addButton);
            buttonCount++;
        }
        
        // Add the grid panel to the main panel
        suggestionPanel.add(gridPanel, BorderLayout.CENTER);
        
        try {
            StyledDocument doc = chatArea.getStyledDocument();
            
            // Insert a placeholder for the component
            int pos = doc.getLength();
            doc.insertString(pos, " ", new SimpleAttributeSet());
            
            // Create a style for the component
            SimpleAttributeSet componentStyle = new SimpleAttributeSet();
            StyleConstants.setComponent(componentStyle, suggestionPanel);
            
            // Apply the style to the placeholder
            doc.setCharacterAttributes(pos, 1, componentStyle, false);
            
            // Scroll to the bottom
            chatArea.setCaretPosition(doc.getLength());
            
            log.info("Added related element suggestions");
        } catch (BadLocationException e) {
            log.error("Error adding suggestion buttons", e);
        }
    }
    
    /**
     * Gets related elements based on the added element type
     * 
     * @param addedElementType The type of element that was added
     * @return Array of related elements [display name, normalized type]
     */
    private String[][] getRelatedElements(String addedElementType) {
        switch (addedElementType.toLowerCase()) {
            case "threadgroup":
                return new String[][] {
                    {"HTTP Sampler", "httpsampler"},
                    {"Loop Controller", "loopcontroller"},
                    {"CSV Data Set", "csvdataset"},
                    {"Constant Timer", "constanttimer"}
                };
            case "httpsampler":
                return new String[][] {
                    {"Response Assertion", "responseassert"},
                    {"JSON Path Extractor", "jsonpathextractor"},
                    {"Constant Timer", "constanttimer"},
                    {"View Results Tree", "viewresultstree"}
                };
            case "loopcontroller":
                return new String[][] {
                    {"HTTP Sampler", "httpsampler"},
                    {"If Controller", "ifcontroller"},
                    {"Transaction Controller", "transactioncontroller"}
                };
            case "csvdataset":
                return new String[][] {
                    {"HTTP Sampler", "httpsampler"},
                    {"HTTP Header Manager", "headermanager"}
                };
            case "responseassert":
            case "jsonassertion":
            case "durationassertion":
            case "sizeassertion":
            case "xpathassertion":
                return new String[][] {
                    {"View Results Tree", "viewresultstree"},
                    {"Aggregate Report", "aggregatereport"}
                };
            case "constanttimer":
            case "uniformrandomtimer":
            case "gaussianrandomtimer":
            case "poissonrandomtimer":
                return new String[][] {
                    {"HTTP Sampler", "httpsampler"},
                    {"Response Assertion", "responseassert"}
                };
            case "regexextractor":
            case "xpathextractor":
            case "jsonpathextractor":
            case "boundaryextractor":
                return new String[][] {
                    {"Response Assertion", "responseassert"},
                    {"If Controller", "ifcontroller"}
                };
            case "viewresultstree":
            case "aggregatereport":
                return new String[][] {
                    {"Response Assertion", "responseassert"},
                    {"Duration Assertion", "durationassert"}
                };
            default:
                return new String[0][0];
        }
    }
    
    /**
     * Creates example buttons for common JMeter elements
     * 
     * @param panel The panel to add the buttons to
     */
    private void createExampleButtons(JPanel panel) {
        // Get context-aware element suggestions based on the current test plan structure
        String[][] elements = getContextAwareElementSuggestions();
        
        log.info("Creating {} context-aware element suggestion buttons", elements.length);
        
        for (String[] element : elements) {
            String displayName = element[0];
            String normalizedType = element[1];
            
            // Create a button for the element
            JButton addButton = createElementButton(displayName, normalizedType, null);
            
            // Add the button to the panel
            panel.add(addButton);
            log.info("Added context-aware button for element: {}", displayName);
        }
    }
    
    /**
     * Gets context-aware element suggestions based on the current test plan structure
     * 
     * @return Array of suggested elements [display name, normalized type]
     */
    private String[][] getContextAwareElementSuggestions() {
        log.info("Getting context-aware element suggestions");
        
        // Get the current selected node from JMeter
        JMeterTreeNode selectedNode = GuiPackage.getInstance().getTreeListener().getCurrentNode();
        
        if (selectedNode == null) {
            log.warn("No node selected, suggesting test plan elements");
            // If no node is selected, suggest creating a test plan
            return new String[][] {
                {"Test Plan", "testplan"},
                {"Thread Group", "threadgroup"},
                {"HTTP Request", "httprequest"},
                {"View Results Tree", "viewresultstree"}
            };
        }
        
        // Get the type of the selected node
        String selectedNodeType = selectedNode.getStaticLabel().toLowerCase();
        log.info("Selected node type: {}", selectedNodeType);
        
        // Return suggestions based on the selected node type
        return getSuggestionsForNodeType(selectedNodeType);
    }
    
    /**
     * Gets element suggestions based on the type of the selected node
     * 
     * @param nodeType The type of the selected node
     * @return Array of suggested elements [display name, normalized type]
     */
    private String[][] getSuggestionsForNodeType(String nodeType) {
        // Convert node type to lowercase for case-insensitive comparison
        nodeType = nodeType.toLowerCase();
        
        // Check for specific node types and return appropriate suggestions
        switch (nodeType) {
            case "test plan":
                return new String[][] {
                    {"Thread Group", "threadgroup"},
                    {"HTTP Header Manager", "headerManager"},
                    {"CSV Data Set Config", "csvdataset"},
                    {"View Results Tree", "viewresultstree"}
                };
                
            case "thread group":
            case "setUp thread group":
            case "tearDown thread group":
                return new String[][] {
                    {"HTTP Request", "httprequest"},
                    {"Loop Controller", "loopcontroller"},
                    {"If Controller", "ifcontroller"},
                    {"While Controller", "whilecontroller"}
                };
                
            case "http request":
            case "http sampler":
                return new String[][] {
                    {"Response Assertion", "responseassert"},
                    {"JSON Path Assertion", "jsonpathassert"},
                    {"Duration Assertion", "durationassert"},
                    {"Size Assertion", "sizeassert"}
                };
                
            case "loop controller":
            case "if controller":
            case "while controller":
            case "transaction controller":
            case "runtime controller":
                return new String[][] {
                    {"HTTP Request", "httprequest"},
                    {"Loop Controller", "loopcontroller"},
                    {"If Controller", "ifcontroller"},
                    {"While Controller", "whilecontroller"}
                };
                
            case "view results tree":
            case "aggregate report":
                return new String[][] {
                    {"Thread Group", "threadgroup"},
                    {"HTTP Request", "httprequest"},
                    {"View Results Tree", "viewresultstree"},
                    {"Aggregate Report", "aggregatereport"}
                };
                
            case "response assertion":
            case "json path assertion":
            case "duration assertion":
            case "size assertion":
            case "xpath assertion":
                return new String[][] {
                    {"HTTP Request", "httprequest"},
                    {"Response Assertion", "responseassert"},
                    {"JSON Path Assertion", "jsonpathassert"},
                    {"XPath Assertion", "xpathassert"}
                };
                
            case "constant timer":
            case "uniform random timer":
            case "gaussian random timer":
            case "poisson random timer":
                return new String[][] {
                    {"HTTP Request", "httprequest"},
                    {"Constant Timer", "constanttimer"},
                    {"Uniform Random Timer", "uniformrandomtimer"},
                    {"Gaussian Random Timer", "gaussianrandomtimer"}
                };
                
            case "regex extractor":
            case "xpath extractor":
            case "json path extractor":
            case "boundary extractor":
                return new String[][] {
                    {"Regex Extractor", "regexextractor"},
                    {"XPath Extractor", "xpathextractor"},
                    {"JSON Path Extractor", "jsonpathextractor"},
                    {"Boundary Extractor", "boundaryextractor"}
                };
                
            case "listener":
                return new String[][] {
                    {"View Results Tree", "viewresultstree"},
                    {"Aggregate Report", "aggregatereport"},
                    {"Thread Group", "threadgroup"},
                    {"HTTP Request", "httprequest"}
                };
                
            case "csv data set config":
            case "http header manager":
                return new String[][] {
                    {"Thread Group", "threadgroup"},
                    {"HTTP Request", "httprequest"},
                    {"CSV Data Set Config", "csvdataset"},
                    {"HTTP Header Manager", "headerManager"}
                };
                
            default:
                // Default suggestions for unknown node types
                return new String[][] {
                    {"Thread Group", "threadgroup"},
                    {"HTTP Request", "httprequest"},
                    {"View Results Tree", "viewresultstree"},
                    {"Loop Controller", "loopcontroller"}
                };
        }
    }

    /**
     * Maps a user-friendly element type to a normalized type that JMeter understands
     * 
     * @param elementType The user-friendly element type
     * @return The normalized element type, or null if not recognized
     */
    private String mapToNormalizedElementType(String elementType) {
        elementType = elementType.toLowerCase();
        log.info("Trying to map element type: {}", elementType);
        
        // Generic element types
        if (elementType.contains("sampler")) {
            if (elementType.contains("http")) {
                return "httpsampler";
            }
            // Default to HTTP sampler if no specific type is mentioned
            return "httpsampler";
        }
        
        if (elementType.contains("controller")) {
            if (elementType.contains("loop")) {
                return "loopcontroller";
            }
            if (elementType.contains("if")) {
                return "ifcontroller";
            }
            if (elementType.contains("while")) {
                return "whilecontroller";
            }
            if (elementType.contains("transaction")) {
                return "transactioncontroller";
            }
            if (elementType.contains("runtime")) {
                return "runtimecontroller";
            }
            // Default to loop controller if no specific type is mentioned
            return "loopcontroller";
        }
        
        if (elementType.contains("timer")) {
            if (elementType.contains("constant")) {
                return "constanttimer";
            }
            if (elementType.contains("uniform")) {
                return "uniformrandomtimer";
            }
            if (elementType.contains("gaussian")) {
                return "gaussianrandomtimer";
            }
            if (elementType.contains("poisson")) {
                return "poissonrandomtimer";
            }
            // Default to constant timer if no specific type is mentioned
            return "constanttimer";
        }
        
        if (elementType.contains("assertion")) {
            if (elementType.contains("response")) {
                return "responseassert";
            }
            if (elementType.contains("json")) {
                return "jsonassertion";
            }
            if (elementType.contains("duration")) {
                return "durationassertion";
            }
            if (elementType.contains("size")) {
                return "sizeassertion";
            }
            if (elementType.contains("xpath")) {
                return "xpathassertion";
            }
            // Default to response assertion if no specific type is mentioned
            return "responseassert";
        }
        
        if (elementType.contains("extractor")) {
            if (elementType.contains("regex")) {
                return "regexextractor";
            }
            if (elementType.contains("xpath")) {
                return "xpathextractor";
            }
            if (elementType.contains("json")) {
                return "jsonpathextractor";
            }
            if (elementType.contains("boundary")) {
                return "boundaryextractor";
            }
            // Default to regex extractor if no specific type is mentioned
            return "regexextractor";
        }
        
        if (elementType.contains("listener")) {
            if (elementType.contains("view") && elementType.contains("tree")) {
                return "viewresultstree";
            }
            if (elementType.contains("aggregate")) {
                return "aggregatereport";
            }
            // Default to view results tree if no specific type is mentioned
            return "viewresultstree";
        }
        
        // Specific element types
        if (elementType.contains("thread") && elementType.contains("group")) {
            return "threadgroup";
        }
        
        if (elementType.contains("csv") || elementType.contains("data set")) {
            return "csvdataset";
        }
        
        if (elementType.contains("header") && elementType.contains("manager")) {
            return "headermanager";
        }
        
        // Try to match partial element types
        if (elementType.contains("http")) {
            return "httpsampler";
        }
        
        if (elementType.contains("thread")) {
            return "threadgroup";
        }
        
        if (elementType.contains("csv")) {
            return "csvdataset";
        }
        
        if (elementType.contains("header")) {
            return "headermanager";
        }
        
        // If we couldn't match the element type, return null
        log.info("Could not map element type: {}", elementType);
        return null;
    }
    
    /**
     * Formats an element type for display in the button
     * 
     * @param elementType The element type to format
     * @return The formatted element type
     */
    private String formatElementType(String elementType) {
        // Capitalize the first letter of each word
        String[] words = elementType.split("\\s+");
        StringBuilder result = new StringBuilder();
        
        for (String word : words) {
            if (word.length() > 0) {
                result.append(Character.toUpperCase(word.charAt(0)))
                      .append(word.substring(1))
                      .append(" ");
            }
        }
        
        return result.toString().trim();
    }

    /**
     * Sends a user message to the AI and handles the response
     * 
     * @param message The user message
     */
    private void sendUserMessage(String message) {
        // Check if this is a special @this command
        if (message.toLowerCase().contains("@this")) {
            // Handle the @this command to get information about the current element
            String currentElementInfo = getCurrentElementInfo();
            if (currentElementInfo != null) {
                // Display loading indicator
                appendToChat("AI is thinking...", new Color(128, 128, 128), false);
                
                // Disable input while processing
                messageField.setEnabled(false);
                sendButton.setEnabled(false);
                
                // Process the message in a background thread
                new SwingWorker<String, Void>() {
                    @Override
                    protected String doInBackground() throws Exception {
                        // Create a context-enhanced message for Claude
                        String enhancedMessage = "The user asked: \"" + message + "\"\n\n" +
                                "Here is information about the currently selected element in the JMeter test plan:\n\n" +
                                currentElementInfo + "\n\n" +
                                "Based on the above information about the selected JMeter element and the user's question, " +
                                "please provide a helpful, specific response that addresses their query. " +
                                "Include practical advice on how to configure, use, or optimize this element effectively. " +
                                "If appropriate, suggest compatible elements that would work well with this one, " +
                                "and explain how they would interact. " +
                                "Focus on JMeter-specific best practices that apply to this particular element.";
                        
                        // Get the currently selected model from the dropdown
                        ModelInfo selectedModel = (ModelInfo) modelSelector.getSelectedItem();
                        if (selectedModel != null) {
                            // Set the current model ID before generating the response
                            claudeService.setModel(selectedModel.id());
                        }
                        
                        // Send the enhanced message to Claude
                        return claudeService.generateDirectResponse(enhancedMessage);
                    }
                    
                    @Override
                    protected void done() {
                        try {
                            // Remove the loading indicator
                            removeLoadingIndicator();
                            
                            // Get the AI response
                            String response = get();
                            
                            // Process the AI response
                            processAiResponse(response);
                            
                            // Add the AI response to the conversation history
                            conversationHistory.add(response);
                            
                            // Re-enable input
                            messageField.setEnabled(true);
                            sendButton.setEnabled(true);
                            messageField.requestFocusInWindow();
                        } catch (InterruptedException | ExecutionException e) {
                            log.error("Error getting AI response for @this command", e);
                            
                            // Remove the loading indicator
                            removeLoadingIndicator();
                            
                            // Display error message
                            appendToChat("Sorry, I encountered an error while processing your request. Please try again.", Color.RED, false);
                            
                            // Re-enable input
                            messageField.setEnabled(true);
                            sendButton.setEnabled(true);
                            messageField.requestFocusInWindow();
                        }
                    }
                }.execute();
                return;
            } else {
                processAiResponse("I couldn't find information about the currently selected element. Please make sure you have selected an element in the test plan.");
                return;
            }
        }

        // Check if this is an element request
        String elementResponse = JMeterElementRequestHandler.processElementRequest(message);
        
        // Only process as an element request if it's a valid request
        // This prevents general conversation from being interpreted as element requests
        if (elementResponse != null && !elementResponse.contains("I couldn't understand what to do with")) {
            log.info("Detected element request, returning structured response");
            processAiResponse(elementResponse);
            return;
        }
        
        // Display loading indicator
        appendToChat("AI is thinking...", new Color(128, 128, 128), false);
        
        // Disable input while processing
        messageField.setEnabled(false);
        sendButton.setEnabled(false);
        
        // Process the message in a background thread
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return getAiResponse(message);
            }
            
            @Override
            protected void done() {
                try {
                    // Remove the loading indicator
                    removeLoadingIndicator();
                    
                    // Get the AI response
                    String response = get();
                    
                    // Process the AI response
                    processAiResponse(response);
                    
                    // Add the AI response to the conversation history
                    conversationHistory.add(response);
                    
                    // Re-enable input
                    messageField.setEnabled(true);
                    sendButton.setEnabled(true);
                    messageField.requestFocusInWindow();
                } catch (InterruptedException | ExecutionException e) {
                    log.error("Error getting AI response", e);
                    
                    // Remove the loading indicator
                    removeLoadingIndicator();
                    
                    // Display error message
                    appendToChat("Sorry, I encountered an error while processing your request. Please try again.", Color.RED, false);
                    
                    // Re-enable input
                    messageField.setEnabled(true);
                    sendButton.setEnabled(true);
                    messageField.requestFocusInWindow();
                }
            }
        }.execute();
    }
    
    /**
     * Gets information about the currently selected element in the test plan
     * 
     * @return A formatted string with information about the current element, or null if no element is selected
     */
    private String getCurrentElementInfo() {
        try {
            GuiPackage guiPackage = GuiPackage.getInstance();
            if (guiPackage == null) {
                log.warn("Cannot get element info: GuiPackage is null");
                return null;
            }
            
            JMeterTreeNode currentNode = guiPackage.getTreeListener().getCurrentNode();
            if (currentNode == null) {
                log.warn("No node is currently selected in the test plan");
                return null;
            }
            
            // Get the test element
            TestElement element = currentNode.getTestElement();
            if (element == null) {
                log.warn("Selected node does not have a test element");
                return null;
            }
            
            // Build information about the element
            StringBuilder info = new StringBuilder();
            info.append("# ").append(currentNode.getName()).append(" (").append(element.getClass().getSimpleName()).append(")\n\n");
            
            // Add description based on element type
            String elementType = element.getClass().getSimpleName();
            info.append(getElementDescription(elementType)).append("\n\n");
            
            // Add properties
            info.append("## Properties\n\n");
            
            // Get all property names
            PropertyIterator propertyIterator = element.propertyIterator();
            while (propertyIterator.hasNext()) {
                JMeterProperty property = propertyIterator.next();
                String propertyName = property.getName();
                String propertyValue = property.getStringValue();
                
                // Skip empty properties and internal JMeter properties
                if (!propertyValue.isEmpty() && !propertyName.startsWith("TestElement.") && !propertyName.equals("guiclass")) {
                    // Format the property name for better readability
                    String formattedName = propertyName.replace(".", " ").replace("_", " ");
                    formattedName = formattedName.substring(0, 1).toUpperCase() + formattedName.substring(1);
                    
                    info.append("- **").append(formattedName).append("**: ").append(propertyValue).append("\n");
                }
            }
            
            // Add hierarchical information
            info.append("\n## Location in Test Plan\n\n");
            
            // Get parent node
            TreeNode parent = currentNode.getParent();
            if (parent instanceof JMeterTreeNode) {
                JMeterTreeNode parentNode = (JMeterTreeNode) parent;
                info.append("- Parent: **").append(parentNode.getName()).append("** (").append(parentNode.getTestElement().getClass().getSimpleName()).append(")\n");
            }
            
            // Get child nodes
            if (currentNode.getChildCount() > 0) {
                info.append("- Children: ").append(currentNode.getChildCount()).append("\n");
                for (int i = 0; i < currentNode.getChildCount(); i++) {
                    JMeterTreeNode childNode = (JMeterTreeNode) currentNode.getChildAt(i);
                    info.append("  - **").append(childNode.getName()).append("** (").append(childNode.getTestElement().getClass().getSimpleName()).append(")\n");
                }
            } else {
                info.append("- No children\n");
            }
            
            // Add suggestions for what can be added to this element
            info.append("\n## Suggested Elements\n\n");
            String[][] suggestions = getSuggestionsForNodeType(currentNode.getStaticLabel().toLowerCase());
            if (suggestions.length > 0) {
                info.append("You can add the following elements to this node:\n\n");
                for (String[] suggestion : suggestions) {
                    info.append("- ").append(suggestion[0]).append("\n");
                }
            } else {
                info.append("No specific suggestions for this element type.\n");
            }
            
            return info.toString();
        } catch (Exception e) {
            log.error("Error getting current element info", e);
            return "Error retrieving element information: " + e.getMessage();
        }
    }
    
    /**
     * Gets a description for a specific element type
     * 
     * @param elementType The type of element
     * @return A description of the element
     */
    private String getElementDescription(String elementType) {
        // Convert to lowercase for case-insensitive comparison
        String type = elementType.toLowerCase();
        
        if (type.contains("testplan")) {
            return "The Test Plan is the root element of a JMeter test. It defines global settings and variables for the test.";
        } else if (type.contains("threadgroup")) {
            return "Thread Groups simulate users accessing your application. Each thread represents a user, and you can configure the number of threads, ramp-up period, and loop count.";
        } else if (type.contains("httpsampler") || type.contains("httprequest")) {
            return "HTTP Samplers send HTTP/HTTPS requests to a web server. You can configure the URL, method, parameters, and other settings.";
        } else if (type.contains("loopcontroller")) {
            return "Loop Controllers repeat their child elements a specified number of times or indefinitely.";
        } else if (type.contains("ifcontroller")) {
            return "If Controllers execute their child elements only if a condition is true. The condition can be a JavaScript expression or a variable reference.";
        } else if (type.contains("whilecontroller")) {
            return "While Controllers execute their child elements repeatedly as long as a condition is true.";
        } else if (type.contains("transactioncontroller")) {
            return "Transaction Controllers group samplers together to measure the total time taken by all samplers within the transaction.";
        } else if (type.contains("runtimecontroller")) {
            return "Runtime Controllers execute their child elements for a specified amount of time.";
        } else if (type.contains("responseassert")) {
            return "Response Assertions validate the response from a sampler, such as checking for specific text or patterns.";
        } else if (type.contains("jsonpathassert") || type.contains("jsonassertion")) {
            return "JSON Path Assertions validate JSON responses using JSONPath expressions.";
        } else if (type.contains("durationassertion")) {
            return "Duration Assertions validate that a sampler completes within a specified time.";
        } else if (type.contains("sizeassertion")) {
            return "Size Assertions validate the size of a response.";
        } else if (type.contains("xpathassertion")) {
            return "XPath Assertions validate XML responses using XPath expressions.";
        } else if (type.contains("constanttimer")) {
            return "Constant Timers add a fixed delay between sampler executions.";
        } else if (type.contains("uniformrandomtimer")) {
            return "Uniform Random Timers add a random delay between sampler executions, with a uniform distribution.";
        } else if (type.contains("gaussianrandomtimer")) {
            return "Gaussian Random Timers add a random delay between sampler executions, with a Gaussian (normal) distribution.";
        } else if (type.contains("poissonrandomtimer")) {
            return "Poisson Random Timers add a random delay between sampler executions, with a Poisson distribution.";
        } else if (type.contains("csvdataset")) {
            return "CSV Data Set Config elements read data from CSV files to parameterize your test.";
        } else if (type.contains("headermanager")) {
            return "HTTP Header Manager elements add HTTP headers to your requests.";
        } else if (type.contains("viewresultstree")) {
            return "View Results Tree listeners display detailed results for each sampler, including request and response data.";
        } else if (type.contains("aggregatereport")) {
            return "Aggregate Report listeners display summary statistics for each sampler, such as average response time and throughput.";
        } else if (type.contains("extractor") || type.contains("postprocessor")) {
            if (type.contains("jsonpath")) {
                return "JSON Path Extractors extract values from JSON responses using JSONPath expressions.";
            } else if (type.contains("xpath")) {
                return "XPath Extractors extract values from XML responses using XPath expressions.";
            } else if (type.contains("regex")) {
                return "Regular Expression Extractors extract values from responses using regular expressions.";
            } else if (type.contains("boundary")) {
                return "Boundary Extractors extract values from responses using boundary strings.";
            }
            return "Extractors extract values from responses for use in subsequent requests.";
        }
        
        // Generic description for unknown element types
        return "This is a " + elementType + " element in your JMeter test plan.";
    }
    
    /**
     * Removes the loading indicator from the chat
     */
    private void removeLoadingIndicator() {
        try {
            StyledDocument doc = chatArea.getStyledDocument();
            
            // Find the loading indicator text
            String text = doc.getText(0, doc.getLength());
            int index = text.lastIndexOf("AI is thinking...");
            
            if (index != -1) {
                // Remove the loading indicator
                doc.remove(index, "AI is thinking...".length());
            }
        } catch (BadLocationException e) {
            log.error("Error removing loading indicator", e);
        }
    }

    /**
     * Processes the AI response and handles any element addition requests
     * 
     * @param response The AI response
     */
    private void processAiResponse(String response) {
        if (response == null || response.isEmpty()) {
            appendToChat("No response from AI. Please try again.", Color.RED, false);
            log.warn("Empty AI response");
            return;
        }
        
        log.info("Processing AI response: {}", response.substring(0, Math.min(100, response.length())));
        
        // Add the AI response to the chat
        appendToChat(response, new Color(0, 51, 102), true);
        
        // Create element buttons for context-aware suggestions after the AI response
        SwingUtilities.invokeLater(() -> {
            createElementButtons(response);
        });
    }
    
    /**
     * Gets a response from the AI based on the conversation history
     * 
     * @param message The user message
     * @return The AI response
     */
    private String getAiResponse(String message) {
        log.info("Getting AI response for message: {}", message);
        
        // Check if this is a request for a performance test plan
        String performanceTestPlanResponse = JMeterElementRequestHandler.processPerformanceTestPlanRequest(message);
        if (performanceTestPlanResponse != null) {
            log.info("Detected performance test plan request, returning structured response");
            return performanceTestPlanResponse;
        }
        
        // Get the currently selected model from the dropdown
        ModelInfo selectedModel = (ModelInfo) modelSelector.getSelectedItem();
        if (selectedModel != null) {
            // Set the current model ID before generating the response
            log.info("Using model from dropdown for message: {}", selectedModel.id());
            claudeService.setModel(selectedModel.id());
        } else {
            log.warn("No model selected in dropdown, using default model: {}", claudeService.getCurrentModel());
        }
        
        // Call Claude API with full conversation history
        return claudeService.generateResponse(new ArrayList<>(conversationHistory));
    }
}