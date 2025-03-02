package org.qainsights.jmeter.ai.gui;

import org.qainsights.jmeter.ai.service.ClaudeService;
import org.qainsights.jmeter.ai.utils.Models;
import com.anthropic.models.ModelInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.swing.*;
import javax.swing.text.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AiChatPanel extends JPanel {
    private JTextPane chatArea;
    private JTextField inputField;
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
        setPreferredSize(new Dimension(400, 600));
        setMinimumSize(new Dimension(300, 400));
        
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
        JLabel titleLabel = new JLabel("JMeter Agent");
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
        // Use the system default font
        chatArea.setFont(UIManager.getFont("TextField.font"));
        JScrollPane scrollPane = new JScrollPane(chatArea);
        scrollPane.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        chatPanel.add(scrollPane, BorderLayout.CENTER);
        
        // Add the chat panel to the center of the main panel
        add(chatPanel, BorderLayout.CENTER);

        // Create the bottom panel with model selector and input controls
        JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        
        // Add model selector to the bottom panel
        JPanel modelPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JLabel modelLabel = new JLabel("Model: ");
        modelPanel.add(modelLabel);
        modelPanel.add(modelSelector);
        bottomPanel.add(modelPanel, BorderLayout.NORTH);
        
        // Create input panel with text field and send button
        JPanel inputPanel = new JPanel(new BorderLayout(5, 0));
        inputField = new JTextField();
        sendButton = new JButton("Send");
        inputPanel.add(inputField, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);
        bottomPanel.add(inputPanel, BorderLayout.CENTER);
        
        add(bottomPanel, BorderLayout.SOUTH);

        sendButton.addActionListener(e -> sendMessage());
        inputField.addActionListener(e -> sendMessage());

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
        inputField.setText("");
        
        // Display welcome message
        displayWelcomeMessage();
        
        // Log the action
        log.info("Started new conversation");
    }
    
    /**
     * Displays a welcome message in the chat area
     */
    private void displayWelcomeMessage() {
        try {
            StyledDocument doc = chatArea.getStyledDocument();
            
            // Create a style for the welcome message
            SimpleAttributeSet welcomeStyle = new SimpleAttributeSet();
            StyleConstants.setForeground(welcomeStyle, new Color(100, 100, 100));
            StyleConstants.setAlignment(welcomeStyle, StyleConstants.ALIGN_CENTER);
            
            // Add the welcome message
            doc.insertString(doc.getLength(), "New conversation started\n", welcomeStyle);
            doc.insertString(doc.getLength(), "Type your message below and press Enter to send\n", welcomeStyle);
            doc.insertString(doc.getLength(), "AI can make mistakes, please verify the response\n", welcomeStyle);
            
            // Apply paragraph alignment
            doc.setParagraphAttributes(0, doc.getLength(), welcomeStyle, false);
            
        } catch (BadLocationException e) {
            log.error("Error displaying welcome message", e);
        }
    }

    private void sendMessage() {
        String message = inputField.getText().trim();
        if (!message.isEmpty()) {
            // Disable input field and send button while waiting for response
            inputField.setEnabled(false);
            sendButton.setEnabled(false);

            appendToChat("You: " + message, Color.BLUE, false);
            inputField.setText("");

            conversationHistory.add(message);

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
            new SwingWorker<String, Void>() {
                @Override
                protected String doInBackground() {
                    return claudeService.generateResponse(new ArrayList<>(conversationHistory));
                }

                @Override
                protected void done() {
                    try {
                        String response = get();
                        appendToChat("Claude: " + response, Color.BLACK, true);
                        conversationHistory.add(response);
                    } catch (Exception e) {
                        log.error("Error getting response", e);
                        appendToChat("Error: " + e.getMessage(), Color.RED, false);
                    } finally {
                        // Re-enable input field and send button after response is received
                        inputField.setEnabled(true);
                        sendButton.setEnabled(true);
                        inputField.requestFocus();
                    }
                }
            }.execute();
        }
    }

    private void appendToChat(String message, Color color, boolean parseMarkdown) {
        StyledDocument doc = chatArea.getStyledDocument();

        try {
            // Reset any previous paragraph attributes to default alignment
            SimpleAttributeSet defaultParagraphStyle = new SimpleAttributeSet();
            StyleConstants.setAlignment(defaultParagraphStyle, StyleConstants.ALIGN_LEFT);
            doc.setParagraphAttributes(doc.getLength(), 0, defaultParagraphStyle, true);
            
            // Get the system default font
            Font defaultFont = UIManager.getFont("TextField.font");
            
            // Add the sender part with the specified color
            SimpleAttributeSet senderStyle = new SimpleAttributeSet();
            StyleConstants.setForeground(senderStyle, color);
            StyleConstants.setBold(senderStyle, true);
            StyleConstants.setFontFamily(senderStyle, defaultFont.getFamily());
            StyleConstants.setFontSize(senderStyle, defaultFont.getSize());

            // For Claude messages, only style the "Claude: " part
            if (message.startsWith("Claude: ") && parseMarkdown) {
                doc.insertString(doc.getLength(), "Claude: ", senderStyle);

                // Process the rest of the message for markdown
                String claudeMessage = message.substring("Claude: ".length());
                processMarkdownMessage(doc, claudeMessage);
                
                // Parse the Claude message for element suggestions and create buttons
                createElementButtons(claudeMessage);
            } else {
                // For user messages or non-markdown messages
                doc.insertString(doc.getLength(), message + "\n", senderStyle);
            }

            // Scroll to the end
            chatArea.setCaretPosition(doc.getLength());
        } catch (BadLocationException e) {
            log.error("Error appending to chat", e);
        }
    }

    private void processMarkdownMessage(StyledDocument doc, String message) throws BadLocationException {
        // Find all code blocks in the message
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(message);

        int lastEnd = 0;
        int blockCount = 0;
        
        // Create a default style for regular text
        SimpleAttributeSet defaultStyle = new SimpleAttributeSet();
        // Use the system default font for regular text
        Font defaultFont = UIManager.getFont("TextField.font");
        StyleConstants.setFontFamily(defaultStyle, defaultFont.getFamily());
        StyleConstants.setFontSize(defaultStyle, defaultFont.getSize());
        
        // Process each code block
        while (matcher.find()) {
            blockCount++;

            // Add the text before the code block
            String textBefore = message.substring(lastEnd, matcher.start());
            if (!textBefore.isEmpty()) {
                // Process basic markdown in the text before the code block
                processBasicMarkdown(doc, textBefore);
            }

            // Get the code block content
            String language = matcher.group(1).trim();
            String codeContent = matcher.group(2).trim();
            String codeId = "code_" + System.currentTimeMillis() + "_" + blockCount;

            // Store the code for copying
            codeSnippets.put(codeId, codeContent);

            // Add code block header with language and copy button
            SimpleAttributeSet codeHeaderStyle = new SimpleAttributeSet();
            StyleConstants.setBackground(codeHeaderStyle, new Color(240, 240, 240));
            StyleConstants.setForeground(codeHeaderStyle, Color.GRAY);
            StyleConstants.setFontFamily(codeHeaderStyle, "Monospaced");

            // Insert a newline before the code block
            doc.insertString(doc.getLength(), "\n", defaultStyle);

            // Create a panel for the code block header
            JPanel headerPanel = new JPanel(new BorderLayout());
            headerPanel.setBackground(new Color(240, 240, 240));
            headerPanel.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));

            JLabel languageLabel = new JLabel(language.isEmpty() ? "code" : language);
            languageLabel.setForeground(Color.GRAY);

            JButton copyButton = new JButton("Copy");
            copyButton.setFocusPainted(false);
            copyButton.setBackground(new Color(76, 175, 80));
            copyButton.setForeground(Color.BLACK);
            copyButton.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));

            final String snippetId = codeId;
            copyButton.addActionListener(e -> {
                String codeToCopy = codeSnippets.get(snippetId);
                if (codeToCopy != null) {
                    StringSelection selection = new StringSelection(codeToCopy);
                    Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                    clipboard.setContents(selection, selection);

                    // Change button text temporarily
                    copyButton.setText("Copied!");
                    copyButton.setBackground(new Color(33, 150, 243));
                    copyButton.setForeground(Color.BLACK);

                    // Reset button text after delay
                    Timer timer = new Timer(2000, evt -> {
                        copyButton.setText("Copy");
                        copyButton.setBackground(new Color(76, 175, 80));
                        copyButton.setForeground(Color.BLACK);
                    });
                    timer.setRepeats(false);
                    timer.start();
                }
            });

            headerPanel.add(languageLabel, BorderLayout.WEST);
            headerPanel.add(copyButton, BorderLayout.EAST);

            // Insert the header panel as a component
            int headerPos = doc.getLength();
            doc.insertString(headerPos, " ", defaultStyle); // Placeholder for component

            // Add the component to the document
            StyleConstants.setComponent(codeHeaderStyle, headerPanel);
            doc.setCharacterAttributes(headerPos, 1, codeHeaderStyle, false);

            // Insert the code content with code styling
            SimpleAttributeSet codeStyle = new SimpleAttributeSet();
            StyleConstants.setBackground(codeStyle, new Color(245, 245, 245));
            StyleConstants.setFontFamily(codeStyle, "Monospaced");
            StyleConstants.setFontSize(codeStyle, defaultFont.getSize());

            // Add the code content in a bordered area
            doc.insertString(doc.getLength(), "\n" + codeContent + "\n", codeStyle);

            // Insert a newline after the code block
            // doc.insertString(doc.getLength(), "\n", defaultStyle);

            // Update lastEnd for the next iteration
            lastEnd = matcher.end();
        }

        // Add any remaining text after the last code block
        if (lastEnd < message.length()) {
            String textAfter = message.substring(lastEnd);
            if (!textAfter.isEmpty()) {
                processBasicMarkdown(doc, textAfter);
            }
        }

        // Add a single newline at the end instead of two
        doc.insertString(doc.getLength(), "\n", defaultStyle);
    }

    private void processBasicMarkdown(StyledDocument doc, String text) throws BadLocationException {
        // This is a simple implementation of basic markdown
        // For a more complete solution, you might want to use a proper markdown parser

        // Create a default style for regular text
        SimpleAttributeSet defaultStyle = new SimpleAttributeSet();
        // Use the system default font for regular text
        Font defaultFont = UIManager.getFont("TextField.font");
        StyleConstants.setFontFamily(defaultStyle, defaultFont.getFamily());
        StyleConstants.setFontSize(defaultStyle, defaultFont.getSize());
        // No italic or other special formatting for regular text

        // Process the text line by line
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            // Check for bold text (**text**)
            Matcher boldMatcher = Pattern.compile("\\*\\*(.*?)\\*\\*").matcher(line);
            StringBuffer sb = new StringBuffer();

            while (boldMatcher.find()) {
                String boldText = boldMatcher.group(1);
                boldMatcher.appendReplacement(sb, "");

                // Add the text before the bold part
                doc.insertString(doc.getLength(), sb.toString(), defaultStyle);
                sb.setLength(0);

                // Add the bold text
                SimpleAttributeSet boldStyle = new SimpleAttributeSet();
                StyleConstants.setBold(boldStyle, true);
                // Use the same font family and size as the default font
                StyleConstants.setFontFamily(boldStyle, defaultFont.getFamily());
                StyleConstants.setFontSize(boldStyle, defaultFont.getSize());
                doc.insertString(doc.getLength(), boldText, boldStyle);
            }

            boldMatcher.appendTail(sb);

            // Add any remaining text
            if (sb.length() > 0) {
                doc.insertString(doc.getLength(), sb.toString(), defaultStyle);
            }

            // Add a newline unless it's the last line
            if (i < lines.length - 1) {
                doc.insertString(doc.getLength(), "\n", defaultStyle);
            }
        }
    }

    private void createElementButtons(String message) {
        log.info("Parsing AI response for element suggestions: {}", message);
        
        // Create a panel to hold the buttons
        JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.Y_AXIS));
        buttonPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 1, 0, new Color(200, 200, 200)),
            BorderFactory.createEmptyBorder(10, 5, 10, 5)
        ));
        buttonPanel.setBackground(new Color(245, 245, 245));
        
        // Add a label to the panel
        JLabel titleLabel = new JLabel("Available Elements to Add:");
        titleLabel.setFont(new Font(titleLabel.getFont().getName(), Font.BOLD, 12));
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        buttonPanel.add(titleLabel);
        buttonPanel.add(Box.createVerticalStrut(5));
        
        // Create a panel for the buttons with a GridLayout (2 columns)
        JPanel gridPanel = new JPanel(new GridLayout(0, 2, 5, 5));
        gridPanel.setBackground(new Color(245, 245, 245));
        gridPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        
        // Find all element suggestions in the message
        Matcher matcher = ELEMENT_SUGGESTION_PATTERN.matcher(message);
        boolean foundSuggestions = false;
        Set<String> addedElements = new HashSet<>(); // To avoid duplicate buttons
        int buttonCount = 0; // Counter for the number of buttons added
        final int MAX_BUTTONS = 4; // Maximum number of buttons to display
        
        log.info("Looking for element suggestions in message...");
        while (matcher.find() && buttonCount < MAX_BUTTONS) {
            String elementType = matcher.group(1).trim().toLowerCase();
            String elementName = matcher.group(2);
            
            log.info("Found element suggestion: type={}, name={}", elementType, elementName);
            
            // Skip if we've already added a button for this element type
            if (addedElements.contains(elementType)) {
                log.info("Skipping duplicate element: {}", elementType);
                continue;
            }
            
            // Map the element type to a normalized type
            String normalizedType = mapToNormalizedElementType(elementType);
            if (normalizedType == null) {
                // Skip if we couldn't map the element type
                log.info("Could not map element type to normalized type: {}", elementType);
                continue;
            }
            
            log.info("Mapped element type {} to normalized type {}", elementType, normalizedType);
            
            foundSuggestions = true;
            addedElements.add(elementType);
            
            // Create a button for the element
            JButton addButton = createElementButton(formatElementType(elementType), normalizedType, elementName);
            
            // Add the button to the panel
            gridPanel.add(addButton);
            buttonCount++;
            log.info("Added button for element: {}", elementType);
        }
        
        // If no suggestions were found in the message, create some example buttons for common elements
        if (!foundSuggestions && (message.toLowerCase().contains("test plan") || message.toLowerCase().contains("jmeter"))) {
            log.info("No element suggestions found, creating example buttons");
            
            // Create example buttons for common elements
            createExampleButtons(gridPanel);
            foundSuggestions = true;
        }
        
        // Add the grid panel to the main panel
        buttonPanel.add(gridPanel);
        
        // If we found suggestions, add the button panel to the chat area
        if (foundSuggestions) {
            log.info("Found element suggestions, adding button panel to chat area");
            try {
                StyledDocument doc = chatArea.getStyledDocument();
                
                // Insert a placeholder for the component
                int pos = doc.getLength();
                doc.insertString(pos, " ", new SimpleAttributeSet());
                
                // Create a style for the component
                SimpleAttributeSet componentStyle = new SimpleAttributeSet();
                StyleConstants.setComponent(componentStyle, buttonPanel);
                
                // Apply the style to the placeholder
                doc.setCharacterAttributes(pos, 1, componentStyle, false);
                
                // Add a newline after the buttons
                doc.insertString(doc.getLength(), "\n", new SimpleAttributeSet());
                
                log.info("Successfully added element buttons to the chat area");
            } catch (BadLocationException e) {
                log.error("Error adding element buttons to chat", e);
            }
        } else {
            log.info("No element suggestions found in message");
        }
    }
    
    /**
     * Creates a styled button for a JMeter element
     * 
     * @param displayName The display name for the button
     * @param normalizedType The normalized element type
     * @param elementName The element name (can be null)
     * @return The styled button
     */
    private JButton createElementButton(String displayName, String normalizedType, String elementName) {
        JButton addButton = new JButton("Add " + displayName);
        addButton.setFocusPainted(false);
        addButton.setBackground(new Color(46, 125, 50)); // Darker green for better contrast
        addButton.setForeground(Color.BLACK);
        addButton.setFont(new Font(addButton.getFont().getName(), Font.BOLD, 12));
        addButton.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(27, 94, 32), 1),
            BorderFactory.createEmptyBorder(8, 10, 8, 10)
        ));
        
        // Add action listener to add the element when clicked
        addButton.addActionListener(e -> {
            // Use the JMeterElementRequestHandler to add the element
            String result = org.qainsights.jmeter.ai.utils.JMeterElementRequestHandler.processElementRequest(
                    "add " + normalizedType + (elementName != null ? " called " + elementName : ""));
            
            if (result != null) {
                // Display the result in the chat area
                appendToChat("System: " + result, new Color(0, 100, 0), false);
            }
        });
        
        return addButton;
    }
    
    /**
     * Suggests related elements after adding an element
     * 
     * @param addedElementType The type of element that was added
     */
    private void suggestRelatedElements(String addedElementType) {
        // Create a panel to hold the buttons
        JPanel suggestionPanel = new JPanel();
        suggestionPanel.setLayout(new BoxLayout(suggestionPanel, BoxLayout.Y_AXIS));
        suggestionPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 1, 0, new Color(200, 200, 200)),
            BorderFactory.createEmptyBorder(10, 5, 10, 5)
        ));
        suggestionPanel.setBackground(new Color(240, 248, 255)); // Light blue background
        
        // Add a label to the panel
        JLabel titleLabel = new JLabel("Suggested Elements to Add Next:");
        titleLabel.setFont(new Font(titleLabel.getFont().getName(), Font.BOLD, 12));
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        suggestionPanel.add(titleLabel);
        suggestionPanel.add(Box.createVerticalStrut(5));
        
        // Create a panel for the buttons with a GridLayout (2 columns)
        JPanel gridPanel = new JPanel(new GridLayout(0, 2, 5, 5));
        gridPanel.setBackground(new Color(240, 248, 255));
        gridPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        
        // Get related elements based on the added element type
        String[][] relatedElements = getRelatedElements(addedElementType);
        
        if (relatedElements.length > 0) {
            for (String[] element : relatedElements) {
                String displayName = element[0];
                String normalizedType = element[1];
                
                // Create a button for the element (with a different style)
                JButton addButton = new JButton("Add " + displayName);
                addButton.setFocusPainted(false);
                addButton.setBackground(new Color(33, 150, 243)); // Blue for suggested elements
                addButton.setForeground(Color.WHITE);
                addButton.setFont(new Font(addButton.getFont().getName(), Font.BOLD, 12));
                addButton.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(25, 118, 210), 1),
                    BorderFactory.createEmptyBorder(8, 10, 8, 10)
                ));
                
                // Add action listener to add the element when clicked
                final String finalNormalizedType = normalizedType;
                addButton.addActionListener(e -> {
                    // Use the JMeterElementRequestHandler to add the element
                    String result = org.qainsights.jmeter.ai.utils.JMeterElementRequestHandler.processElementRequest(
                            "add " + finalNormalizedType);
                    
                    if (result != null) {
                        // Display the result in the chat area
                        appendToChat("System: " + result, new Color(0, 100, 0), false);
                    }
                });
                
                // Add the button to the panel
                gridPanel.add(addButton);
            }
            
            // Add the grid panel to the main panel
            suggestionPanel.add(gridPanel);
            
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
                
                // Add a newline after the buttons
                doc.insertString(doc.getLength(), "\n", new SimpleAttributeSet());
                
                log.info("Added suggested elements after adding {}", addedElementType);
            } catch (BadLocationException e) {
                log.error("Error adding suggested elements to chat", e);
            }
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
                    {"Duration Assertion", "durationassertion"}
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
        // Common JMeter elements - limit to 4 buttons (2 rows of 2 buttons)
        String[][] elements = {
            {"Thread Group", "threadgroup"},
            {"HTTP Sampler", "httpsampler"},
            {"CSV Data Set", "csvdataset"},
            {"Loop Controller", "loopcontroller"}
        };
        
        for (String[] element : elements) {
            String displayName = element[0];
            String normalizedType = element[1];
            
            // Create a button for the element
            JButton addButton = createElementButton(displayName, normalizedType, null);
            
            // Add the button to the panel
            panel.add(addButton);
            log.info("Added example button for element: {}", displayName);
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
}