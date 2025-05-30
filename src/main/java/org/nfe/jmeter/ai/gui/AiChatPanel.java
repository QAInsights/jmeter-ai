package org.nfe.jmeter.ai.gui;

import javax.swing.*;
import javax.swing.text.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

// import com.openai.models.Model; // OpenAI SDK removed
import org.apache.jorphan.gui.JMeterUIDefaults;
import org.nfe.jmeter.ai.intellisense.InputBoxIntellisense;
import org.nfe.jmeter.ai.lint.LintCommandHandler;
import org.nfe.jmeter.ai.optimizer.OptimizeRequestHandler;
import org.nfe.jmeter.ai.service.AiService;
import org.nfe.jmeter.ai.service.ClaudeService;
import org.nfe.jmeter.ai.service.OpenAiService;
import org.nfe.jmeter.ai.usage.UsageCommandHandler;
import org.nfe.jmeter.ai.utils.JMeterElementManager;
import org.nfe.jmeter.ai.utils.JMeterElementRequestHandler;
import org.nfe.jmeter.ai.utils.Models;
import org.nfe.jmeter.ai.utils.VersionUtils;
import org.nfe.jmeter.ai.wrap.WrapCommandHandler;
import org.nfe.jmeter.ai.wrap.WrapUndoRedoHandler;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;

import com.anthropic.models.ModelInfo;
import com.anthropic.models.ModelListPage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Panel for interacting with AI to generate and modify JMeter test plans.
 * This class has been refactored to improve composability, readability, and
 * reusability
 * by delegating responsibilities to specialized component classes.
 */
public class AiChatPanel extends JPanel implements PropertyChangeListener {
    private static final Logger log = LoggerFactory.getLogger(AiChatPanel.class);

    // UI components (kept for backward compatibility)
    private JTextPane chatArea;
    private JTextArea messageField;
    private JButton sendButton;
    private JComboBox<Models.ModelDetail> modelSelector; // Changed to use ModelDetail
    private List<String> conversationHistory;
    private ClaudeService claudeService; // Retained if panel needs to support both services
    private OpenAiService openAiService;
    private TreeNavigationButtons treeNavigationButtons;
    private JPanel navigationPanel; // Added field for navigation panel

    // Store the base font sizes for scaling
    private float baseChatFontSize;
    private float baseMessageFontSize;

    // Component managers
    private final MessageProcessor messageProcessor;
    private final ElementSuggestionManager elementSuggestionManager;

    // Track the last command type for undo/redo operations
    private enum LastCommandType {
        NONE,
        LINT,
        WRAP
    }

    private LastCommandType lastCommandType = LastCommandType.NONE;

