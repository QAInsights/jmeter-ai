package org.qainsights.jmeter.ai.gui;

import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jorphan.gui.JMeterUIDefaults;
import org.qainsights.jmeter.ai.intellisense.InputBoxIntellisense;
import org.qainsights.jmeter.ai.service.AiService;
import org.qainsights.jmeter.ai.service.ClaudeService;
import org.qainsights.jmeter.ai.service.OllamaAiService;
import org.qainsights.jmeter.ai.service.OpenAiService;
import org.qainsights.jmeter.ai.utils.Constants;
import org.qainsights.jmeter.ai.utils.Models;
import org.qainsights.jmeter.ai.utils.VersionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Panel for interacting with AI to generate and modify JMeter test plans.
 * This class has been refactored to improve composability, readability, and
 * reusability
 * by delegating responsibilities to specialized component classes.
 */
public class AiChatPanel extends JPanel implements PropertyChangeListener, CommandCallback {
    private static final Logger log = LoggerFactory.getLogger(AiChatPanel.class);

    // UI components (kept for backward compatibility)
    private JTextPane chatArea;
    private JTextArea messageField;
    private JButton sendButton;
    private JButton stopButton;
    private Runnable currentCancelHandle;
    private JComboBox<String> modelSelector;
    private List<String> conversationHistory;
    private ClaudeService claudeService;
    private OpenAiService openAiService;
    private OllamaAiService ollamaService;
    private TreeNavigationButtons treeNavigationButtons;
    private JPanel navigationPanel; // Added field for navigation panel

    // Store the base font sizes for scaling
    private float baseChatFontSize;
    private float baseMessageFontSize;

    // Component managers
    private final MessageProcessor messageProcessor;
    private final ElementInfoProvider elementInfoProvider;
    private final AiResponseRouter aiResponseRouter;
    private final CommandDispatcher commandDispatcher;
    private final UndoRedoDispatcher undoRedoDispatcher;

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
        ollamaService = new OllamaAiService();

        messageProcessor = new MessageProcessor();
        elementInfoProvider = new ElementInfoProvider();
        aiResponseRouter = new AiResponseRouter(claudeService, openAiService, ollamaService);
        commandDispatcher = new CommandDispatcher(this);
        undoRedoDispatcher = new UndoRedoDispatcher(this);

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

        // Compute shared font once - used by both chat area and message field
        Font defaultFont = UIManager.getFont("TextField.font");
        Font largerFont = new Font(defaultFont.getFamily(), defaultFont.getStyle(), defaultFont.getSize() + 2);

        initModelSelector();
        add(createChatPanel(largerFont), BorderLayout.CENTER);
        add(createBottomPanel(largerFont), BorderLayout.SOUTH);