    /**
     * Constructs a new AiChatPanel.
     */
    public AiChatPanel() {
        // Initialize services and utilities
        claudeService = new ClaudeService();
        openAiService = new OpenAiService();
        messageProcessor = new MessageProcessor();

        // Initialize tree navigation buttons with action listeners
        treeNavigationButtons = new TreeNavigationButtons();
        treeNavigationButtons.setUpButtonActionListener();
        treeNavigationButtons.setDownButtonActionListener();

        // Register for UI refresh events (for zoom functionality)
        UIManager.addPropertyChangeListener(this);

        conversationHistory = new ArrayList<>();

        // Set up the panel layout
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(500, 600));
        setMinimumSize(new Dimension(350, 400));
        setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));

        // Initialize model selector with loading state
        // Initialize model selector for Lab45 AI models
        modelSelector = new JComboBox<>();
        // DefaultListCellRenderer will use ModelDetail.toString() (displayName)
        // No custom renderer needed if ModelDetail.toString() is sufficient for
        // display.

        loadLab45ModelsInBackground(); // Changed from loadModelsInBackground

        // Add a listener to set model and tokens on the openAiService (Lab45 service)
        modelSelector.addActionListener(e -> {
            Object selectedItem = modelSelector.getSelectedItem();
            if (selectedItem instanceof Models.ModelDetail) {
                Models.ModelDetail selectedModelDetail = (Models.ModelDetail) selectedItem;
                log.info("Lab45 Model selected: DisplayName='{}', ID='{}', MaxTokens={}",
                        selectedModelDetail.displayName, selectedModelDetail.modelId, selectedModelDetail.maxTokens);
                openAiService.setCurrentModelAndTokens(selectedModelDetail.modelId, selectedModelDetail.maxTokens);
            }
            // Note: ClaudeService model selection would need a separate mechanism if still
            // supported by this panel.
            // For this refactoring, modelSelector is now dedicated to Lab45 models via
            // openAiService.
        });

        // Create a panel for the chat area with header
        JPanel chatPanel = new JPanel(new BorderLayout());
        chatPanel.setBorder(BorderFactory.createMatteBorder(0, 1, 1, 1, Color.LIGHT_GRAY));

        // Create a header panel for the title and new chat button
        JPanel headerPanel = createHeaderPanel();
        chatPanel.add(headerPanel, BorderLayout.NORTH);

        // Initialize chat area
        chatArea = new JTextPane();
        chatArea.setEditable(false);
        // Use the system default font with larger size
        Font defaultFont = UIManager.getFont("TextField.font");
        Font largerFont = new Font(defaultFont.getFamily(), defaultFont.getStyle(), defaultFont.getSize() + 2);
        chatArea.setFont(largerFont);

        // Store the base font size for scaling
        baseChatFontSize = largerFont.getSize2D();

        // Set default paragraph attributes for left alignment
        StyledDocument doc = chatArea.getStyledDocument();
        SimpleAttributeSet leftAlign = new SimpleAttributeSet();
        StyleConstants.setAlignment(leftAlign, StyleConstants.ALIGN_LEFT);
        doc.setParagraphAttributes(0, doc.getLength(), leftAlign, false);

        // Add keyboard shortcut for undo (Cmd+Z on Mac, Ctrl+Z on Windows/Linux)
        InputMap inputMap = chatArea.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actionMap = chatArea.getActionMap();

        // Define the key stroke based on the platform - using modern API instead of
        // deprecated Event.META_MASK
        KeyStroke undoKeyStroke;
        KeyStroke redoKeyStroke;
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("mac")) {
            // Mac (Cmd+Z for undo, Cmd+Shift+Z for redo)
            undoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.META_DOWN_MASK);
            redoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z,
                    InputEvent.META_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
        } else if (osName.contains("linux")) {
            // Linux (Ctrl+Z for undo, Ctrl+Shift+Z for redo)
            undoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK);
            redoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z,
                    InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
        } else {
            // Windows (Ctrl+Z for undo, Ctrl+Shift+Z for redo)
            undoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK);
            redoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z,
                    InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
        }

        inputMap.put(undoKeyStroke, "undoAction");
        actionMap.put("undoAction", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // Perform undo based on the last command type
                switch (lastCommandType) {
                    case WRAP:
                        undoLastWrap();
                        break;
                    case LINT:
                        undoLastRename();
                        break;
                    default:
                        // If no recent command, try to determine based on context
                        GuiPackage guiPackage = GuiPackage.getInstance();
                        if (guiPackage != null) {
                            JMeterTreeNode currentNode = guiPackage.getTreeListener().getCurrentNode();
                            if (currentNode != null && currentNode.getTestElement() instanceof TransactionController) {
                                undoLastWrap();
                            } else {
                                undoLastRename();
                            }
                        }
                        break;
                }
            }
        });

        // Add keyboard shortcut for redo
        inputMap.put(redoKeyStroke, "redoAction");
        actionMap.put("redoAction", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // Perform redo based on the last command type
                switch (lastCommandType) {
                    case WRAP:
                        // Wrap operations don't support redo due to complexity of recreating
                        // Transaction Controllers
                        try {
                            messageProcessor.appendMessage(chatArea.getStyledDocument(),
                                    "Redo is not supported for wrap operations. Please use the @wrap command again if needed.",
                                    Color.BLUE, false);
                        } catch (BadLocationException ex) {
                            log.error("Error displaying message", ex);
                        }
                        break;
                    case LINT:
                        redoLastUndo();
                        break;
                    default:
                        // If no recent command, try to determine based on context
                        GuiPackage guiPackage = GuiPackage.getInstance();
                        if (guiPackage != null) {
                            JMeterTreeNode currentNode = guiPackage.getTreeListener().getCurrentNode();
                            if (currentNode != null && currentNode.getTestElement() instanceof TransactionController) {
                                // If a Transaction Controller is selected, inform user that redo is not
                                // supported
                                try {
                                    messageProcessor.appendMessage(chatArea.getStyledDocument(),
                                            "Redo is not supported for wrap operations. Please use the @wrap command again if needed.",
                                            Color.BLUE, false);
                                } catch (BadLocationException ex) {
                                    log.error("Error displaying message", ex);
                                }
                            } else {
                                redoLastUndo();
                            }
                        }
                        break;
                }
            }
        });

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

        // Create the navigation panel for tree navigation and element buttons
        navigationPanel = new JPanel();
        navigationPanel.setLayout(new FlowLayout(FlowLayout.LEFT, 5, 5));
        navigationPanel.setBorder(BorderFactory.createTitledBorder("Element Suggestions"));
        navigationPanel.setBackground(new Color(245, 245, 250)); // Light background to make it stand out

        // Add navigation buttons to the panel
        navigationPanel.add(treeNavigationButtons.getUpButton());
        navigationPanel.add(treeNavigationButtons.getDownButton());

        // Add a separator
        JSeparator separator = new JSeparator(SwingConstants.VERTICAL);
        separator.setPreferredSize(new Dimension(1, 30));
        navigationPanel.add(separator);

        // Set minimum height to ensure buttons are visible
        navigationPanel.setMinimumSize(new Dimension(100, 70));
        navigationPanel.setPreferredSize(new Dimension(500, 70));

        // Initialize element suggestion manager with the navigation panel
        elementSuggestionManager = new ElementSuggestionManager(navigationPanel);

        // Make sure the navigation panel is visible
        navigationPanel.setVisible(true);

        bottomPanel.add(navigationPanel, BorderLayout.CENTER);

        // Create the input panel with message field and send button
        JPanel inputPanel = new JPanel(new BorderLayout(5, 0));

        // Initialize message field
        messageField = new JTextArea(3, 20);
        messageField.setLineWrap(true);
        messageField.setWrapStyleWord(true);
        messageField.setFont(largerFont);

        // Store the base font size for scaling
        baseMessageFontSize = largerFont.getSize2D();
        messageField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.LIGHT_GRAY),
                BorderFactory.createEmptyBorder(5, 5, 5, 5)));

        // Setup intellisense for command suggestions
        new InputBoxIntellisense(messageField);

        // Add key listener for Enter to send message
        messageField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && !e.isShiftDown()) {
                    e.consume(); // Prevent newline from being added
                    sendMessage();
                }
            }
        });

        // Add focus listener to store selected text when clicking in the chat box
        // messageField.addFocusListener(new FocusAdapter() {
        // @Override
        // public void focusGained(FocusEvent e) {
        // log.info("Message field gained focus, storing selected text");
        // CodeCommandHandler.storeSelectedText();
        // }
        // });

        JScrollPane messageScrollPane = new JScrollPane(messageField);
        messageScrollPane.setBorder(BorderFactory.createEmptyBorder());
        inputPanel.add(messageScrollPane, BorderLayout.CENTER);

        // Initialize send button
        sendButton = new JButton("Send");
        sendButton.setFont(new Font(sendButton.getFont().getName(), Font.BOLD, 12));
        sendButton.setFocusPainted(false);
        sendButton.addActionListener(e -> sendMessage());
        inputPanel.add(sendButton, BorderLayout.EAST);

        bottomPanel.add(inputPanel, BorderLayout.SOUTH);

        // Add the bottom panel to the main panel
        add(bottomPanel, BorderLayout.SOUTH);

        // Display welcome message
        displayWelcomeMessage();
    }

    /**
     * Creates the header panel with title and new chat button.
     * 
     * @return The header panel
     */
    private JPanel createHeaderPanel() {
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 1, 0, Color.LIGHT_GRAY),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        headerPanel.setBackground(new Color(240, 240, 240));

        // Add a title to the left side of the header panel
        JLabel titleLabel = new JLabel("Lab45 NFE - JMeter Agent v" + VersionUtils.getVersion());
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
                BorderFactory.createEmptyBorder(2, 8, 2, 8)));

        // Add action listener to reset the conversation
        newChatButton.addActionListener(e -> startNewConversation());

        // Add the button to the right side of the header panel
        headerPanel.add(newChatButton, BorderLayout.EAST);

        return headerPanel;
    }

    /**
     * Loads the available Lab45 models in the background and populates the
     * modelSelector.
     */
    private void loadLab45ModelsInBackground() {
        modelSelector.setEnabled(false); // Disable while loading
        // Set a temporary message for loading
        ComboBoxModel<Models.ModelDetail> tempModel = new DefaultComboBoxModel<>();
        // tempModel.addElement(null); // Cannot add null to DefaultComboBoxModel
        // directly
        modelSelector.setModel(tempModel); // Set an empty model first
        modelSelector.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected,
                    boolean cellHasFocus) {
                if (list.getModel().getSize() == 0) { // Check if the model is empty (during loading)
                    return super.getListCellRendererComponent(list, "Loading models...", index, isSelected,
                            cellHasFocus);
                }
                // Once items are loaded, ModelDetail.toString() will be used.
                return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            }
        });

        new SwingWorker<List<Models.ModelDetail>, Void>() {
            @Override
            protected List<Models.ModelDetail> doInBackground() {
                log.info("Loading Lab45 models...");
                return Models.getLab45ModelDetails();
            }

            @Override
            protected void done() {
                try {
                    List<Models.ModelDetail> modelDetails = get();
                    DefaultComboBoxModel<Models.ModelDetail> comboBoxModel = new DefaultComboBoxModel<>();
                    if (modelDetails != null && !modelDetails.isEmpty()) {
                        for (Models.ModelDetail detail : modelDetails) {
                            comboBoxModel.addElement(detail);
                        }
                        modelSelector.setModel(comboBoxModel);

                        Models.ModelDetail defaultModel = Models.getDefaultLab45ModelDetail();
                        if (defaultModel != null) {
                            modelSelector.setSelectedItem(defaultModel);
                            // Set model and tokens in the service when default is selected
                            openAiService.setCurrentModelAndTokens(defaultModel.modelId, defaultModel.maxTokens);
                            log.info("Default Lab45 model set: {}", defaultModel.displayName);
                        } else if (comboBoxModel.getSize() > 0) { // Fallback to first if default not found
                            modelSelector.setSelectedIndex(0);
                            Models.ModelDetail selected = (Models.ModelDetail) modelSelector.getSelectedItem();
                            if (selected != null) {
                                openAiService.setCurrentModelAndTokens(selected.modelId, selected.maxTokens);
                            }
                        }
                        log.info("Lab45 models loaded. Count: {}", modelDetails.size());
                    } else {
                        log.warn("No Lab45 models were loaded.");
                        modelSelector.setModel(new DefaultComboBoxModel<>()); // Empty model
                        modelSelector.setRenderer(new DefaultListCellRenderer() {
                            @Override
                            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                    boolean isSelected, boolean cellHasFocus) {
                                return super.getListCellRendererComponent(list, "No models available", index,
                                        isSelected, cellHasFocus);
                            }
                        });
                    }
                } catch (InterruptedException | ExecutionException e) {
                    log.error("Failed to load Lab45 models into JComboBox", e);
                    modelSelector.setModel(new DefaultComboBoxModel<>()); // Empty model
                    modelSelector.setRenderer(new DefaultListCellRenderer() {
                        @Override
                        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                boolean isSelected, boolean cellHasFocus) {
                            return super.getListCellRendererComponent(list, "Error loading models", index, isSelected,
                                    cellHasFocus);
                        }
                    });
                } finally {
                    modelSelector.setEnabled(true);
                }
            }
        }.execute();
    }

    /**
     * Displays a welcome message in the chat area.
     */
    private void displayWelcomeMessage() {
        log.info("Displaying welcome message");

        String welcomeMessage = "# Welcome to lab45 NFE  - JMeter Agent\n\n" +
                "I'm here to help you with your JMeter test plan. You can ask me questions about JMeter, " +
                "request help with creating test elements, or get advice on optimizing your tests.\n\n" +
                "**Special commands:**\n" +
                "- Use `@this` to get information about the currently selected element\n" +
                "- Use `@optimize` to get optimization suggestions for your test plan\n" +
                "- Use `@lint` to rename elements in your test plan with meaningful names\n" +
                "- Use `@wrap` to group HTTP request samplers under Transaction Controllers\n" +
                "- Use `@usage` to view usage statistics for your AI interactions\n\n" +
                "How can I assist you today?";

        try {
            messageProcessor.appendMessage(chatArea.getStyledDocument(), welcomeMessage, new Color(0, 51, 102), true);
        } catch (BadLocationException e) {
            log.error("Error displaying welcome message", e);
        }
    }

    /**
     * Starts a new conversation by clearing the chat area and conversation history.
     */
    private void startNewConversation() {
        log.info("Starting new conversation");

        // Clear the chat area
        chatArea.setText("");

        // Clear the conversation history
        conversationHistory.clear();

        // Reset the last command type
        lastCommandType = LastCommandType.NONE;

        // Display welcome message
        displayWelcomeMessage();
    }

    /**
     * Sends the message from the input field to the chat.
     */
    private void sendMessage() {
        String message = messageField.getText().trim();
        if (message.isEmpty()) {
            return;
        }

        log.info("Sending user message: {}", message);

        // Add the user message to the chat
        try {
            messageProcessor.appendMessage(chatArea.getStyledDocument(), "You: " + message, Color.BLACK, false);
        } catch (BadLocationException e) {
            log.error("Error appending user message to chat", e);
        }

        // Add the user message to the conversation history
        conversationHistory.add(message);

        // Clear the message field
        messageField.setText("");

        // Add "AI is thinking..." indicator
        try {
            messageProcessor.appendMessage(chatArea.getStyledDocument(), "AI is thinking...", Color.GRAY, false);
        } catch (BadLocationException e) {
            log.error("Error adding loading indicator", e);
        }

        // Check for special commands
        if (message.trim().startsWith("@this")) {
            handleThisCommand();
            return;
        } else if (message.trim().startsWith("@optimize")) {
            handleOptimizeCommand();
            return;
        } else if (message.trim().startsWith("@code")) {
            // @code command is disabled - use right-click context menu instead
            try {
                messageProcessor.appendMessage(chatArea.getStyledDocument(),
                        "The @code command is disabled. Please use the right-click context menu in the JSR223 editor instead.",
                        Color.RED, false);

                // Re-enable input
                messageField.setEnabled(true);
                sendButton.setEnabled(true);
                messageField.requestFocusInWindow();
            } catch (BadLocationException e) {
                log.error("Error displaying message", e);
            }
            return;
        } else if (message.trim().startsWith("@lint")) {
            handleLintCommand(message);
            return;
        } else if (message.trim().startsWith("@wrap")) {
            handleWrapCommand();
            return;
        } else if (message.trim().startsWith("@usage")) {
            handleUsageCommand();
            return;
        }

        log.info("Checking if message is an element request: '{}'", message);

        // Disable input while processing
        messageField.setEnabled(false);
        sendButton.setEnabled(false);

        String elementResponse = JMeterElementRequestHandler.processElementRequest(message);

        // Only process as an element request if it's a valid request
        // This prevents general conversation from being interpreted as element requests
        if (elementResponse != null && !elementResponse.contains("I couldn't understand what to do with")) {
            log.info("Detected element request, response: '{}'",
                    elementResponse.length() > 50 ? elementResponse.substring(0, 50) + "..." : elementResponse);

            // Remove the loading indicator since we're about to display the response
            removeLoadingIndicator();
            processAiResponse(elementResponse);

            // Re-enable input after processing
            messageField.setEnabled(true);
            sendButton.setEnabled(true);
            messageField.requestFocusInWindow();

            return;
        }

        log.info("Message not recognized as an element request, processing as regular AI request");

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
                    try {
                        messageProcessor.appendMessage(chatArea.getStyledDocument(),
                                "Sorry, I encountered an error while processing your request. Please try again.",
                                Color.RED, false);
                    } catch (BadLocationException ex) {
                        log.error("Error displaying error message", ex);
                    }

                    // Re-enable input
                    messageField.setEnabled(true);
                    sendButton.setEnabled(true);
                    messageField.requestFocusInWindow();
                }
            }
        }.execute();
    }

    /**
     * Handles the @this command to get information about the currently selected
     * element.
     */
    private void handleThisCommand() {
        log.info("Processing @this command");

        // Reset the last command type since this is not a lint or wrap command
        lastCommandType = LastCommandType.NONE;

        // Disable input while processing
        messageField.setText("");
        messageField.setEnabled(false);
        sendButton.setEnabled(false);

        // Process the command in a background thread
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                String elementInfo = getCurrentElementInfo();
                if (elementInfo == null) {
                    return "No element is currently selected in the test plan. Please select an element and try again.";
                }
                return elementInfo;
            }

            @Override
            protected void done() {
                try {
                    // Remove the loading indicator
                    removeLoadingIndicator();

                    // Get the element info
                    String info = get();

                    // Process the response
                    processAiResponse(info);

                    // Re-enable input
                    messageField.setEnabled(true);
                    sendButton.setEnabled(true);
                    messageField.requestFocusInWindow();
                } catch (InterruptedException | ExecutionException e) {
                    log.error("Error getting element info", e);

                    // Remove the loading indicator
                    removeLoadingIndicator();

                    // Display error message
                    try {
                        messageProcessor.appendMessage(chatArea.getStyledDocument(),
                                "Sorry, I encountered an error while getting element information. Please try again.",
                                Color.RED, false);
                    } catch (BadLocationException ex) {
                        log.error("Error displaying error message", ex);
                    }

                    // Re-enable input
                    messageField.setEnabled(true);
                    sendButton.setEnabled(true);
                    messageField.requestFocusInWindow();
                }
            }
        }.execute();
    }

    /**
     * Handles the @optimize command to get optimization suggestions for the test
     * plan.
     */
    private void handleOptimizeCommand() {
        log.info("Processing @optimize command");

        // Reset the last command type since this is not a lint or wrap command
        lastCommandType = LastCommandType.NONE;

        // Disable input while processing
        messageField.setText("");
        messageField.setEnabled(false);
        sendButton.setEnabled(false);

        // Process the command in a background thread
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                // Get optimization suggestions from OptimizeRequestHandler
                // Use the appropriate AI service based on the selected model
                Object selectedItem = modelSelector.getSelectedItem();
                AiService serviceToUse;
                String modelIdentifierForLog = "N/A";

                // Assuming this command primarily uses the Lab45 service (openAiService)
                if (selectedItem instanceof Models.ModelDetail) {
                    Models.ModelDetail selectedModelDetail = (Models.ModelDetail) selectedItem;
                    serviceToUse = openAiService; // Lab45 service
                    // Ensure model and tokens are set before use
                    openAiService.setCurrentModelAndTokens(selectedModelDetail.modelId, selectedModelDetail.maxTokens);
                    modelIdentifierForLog = selectedModelDetail.displayName;
                    log.info("Using Lab45 service for optimization with model: {}", modelIdentifierForLog);
                } else {
                    // Fallback or error if no valid Lab45 model selected
                    log.warn("No Lab45 model selected for optimization. Using default Lab45 model.");
                    Models.ModelDetail defaultModel = Models.getDefaultLab45ModelDetail();
                    if (defaultModel != null) {
                        openAiService.setCurrentModelAndTokens(defaultModel.modelId, defaultModel.maxTokens);
                        modelIdentifierForLog = defaultModel.displayName;
                    } else {
                        log.error("Default Lab45 model is null. Cannot proceed with optimization.");
                        return "Error: Default model not configured for Lab45 service.";
                    }
                    serviceToUse = openAiService;
                }
                // If ClaudeService were to be used, similar logic for its model selection would
                // be needed.
                // For now, this command seems to target the service associated with
                // modelSelector.

                return OptimizeRequestHandler.analyzeAndOptimizeSelectedElement(serviceToUse);
            }

            @Override
            protected void done() {
                try {
                    // Remove the loading indicator
                    removeLoadingIndicator();

                    // Get the optimization suggestions
                    String suggestions = get();

                    // Process the response
                    processAiResponse(suggestions);

                    // Re-enable input
                    messageField.setEnabled(true);
                    sendButton.setEnabled(true);
                    messageField.requestFocusInWindow();
                } catch (InterruptedException | ExecutionException e) {
                    log.error("Error getting optimization suggestions", e);

                    // Remove the loading indicator
                    removeLoadingIndicator();

                    // Display error message
                    try {
                        messageProcessor.appendMessage(chatArea.getStyledDocument(),
                                "Sorry, I encountered an error while getting optimization suggestions. Please try again.",
                                Color.RED, false);
                    } catch (BadLocationException ex) {
                        log.error("Error displaying error message", ex);
                    }

                    // Re-enable input
                    messageField.setEnabled(true);
                    sendButton.setEnabled(true);
                    messageField.requestFocusInWindow();
                }
            }
        }.execute();
    }

    /**
     * Gets information about the currently selected element.
     * 
     * @return Information about the currently selected element, or null if no
     *         element is selected
     */
    public String getCurrentElementInfo() {
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
            info.append("# ").append(currentNode.getName()).append(" (").append(element.getClass().getSimpleName())
                    .append(")\n\n");

            // Add description based on element type
            String elementType = element.getClass().getSimpleName();
            info.append(JMeterElementManager.getElementDescription(elementType)).append("\n\n");

            // Add properties
            info.append("## Properties\n\n");

            // Get all property names
            PropertyIterator propertyIterator = element.propertyIterator();
            while (propertyIterator.hasNext()) {
                JMeterProperty property = propertyIterator.next();
                String propertyName = property.getName();
                String propertyValue = property.getStringValue();

                // Skip empty properties and internal JMeter properties
                if (!propertyValue.isEmpty() && !propertyName.startsWith("TestElement.")
                        && !propertyName.equals("guiclass")) {
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
                info.append("- Parent: **").append(parentNode.getName()).append("** (")
                        .append(parentNode.getTestElement().getClass().getSimpleName()).append(")\n");
            }

            // Get child nodes
            if (currentNode.getChildCount() > 0) {
                info.append("- Children: ").append(currentNode.getChildCount()).append("\n");
                for (int i = 0; i < currentNode.getChildCount(); i++) {
                    JMeterTreeNode childNode = (JMeterTreeNode) currentNode.getChildAt(i);
                    info.append("  - **").append(childNode.getName()).append("** (")
                            .append(childNode.getTestElement().getClass().getSimpleName()).append(")\n");
                }
            } else {
                info.append("- No children\n");
            }

            // Add suggestions for what can be added to this element
            info.append("\n## Suggested Elements\n\n");
            String[][] suggestions = getContextAwareSuggestions(currentNode.getStaticLabel());
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
     * Gets context-aware element suggestions based on the node type.
     * 
     * @param nodeType The type of node to get suggestions for
     * @return An array of string arrays, each containing [displayName]
     */
    private String[][] getContextAwareSuggestions(String nodeType) {
        // Convert to lowercase for case-insensitive comparison
        String type = nodeType.toLowerCase();

        // Return suggestions based on the node type
        if (type.contains("test plan")) {
            return new String[][] {
                    { "Thread Group" },
                    { "HTTP Cookie Manager" },
                    { "HTTP Header Manager" }
            };
        } else if (type.contains("thread group")) {
            return new String[][] {
                    { "HTTP Request" },
                    { "Loop Controller" },
                    { "If Controller" }
            };
        } else if (type.contains("http request")) {
            return new String[][] {
                    { "Response Assertion" },
                    { "JSON Extractor" },
                    { "Constant Timer" }
            };
        } else if (type.contains("controller")) {
            return new String[][] {
                    { "HTTP Request" },
                    { "Debug Sampler" },
                    { "JSR223 Sampler" }
            };
        } else {
            // Default suggestions
            return new String[][] {
                    { "Thread Group" },
                    { "HTTP Request" },
                    { "Response Assertion" }
            };
        }
    }

    /**
     * Removes the loading indicator from the chat area.
     */
    private void removeLoadingIndicator() {
        log.info("Attempting to remove loading indicator");
        try {
            StyledDocument doc = chatArea.getStyledDocument();

            // Find the loading indicator text
            String text = doc.getText(0, doc.getLength());
            int index = text.lastIndexOf("AI is thinking...");

            log.info("Loading indicator found at index: {}", index);

            if (index != -1) {
                // Remove the loading indicator
                doc.remove(index, "AI is thinking...".length());
                log.info("Loading indicator removed");
            } else {
                log.warn("Loading indicator not found in chat text");
            }
        } catch (BadLocationException e) {
            log.error("Error removing loading indicator", e);
        }
    }

    /**
     * Processes an AI response and displays it in the chat area.
     * 
     * @param response The AI response to process
     */
    private void processAiResponse(String response) {
        if (response == null || response.isEmpty()) {
            try {
                messageProcessor.appendMessage(chatArea.getStyledDocument(),
                        "No response from AI. Please try again.", Color.RED, false);
            } catch (BadLocationException e) {
                log.error("Error displaying error message", e);
            }
            log.warn("Empty AI response");
            return;
        }

        log.info("Processing AI response: {}", response.substring(0, Math.min(100, response.length())));

        // Add the AI response to the chat
        log.info("Appending AI response to chat");
        try {
            messageProcessor.appendMessage(chatArea.getStyledDocument(), response, new Color(0, 51, 102), true);
        } catch (BadLocationException e) {
            log.error("Error appending AI response to chat", e);
        }

        // Create element buttons for context-aware suggestions after the AI response
        SwingUtilities.invokeLater(() -> {
            log.info("Creating element buttons for context-aware suggestions");

            // Make sure the navigation panel is visible
            navigationPanel.setVisible(true);

            // Process the response to create element buttons
            elementSuggestionManager.createElementButtons(response);

            // Ensure the navigation panel is visible and properly laid out
            navigationPanel.revalidate();
            navigationPanel.repaint();

            // Log the number of components in the navigation panel
            log.info("Navigation panel now has {} components", navigationPanel.getComponentCount());

            // Scroll to the bottom of the chat area to show the latest message
            SwingUtilities.invokeLater(() -> {
                JScrollPane scrollPane = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, chatArea);
                if (scrollPane != null) {
                    JScrollBar vertical = scrollPane.getVerticalScrollBar();
                    vertical.setValue(vertical.getMaximum());
                }
            });
        });
    }

    private void handleUsageCommand() {
        log.info("Processing @usage command");
        // Get the currently selected model from the dropdown
        String selectedModel = (String) modelSelector.getSelectedItem();

        messageField.setText("");
        messageField.setEnabled(false);
        sendButton.setEnabled(false);

        // Determine which service to use based on the model ID
        AiService serviceToUse = null;
        if (selectedModel != null && selectedModel.startsWith("openai:")) {
            serviceToUse = openAiService;
        }
        if (selectedModel != null && selectedModel.startsWith("claude")) {
            serviceToUse = claudeService;
        }
        AiService finalServiceToUse = serviceToUse;
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                UsageCommandHandler usageCommandHandler = new UsageCommandHandler();
                return usageCommandHandler.processUsageCommand(finalServiceToUse);
            }

            @Override
            protected void done() {
                try {
                    removeLoadingIndicator();
                    processAiResponse(get());
                    messageField.setEnabled(true);
                    sendButton.setEnabled(true);
                    messageField.requestFocusInWindow();
                } catch (InterruptedException | ExecutionException e) {
                    log.error("Error processing usage command", e);
                    removeLoadingIndicator();
                    try {
                        messageProcessor.appendMessage(chatArea.getStyledDocument(),
                                "Sorry, I encountered an error while processing the usage command. Please try again.",
                                Color.RED, false);
                    } catch (BadLocationException ex) {
                        log.error("Error displaying error message", ex);
                    }
                    messageField.setEnabled(true);
                    sendButton.setEnabled(true);
                    messageField.requestFocusInWindow();
                }
            }
        }.execute();
    }

    /**
     * Handles the @lint command to rename elements in the test plan.
     * 
     * @param message The message containing the @lint command
     */
    /**
     * Handles the @wrap command to group HTTP request samplers under Transaction
     * Controllers.
     */
    private void handleWrapCommand() {
        log.info("Processing @wrap command");

        // Set the last command type to WRAP
        lastCommandType = LastCommandType.WRAP;

        // Disable input while processing
        messageField.setText("");
        messageField.setEnabled(false);
        sendButton.setEnabled(false);

        // Process the command in a background thread
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                WrapCommandHandler wrapCommandHandler = new WrapCommandHandler();
                return wrapCommandHandler.processWrapCommand();
            }

            @Override
            protected void done() {
                try {
                    // Remove the loading indicator
                    removeLoadingIndicator();

                    // Get the wrap result
                    String result = get();

                    // Process the response
                    processAiResponse(result);

                    // Re-enable input
                    messageField.setEnabled(true);
                    sendButton.setEnabled(true);
                    messageField.requestFocusInWindow();
                } catch (InterruptedException | ExecutionException e) {
                    log.error("Error processing @wrap command", e);

                    // Remove the loading indicator
                    removeLoadingIndicator();

                    // Display error message
                    try {
                        messageProcessor.appendMessage(chatArea.getStyledDocument(),
                                "Sorry, I encountered an error while processing the @wrap command. Please try again.",
                                Color.RED, false);
                    } catch (BadLocationException ex) {
                        log.error("Error displaying error message", ex);
                    }

                    // Re-enable input
                    messageField.setEnabled(true);
                    sendButton.setEnabled(true);
                    messageField.requestFocusInWindow();
                }
            }
        }.execute();
    }

    /**
     * Handles the @lint command to rename elements in the test plan.
     * 
     * @param message The message containing the @lint command
     */
    private void handleLintCommand(String message) {
        log.info("Processing @lint command");

        // Set the last command type to LINT
        lastCommandType = LastCommandType.LINT;

        try {
            // Add user message to chat
            messageProcessor.appendMessage(chatArea.getStyledDocument(), message, Color.BLACK, true);
        } catch (BadLocationException e) {
            log.error("Error adding message to chat", e);
        }

        // Disable input while processing
        messageField.setText("");
        messageField.setEnabled(false);
        sendButton.setEnabled(false);

        // Process the command in a background thread
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                // Get the currently selected model
                Object selectedItem = modelSelector.getSelectedItem();
                AiService serviceToUse;
                String modelIdentifierForLog = "N/A";

                if (selectedItem instanceof Models.ModelDetail) {
                    Models.ModelDetail selectedModelDetail = (Models.ModelDetail) selectedItem;
                    serviceToUse = openAiService; // Lab45 service
                    openAiService.setCurrentModelAndTokens(selectedModelDetail.modelId, selectedModelDetail.maxTokens);
                    modelIdentifierForLog = selectedModelDetail.displayName;
                    log.info("Using Lab45 service for linting with model: {}", modelIdentifierForLog);
                } else {
                    log.warn("No Lab45 model selected for linting. Using default Lab45 model.");
                    Models.ModelDetail defaultModel = Models.getDefaultLab45ModelDetail();
                    if (defaultModel != null) {
                        openAiService.setCurrentModelAndTokens(defaultModel.modelId, defaultModel.maxTokens);
                        modelIdentifierForLog = defaultModel.displayName;
                    } else {
                        log.error("Default Lab45 model is null. Cannot proceed with linting.");
                        return "Error: Default model not configured for Lab45 service.";
                    }
                    serviceToUse = openAiService; // Fallback to Lab45 service
                }
                // If ClaudeService were an option here, logic to choose it would be needed.

                LintCommandHandler lintCommandHandler = new LintCommandHandler(serviceToUse);
                return lintCommandHandler.processLintCommand(message);
            }

            @Override
            protected void done() {
                try {
                    // Remove the loading indicator
                    removeLoadingIndicator();

                    // Get the response
                    String response = get();

                    // Process the response
                    processAiResponse(response);

                    // Re-enable input
                    messageField.setEnabled(true);
                    sendButton.setEnabled(true);
                    messageField.requestFocusInWindow();
                } catch (InterruptedException | ExecutionException e) {
                    log.error("Error processing lint command", e);

                    // Remove the loading indicator
                    removeLoadingIndicator();

                    // Display error message
                    try {
                        messageProcessor.appendMessage(chatArea.getStyledDocument(),
                                "Sorry, I encountered an error while processing your lint command. Please try again.",
                                Color.RED, false);
                    } catch (BadLocationException ex) {
                        log.error("Error displaying error message", ex);
                    }

                    // Re-enable input
                    messageField.setEnabled(true);
                    sendButton.setEnabled(true);
                    messageField.requestFocusInWindow();
                }
            }
        }.execute();
    }

    /**
     * Gets an AI response for a message.
     * 
     * @param message The message to get a response for
     * @return The AI response
     */
    private String getAiResponse(String message) {
        log.info("Getting AI response for message: {}", message);

        Object selectedItem = modelSelector.getSelectedItem();
        Models.ModelDetail selectedModelDetail = null;

        if (selectedItem instanceof Models.ModelDetail) {
            selectedModelDetail = (Models.ModelDetail) selectedItem;
        } else { // Fallback if not a ModelDetail (e.g. if loading or error)
            log.warn("Selected item in JComboBox is not a ModelDetail instance: {}. Using default Lab45 model.",
                    selectedItem);
            selectedModelDetail = Models.getDefaultLab45ModelDetail();
            if (selectedModelDetail == null) { // Should not happen if list is not empty
                log.error("Default Lab45 model is null. Cannot get AI response.");
                return "Error: No model configured for AI service.";
            }
            // Update the UI to reflect that the default is being used.
            final Models.ModelDetail finalSelectedModelDetail = selectedModelDetail;
            SwingUtilities.invokeLater(() -> modelSelector.setSelectedItem(finalSelectedModelDetail));
        }

        // Now, openAiService is assumed to be the Lab45 service.
        // Model and tokens are set via the modelSelector's ActionListener or when
        // default is loaded.
        // So, just call generateResponse.
        log.info("Using Lab45 service (via openAiService) with current model: {}, tokens: {}",
                openAiService.getCurrentModel(), openAiService.getMaxTokens()); // Using deprecated getters for logging
                                                                                // new field values
                                                                                // Actual values are set by
                                                                                // setCurrentModelAndTokens
        return openAiService.generateResponse(new ArrayList<>(conversationHistory));
    }

    /**
     * Undoes the last rename operation performed by the ElementRenamer.
     */
    private void undoLastRename() {
        // Create a SwingWorker to process the undo in the background
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                // Get the currently selected model detail for Lab45 service
                Object selectedItem = modelSelector.getSelectedItem();
                AiService serviceToUse;
                String modelIdentifierForLog = "N/A";

                if (selectedItem instanceof Models.ModelDetail) {
                    Models.ModelDetail selectedModelDetail = (Models.ModelDetail) selectedItem;
                    serviceToUse = openAiService; // Lab45 service
                    openAiService.setCurrentModelAndTokens(selectedModelDetail.modelId, selectedModelDetail.maxTokens);
                    modelIdentifierForLog = selectedModelDetail.displayName;
                    log.info("Using Lab45 service for undoLastRename with model: {}", modelIdentifierForLog);
                } else {
                    log.warn("No Lab45 model selected for undoLastRename. Using default Lab45 model.");
                    Models.ModelDetail defaultModel = Models.getDefaultLab45ModelDetail();
                    if (defaultModel != null) {
                        openAiService.setCurrentModelAndTokens(defaultModel.modelId, defaultModel.maxTokens);
                        modelIdentifierForLog = defaultModel.displayName;
                    } else {
                        log.error("Default Lab45 model is null. Cannot proceed with undoLastRename.");
                        return "Error: Default model not configured for Lab45 service.";
                    }
                    serviceToUse = openAiService; // Fallback to Lab45
                }

                LintCommandHandler lintHandler = new LintCommandHandler(serviceToUse);
                return lintHandler.undoLastRename();
            }

            @Override
            protected void done() {
                try {
                    // Get the result and display it
                    String result = get();
                    // Display the result in the chat area
                    try {
                        messageProcessor.appendMessage(chatArea.getStyledDocument(), result, new Color(0, 51, 102),
                                false);
                    } catch (BadLocationException ex) {
                        log.error("Error displaying undo result", ex);
                    }
                } catch (InterruptedException | ExecutionException e) {
                    log.error("Error undoing rename operation", e);
                    // Display error message
                    try {
                        messageProcessor.appendMessage(chatArea.getStyledDocument(),
                                "Error undoing rename operation: " + e.getMessage(), Color.RED, false);
                    } catch (BadLocationException ex) {
                        log.error("Error displaying error message", ex);
                    }
                }
            }
        }.execute();
    }

    /**
     * Redoes the last undone rename operation performed by the ElementRenamer.
     */
    private void redoLastUndo() {
        // Create a SwingWorker to process the redo in the background
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                // Create a LintCommandHandler and process the redo
                LintCommandHandler lintHandler = new LintCommandHandler(claudeService);
                return lintHandler.redoLastUndo();
            }

            @Override
            protected void done() {
                try {
                    // Get the result and display it
                    String result = get();
                    // Display the result in the chat area
                    try {
                        messageProcessor.appendMessage(chatArea.getStyledDocument(), result, new Color(0, 51, 102),
                                false);
                    } catch (BadLocationException ex) {
                        log.error("Error displaying redo result", ex);
                    }
                } catch (InterruptedException | ExecutionException e) {
                    log.error("Error redoing rename operation", e);
                    // Display error message
                    try {
                        messageProcessor.appendMessage(chatArea.getStyledDocument(),
                                "Error redoing rename operation: " + e.getMessage(), Color.RED, false);
                    } catch (BadLocationException ex) {
                        log.error("Error displaying error message", ex);
                    }
                }
            }
        }.execute();
    }

    /**
     * Undoes the last wrap operation performed by the WrapCommandHandler.
     */
    private void undoLastWrap() {
        // Create a SwingWorker to process the undo in the background
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                // Get the WrapUndoRedoHandler instance and process the undo
                return WrapUndoRedoHandler.getInstance().undoLastWrap();
            }

            @Override
            protected void done() {
                try {
                    // Get the result and display it
                    String result = get();
                    // Display the result in the chat area
                    try {
                        messageProcessor.appendMessage(chatArea.getStyledDocument(), result, new Color(0, 51, 102),
                                false);
                    } catch (BadLocationException ex) {
                        log.error("Error displaying wrap undo result", ex);
                    }
                } catch (InterruptedException | ExecutionException e) {
                    log.error("Error undoing wrap operation", e);
                    // Display error message
                    try {
                        messageProcessor.appendMessage(chatArea.getStyledDocument(),
                                "Error undoing wrap operation: " + e.getMessage(), Color.RED, false);
                    } catch (BadLocationException ex) {
                        log.error("Error displaying error message", ex);
                    }
                }
            }
        }.execute();
    }

    /**
     * Cleans up resources when the panel is no longer needed.
     */
    public void cleanup() {
        // Unregister property change listener
        UIManager.removePropertyChangeListener(this);
    }

    /**
     * Updates the font sizes of chat components based on JMeter's current scale
     * factor
     */
    private void updateFontSizes() {
        float scale = JMeterUIDefaults.INSTANCE.getScale();

        // Update chat area font
        Font currentChatFont = chatArea.getFont();
        float newChatSize = baseChatFontSize * scale;
        Font newChatFont = currentChatFont.deriveFont(newChatSize);
        chatArea.setFont(newChatFont);

        // Update message field font
        Font currentMessageFont = messageField.getFont();
        float newMessageSize = baseMessageFontSize * scale;
        Font newMessageFont = currentMessageFont.deriveFont(newMessageSize);
        messageField.setFont(newMessageFont);
    }

    /**
     * Handles property change events, specifically for UI refresh events triggered
     * by zoom actions
     */
    @Override
    public void propertyChange(PropertyChangeEvent evt) {
        // Check if this is a UI refresh event
        if ("lookAndFeel".equals(evt.getPropertyName())) {
            // Update font sizes based on the current scale
            updateFontSizes();
        }
    }
}