        // Display welcome message
        displayWelcomeMessage();
    }

    /**
     * Initialises the model selector combo box, loads models in the background,
     * and wires up the selection listener.
     */
    private void initModelSelector() {
        modelSelector = new JComboBox<>();
        modelSelector.addItem(null); // Add empty item while loading
        modelSelector.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected,
                                                          boolean cellHasFocus) {
                return super.getListCellRendererComponent(list, Objects.requireNonNullElse(value, "Loading models..."), index, isSelected, cellHasFocus);
            }
        });
        loadModelsInBackground();
        modelSelector.addActionListener(e -> {
            String selectedModel = (String) modelSelector.getSelectedItem();
            if (selectedModel != null) {
                log.info("Model selected from dropdown: {}", selectedModel);
                claudeService.setModel(selectedModel);
                openAiService.setModel(selectedModel);
                ollamaService.setModel(selectedModel);
            }
        });
    }

    /**
     * Creates the chat panel containing the header, chat area and undo/redo keybindings.
     *
     * @param font the font to apply to the chat area
     * @return the assembled chat panel
     */
    private JPanel createChatPanel(Font font) {
        JPanel chatPanel = new JPanel(new BorderLayout());
        Color borderColor = getThemeColor("Component.borderColor", UIManager.getColor("Separator.foreground"));
        chatPanel.setBorder(BorderFactory.createMatteBorder(0, 1, 1, 1, borderColor));
        chatPanel.add(createHeaderPanel(), BorderLayout.NORTH);

        chatArea = new JTextPane();
        chatArea.setEditable(false);
        chatArea.setFont(font);
        baseChatFontSize = font.getSize2D();
        chatArea.setBackground(getThemeColor("TextPane.background", Color.WHITE));
        chatArea.setForeground(getThemeColor("TextPane.foreground", Color.BLACK));
        Style defaultStyle = chatArea.getStyledDocument().getStyle("default");
        if (defaultStyle != null) {
            StyleConstants.setForeground(defaultStyle, getThemeColor("TextPane.foreground", Color.BLACK));
        }

        StyledDocument doc = chatArea.getStyledDocument();
        SimpleAttributeSet leftAlign = new SimpleAttributeSet();
        StyleConstants.setAlignment(leftAlign, StyleConstants.ALIGN_LEFT);
        doc.setParagraphAttributes(0, doc.getLength(), leftAlign, false);

        registerUndoRedoKeyBindings();

        JScrollPane scrollPane = new JScrollPane(chatArea);
        scrollPane.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        chatPanel.add(scrollPane, BorderLayout.CENTER);
        return chatPanel;
    }

    /**
     * Registers undo and redo keyboard shortcuts on the chat area.
     */
    private void registerUndoRedoKeyBindings() {
        InputMap inputMap = chatArea.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actionMap = chatArea.getActionMap();

        inputMap.put(Constants.UNDO_KEY_STROKE, "undoAction");
        actionMap.put("undoAction", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                switch (lastCommandType) {
                    case WRAP:
                        undoLastWrap();
                        break;
                    case LINT:
                        undoLastRename();
                        break;
                    default:
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

        inputMap.put(Constants.REDO_KEY_STROKE, "redoAction");
        actionMap.put("redoAction", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                switch (lastCommandType) {
                    case WRAP:
                        showWrapRedoNotSupported();
                        break;
                    case LINT:
                        redoLastUndo();
                        break;
                    default:
                        GuiPackage guiPackage = GuiPackage.getInstance();
                        if (guiPackage != null) {
                            JMeterTreeNode currentNode = guiPackage.getTreeListener().getCurrentNode();
                            if (currentNode != null && currentNode.getTestElement() instanceof TransactionController) {
                                showWrapRedoNotSupported();
                            } else {
                                redoLastUndo();
                            }
                        }
                        break;
                }
            }
        });
    }

    /**
     * Creates the bottom panel containing the model selector row, navigation panel
     * and input panel.
     *
     * @param font the font to apply to the message input field
     * @return the assembled bottom panel
     */
    private JPanel createBottomPanel(Font font) {
        JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));

        JPanel modelPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        modelPanel.add(new JLabel("Model: "));
        modelPanel.add(modelSelector);
        bottomPanel.add(modelPanel, BorderLayout.NORTH);

        bottomPanel.add(createNavigationPanel(), BorderLayout.CENTER);
        bottomPanel.add(createInputPanel(font), BorderLayout.SOUTH);
        return bottomPanel;
    }

    /**
     * Creates and initialises the navigation panel for tree navigation and element
     * suggestion buttons.
     *
     * @return the assembled navigation panel
     */
    private JPanel createNavigationPanel() {
        navigationPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        navigationPanel.setBorder(BorderFactory.createTitledBorder("Element Suggestions"));
        navigationPanel.add(treeNavigationButtons.getUpButton());
        navigationPanel.add(treeNavigationButtons.getDownButton());

        JSeparator separator = new JSeparator(SwingConstants.VERTICAL);
        separator.setPreferredSize(new Dimension(1, 30));
        navigationPanel.add(separator);

        navigationPanel.setMinimumSize(new Dimension(100, 70));
        navigationPanel.setPreferredSize(new Dimension(500, 70));
        navigationPanel.setVisible(true);
        return navigationPanel;
    }

    /**
     * Creates the input panel containing the message text area and send button.
     *
     * @param font the font to apply to the message field
     * @return the assembled input panel
     */
    private JPanel createInputPanel(Font font) {
        JPanel inputPanel = new JPanel(new BorderLayout(5, 0));

        messageField = new JTextArea(3, 20);
        messageField.setLineWrap(true);
        messageField.setWrapStyleWord(true);
        messageField.setFont(font);
        baseMessageFontSize = font.getSize2D();
        Color inputBorderColor = getThemeColor("Component.borderColor", Color.LIGHT_GRAY);
        messageField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(inputBorderColor),
                BorderFactory.createEmptyBorder(5, 5, 5, 5)));
        new InputBoxIntellisense(messageField);
        messageField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && !e.isShiftDown()) {
                    e.consume();
                    sendMessage();
                }
            }
        });

        JScrollPane messageScrollPane = new JScrollPane(messageField);
        messageScrollPane.setBorder(BorderFactory.createEmptyBorder());
        inputPanel.add(messageScrollPane, BorderLayout.CENTER);

        sendButton = createStyledButton("Send", 12);
        sendButton.addActionListener(e -> sendMessage());

        stopButton = createStyledButton("Stop", 12);
        stopButton.setVisible(false);
        stopButton.addActionListener(e -> {
            if (currentCancelHandle != null) {
                currentCancelHandle.run();
                appendMessageToChat("\n[Stream cancelled]");
                hideStopButton();
                setInputEnabled(true);
                removeLoadingIndicator();
            }
        });

        JPanel buttonPanel = new JPanel(new BorderLayout());
        buttonPanel.add(sendButton, BorderLayout.CENTER);
        buttonPanel.add(stopButton, BorderLayout.EAST);

        inputPanel.add(buttonPanel, BorderLayout.EAST);

        return inputPanel;
    }

    /**
     * Creates the header panel with title and new chat button.
     *
     * @return The header panel
     */
    private JPanel createHeaderPanel() {
        JPanel headerPanel = new JPanel();
        headerPanel.setLayout(new BoxLayout(headerPanel, BoxLayout.X_AXIS));
        Color headerBorderColor = getThemeColor("Separator.foreground", Color.LIGHT_GRAY);
        headerPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 1, 0, headerBorderColor),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        headerPanel.setBackground(UIManager.getColor("Panel.background"));

        JLabel titleLabel = new JLabel(Constants.APP_NAME + " v" + VersionUtils.getVersion());
        titleLabel.setFont(new Font(titleLabel.getFont().getName(), Font.BOLD, 14));
        titleLabel.setAlignmentY(Component.CENTER_ALIGNMENT);
        titleLabel.setMinimumSize(new Dimension(0, titleLabel.getPreferredSize().height));
        headerPanel.add(titleLabel);

        headerPanel.add(Box.createHorizontalGlue());

        JPanel donatePanel = createDonateButtonPanel();
        donatePanel.setAlignmentY(Component.CENTER_ALIGNMENT);
        headerPanel.add(donatePanel);

        headerPanel.add(Box.createRigidArea(new Dimension(6, 0)));

        JButton newChatButton = createStyledButton("+", 16);
        newChatButton.setToolTipText("Start a new conversation");
        newChatButton.setMargin(new Insets(0, 8, 0, 8));
        newChatButton.setAlignmentY(Component.CENTER_ALIGNMENT);
        newChatButton.addActionListener(e -> startNewConversation());
        headerPanel.add(newChatButton);

        return headerPanel;
    }

    private void openDonateLink() {
        try {
            Desktop.getDesktop().browse(new URI(Constants.DONATE_LINK));
        } catch (Exception e) {
            log.error("Failed to open donate link", e);
        }
    }

    /**
     * Creates the donate button wrapped in a centred panel.
     *
     * @return a panel containing the styled donate button
     */
    private JPanel createDonateButtonPanel() {
        JButton donateButton = createStyledButton("☕ Donate ♥", 13);
        donateButton.setToolTipText("Support this project as it takes time, tokens and resources to build and maintain");
        donateButton.setBackground(new Color(255, 149, 0));
        donateButton.setForeground(Color.BLACK);
        donateButton.setOpaque(true);
        donateButton.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(210, 105, 0), 2, true),
                BorderFactory.createEmptyBorder(5, 16, 5, 16)));
        donateButton.addActionListener(e -> openDonateLink());
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        panel.setOpaque(false);
        panel.add(donateButton);
        return panel;
    }

    /**
     * Creates a styled button with bold font and a rounded compound border.
     *
     * @param text     the button label
     * @param fontSize the bold font size
     * @return the configured JButton
     */
    private JButton createStyledButton(String text, int fontSize) {
        JButton button = new JButton(text);
        button.setFont(new Font(button.getFont().getName(), Font.BOLD, fontSize));
        button.setFocusPainted(false);
        Color borderColor = getThemeColor("Component.borderColor", Color.LIGHT_GRAY);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor, 1, true),
                BorderFactory.createEmptyBorder(2, 8, 2, 8)));
        return button;
    }

    /**
     * Loads the available models in the background.
     */
    private void loadModelsInBackground() {
        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() {
                return Models.loadAllModels(claudeService.getClient(), openAiService.getClient(), ollamaService);
            }

            @Override
            protected void done() {
                try {
                    List<String> models = get();
                    modelSelector.removeAllItems();

                    // Get the default model ID
                    String defaultModelId = claudeService.getCurrentModel();
                    log.info("Default model ID: {}", defaultModelId);

                    String defaultModel = null;

                    for (String model : models) {
                        modelSelector.addItem(model);
                        if (model.equals(defaultModelId)) {
                            defaultModel = model;
                        }
                    }

                    // Select the default model if found
                    if (defaultModel != null) {
                        modelSelector.setSelectedItem(defaultModel);
                        log.info("Selected default model: {}", defaultModel);
                    } else if (modelSelector.getItemCount() > 0) {
                        // If default model not found, select the first one
                        modelSelector.setSelectedIndex(0);
                        String selectedModel = (String) modelSelector.getSelectedItem();
                        claudeService.setModel(selectedModel);
                        log.info("Default model not found, selected first available: {}", selectedModel);
                    }
                } catch (Exception e) {
                    log.error("Failed to load models", e);
                }
            }
        }.execute();
    }

    /**
     * Displays a welcome message in the chat area.
     */
    private void displayWelcomeMessage() {
        log.info("Displaying welcome message");

        String welcomeMessage = Constants.WELCOME_MESSAGE;

        try {
            messageProcessor.appendMessage(chatArea.getStyledDocument(), welcomeMessage, getThemeColor("TextPane.foreground", Color.BLACK), true);
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
        commandDispatcher.dispatch(messageField.getText().trim());
    }

    /**
     * Gets information about the currently selected element.
     *
     * @return Information about the currently selected element, or null if no
     * element is selected
     */
    public String getCurrentElementInfo() {
        return elementInfoProvider.getCurrentElementInfo();
    }

    /**
     * Removes the loading indicator from the chat area.
     */
    @Override
    public void removeLoadingIndicator() {
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
    @Override
    public void processAiResponse(String response) {
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
            messageProcessor.appendMessage(chatArea.getStyledDocument(), response, getThemeColor("TextPane.foreground", Color.BLACK), true);
        } catch (BadLocationException e) {
            log.error("Error appending AI response to chat", e);
        }

        // Create element buttons for context-aware suggestions after the AI response
        SwingUtilities.invokeLater(() -> {
            log.info("Creating element buttons for context-aware suggestions");

            // Make sure the navigation panel is visible
            navigationPanel.setVisible(true);

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

    // -------------------------------------------------------------------------
    // CommandCallback implementation
    // -------------------------------------------------------------------------

    @Override
    public void setInputEnabled(boolean enabled) {
        messageField.setEnabled(enabled);
        sendButton.setEnabled(enabled);
        if (enabled) {
            messageField.requestFocusInWindow();
        }
    }


    @Override
    public void showStopButton() {
        SwingUtilities.invokeLater(() -> {
            stopButton.setVisible(true);
            sendButton.setEnabled(false);
        });
    }

    @Override
    public void hideStopButton() {
        SwingUtilities.invokeLater(() -> {
            stopButton.setVisible(false);
            sendButton.setEnabled(true);
            currentCancelHandle = null;
        });
    }

    private boolean firstTokenReceived = false;

    @Override
    public void appendStreamToken(String token) {
        SwingUtilities.invokeLater(() -> {
            if (!firstTokenReceived) {
                removeLoadingIndicator();
                firstTokenReceived = true;
            }
            try {
                StyledDocument doc = chatArea.getStyledDocument();
                doc.insertString(doc.getLength(), token, null);

                JScrollPane scrollPane = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, chatArea);
                if (scrollPane != null) {
                    JScrollBar vertical = scrollPane.getVerticalScrollBar();
                    vertical.setValue(vertical.getMaximum());
                }
            } catch (BadLocationException e) {
                log.error("Error appending stream token", e);
            }
        });
    }

    @Override
    public void onStreamComplete(String fullResponse) {
        SwingUtilities.invokeLater(() -> {
            try {
                StyledDocument doc = chatArea.getStyledDocument();
                doc.insertString(doc.getLength(), "\n", new SimpleAttributeSet());
            } catch (BadLocationException e) {
                log.error("Error appending newline after stream", e);
            }
            firstTokenReceived = false;
            hideStopButton();
            setInputEnabled(true);
        });
    }

    @Override
    public void onStreamError(String logMessage, Exception e, String userMessage) {
        SwingUtilities.invokeLater(() -> {
            firstTokenReceived = false;
            hideStopButton();
            onWorkerError(logMessage, e, userMessage);
        });
    }

    @Override
    public Runnable getAiStreamResponse(String message, java.util.function.Consumer<String> tokenConsumer, Runnable onComplete, java.util.function.Consumer<Exception> onError) {
        firstTokenReceived = false;
        Runnable cancelHandle = aiResponseRouter.generateStreamResponse((String) modelSelector.getSelectedItem(), new ArrayList<>(conversationHistory), tokenConsumer, onComplete, onError);
        currentCancelHandle = cancelHandle;
        return cancelHandle;
    }

    @Override
    public void clearMessageField() {
        messageField.setText("");
    }

    @Override
    public void appendUserMessage(String message) {
        SwingUtilities.invokeLater(() -> {
            try {
                messageProcessor.appendMessage(chatArea.getStyledDocument(), message, null, false);
            } catch (BadLocationException e) {
                log.error("Error appending user message to chat", e);
            }
        });
    }

    @Override
    public void appendLoadingIndicator() {
        SwingUtilities.invokeLater(() -> {
            try {
                messageProcessor.appendMessage(chatArea.getStyledDocument(), "AI is thinking...",
                        getThemeColor("Label.disabledForeground", Color.GRAY), false);
            } catch (BadLocationException e) {
                log.error("Error adding loading indicator", e);
            }
        });
    }

    @Override
    public void appendRedMessage(String message) {
        SwingUtilities.invokeLater(() -> {
            try {
                messageProcessor.appendMessage(chatArea.getStyledDocument(), message, Color.RED, false);
            } catch (BadLocationException e) {
                log.error("Error displaying message", e);
            }
        });
    }

    @Override
    public String getSelectedModel() {
        return (String) modelSelector.getSelectedItem();
    }

    @Override
    public List<String> getConversationHistory() {
        return conversationHistory;
    }

    @Override
    public void addToConversationHistory(String entry) {
        conversationHistory.add(entry);
    }

    @Override
    public void setLastCommandType(String type) {
        switch (type) {
            case "LINT":
                lastCommandType = LastCommandType.LINT;
                break;
            case "WRAP":
                lastCommandType = LastCommandType.WRAP;
                break;
            default:
                lastCommandType = LastCommandType.NONE;
                break;
        }
    }

    /**
     * Gets an AI response for a message.
     *
     * @param message The message to get a response for
     * @return The AI response
     */
    @Override
    public String getAiResponse(String message) {
        log.info("Getting AI response for message: {}", message);
        return aiResponseRouter.getAiResponse((String) modelSelector.getSelectedItem(), new ArrayList<>(conversationHistory));
    }

    private void undoLastRename() {
        undoRedoDispatcher.undoLastRename();
    }

    private void redoLastUndo() {
        undoRedoDispatcher.redoLastUndo();
    }

    private void undoLastWrap() {
        undoRedoDispatcher.undoLastWrap();
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
            updateFontSizes();
            SwingUtilities.invokeLater(this::refreshChatColors);
        }
    }

    /**
     * Appends a plain response message to the chat area.
     *
     * @param message the text to display
     */
    @Override
    public void appendMessageToChat(String message) {
        SwingUtilities.invokeLater(() -> {
            try {
                messageProcessor.appendMessage(chatArea.getStyledDocument(), message, null, false);
            } catch (BadLocationException ex) {
                log.error("Error displaying message", ex);
            }
        });
    }

    /**
     * Appends a red error message to the chat area and logs the exception.
     *
     * @param context a short description of the operation that failed (used for logging and the displayed message)
     * @param e       the exception that was caught
     */
    @Override
    public void appendErrorMessageToChat(String context, Exception e) {
        log.error(context, e);
        SwingUtilities.invokeLater(() -> {
            try {
                messageProcessor.appendMessage(chatArea.getStyledDocument(),
                        context + ": " + e.getMessage(), Color.RED, false);
            } catch (BadLocationException ex) {
                log.error("Error displaying error message", ex);
            }
        });
    }

    /**
     * Resolves the appropriate AiService based on the selected model ID prefix.
     *
     * @param selectedModel the model ID string from the model selector
     * @return the matching AiService
     */
    @Override
    public AiService resolveAiService(String selectedModel) {
        return aiResponseRouter.resolveAiService(selectedModel);
    }

    /**
     * Displays a message indicating that redo is not supported for wrap operations.
     */
    private void showWrapRedoNotSupported() {
        SwingUtilities.invokeLater(() -> {
            try {
                messageProcessor.appendMessage(chatArea.getStyledDocument(),
                        "Redo is not supported for wrap operations. Please use the @wrap command again if needed.",
                        Color.BLUE, false);
            } catch (BadLocationException ex) {
                log.error("Error displaying message", ex);
            }
        });
    }

    /**
     * Common success handler for all SwingWorker done() callbacks.
     * Removes the loading indicator, displays the response, and re-enables input.
     *
     * @param response the result string from the worker
     */
    @Override
    public void onWorkerSuccess(String response) {
        removeLoadingIndicator();
        processAiResponse(response);
        messageField.setEnabled(true);
        sendButton.setEnabled(true);
        messageField.requestFocusInWindow();
    }

    /**
     * Common error handler for all SwingWorker done() callbacks.
     * Logs the error, removes the loading indicator, shows a red error message, and re-enables input.
     *
     * @param logMessage  the message to log
     * @param e           the exception that was caught
     * @param userMessage the human-readable message to display in the chat
     */
    @Override
    public void onWorkerError(String logMessage, Exception e, String userMessage) {
        log.error(logMessage, e);
        removeLoadingIndicator();
        try {
            messageProcessor.appendMessage(chatArea.getStyledDocument(), userMessage, Color.RED, false);
        } catch (BadLocationException ex) {
            log.error("Error displaying error message", ex);
        }
        messageField.setEnabled(true);
        sendButton.setEnabled(true);
        messageField.requestFocusInWindow();
    }

    /**
     * Gets a color from the current UIManager theme, falling back to a default if not available.
     *
     * @param key      The UIManager color key
     * @param fallback The fallback color if the key is not found
     * @return The theme color or the fallback
     */
    private static Color getThemeColor(String key, Color fallback) {
        Color color = UIManager.getColor(key);
        return color != null ? color : fallback;
    }

    private void refreshChatColors() {
        Color newFg = getThemeColor("TextPane.foreground", Color.BLACK);
        Color newBg = getThemeColor("TextPane.background", Color.WHITE);
        chatArea.setBackground(newBg);
        chatArea.setForeground(newFg);
        Style defaultStyle = chatArea.getStyledDocument().getStyle("default");
        if (defaultStyle != null) {
            StyleConstants.setForeground(defaultStyle, newFg);
        }
        chatArea.repaint();
    }
}
