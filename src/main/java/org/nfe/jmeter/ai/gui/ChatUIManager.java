package org.nfe.jmeter.ai.gui;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.function.Consumer;
import java.util.List;
import java.util.ArrayList;

import org.nfe.jmeter.ai.service.ClaudeService;
import org.nfe.jmeter.ai.service.OpenAiService;
import org.nfe.jmeter.ai.utils.Models;
import org.nfe.jmeter.ai.utils.VersionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.anthropic.models.ModelInfo;
import com.anthropic.models.ModelListPage;

/**
 * Manages the UI components of the chat interface.
 * This class is responsible for creating, styling, and managing the chat UI components.
 */
public class ChatUIManager {
    private static final Logger log = LoggerFactory.getLogger(ChatUIManager.class);
    
    // UI Components
    private final JTextPane chatArea;
    private final JTextArea messageField;
    private final JButton sendButton;
    private final JComboBox<ModelInfo> modelSelector;
    private final TreeNavigationButtons treeNavigationButtons;
    
    // Panels
    private final JPanel mainPanel;
    private final JPanel bottomPanel;
    private final JPanel navigationPanel;
    
    /**
     * Constructs a new ChatUIManager with all necessary UI components.
     * 
     * @param sendMessageAction The action to perform when the send button is clicked
     * @param newChatAction The action to perform when the new chat button is clicked
     * @param modelSelectionAction The action to perform when a model is selected
     * @param claudeService The Claude service to use for model information
     * @param openAiService The OpenAI service to use for model information
     */
    public ChatUIManager(Runnable sendMessageAction, Runnable newChatAction, 
                         Consumer<ModelInfo> modelSelectionAction, ClaudeService claudeService, OpenAiService openAiService) {
        // Initialize navigation buttons
        treeNavigationButtons = new TreeNavigationButtons();
        treeNavigationButtons.setUpButtonActionListener();
        treeNavigationButtons.setDownButtonActionListener();
        
        // Create main panel
        mainPanel = new JPanel(new BorderLayout());
        mainPanel.setPreferredSize(new Dimension(500, 600));
        mainPanel.setMinimumSize(new Dimension(350, 400));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));
        
        // Create chat area
        chatArea = createChatArea();
        JPanel chatPanel = createChatPanel(chatArea, newChatAction);
        mainPanel.add(chatPanel, BorderLayout.CENTER);
        
        // Create bottom panel with model selector and input controls
        bottomPanel = new JPanel(new BorderLayout(5, 5));
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        
        // Create model selector
        modelSelector = createModelSelector(claudeService, openAiService, modelSelectionAction);
        JPanel modelPanel = createModelPanel(modelSelector);
        bottomPanel.add(modelPanel, BorderLayout.NORTH);
        
        // Create navigation panel
        navigationPanel = createNavigationPanel();
        bottomPanel.add(navigationPanel, BorderLayout.CENTER);
        
        // Create input panel
        JPanel inputPanel = new JPanel(new BorderLayout(5, 0));
        
        // Create message field
        messageField = createMessageField(sendMessageAction);
        JScrollPane messageScrollPane = new JScrollPane(messageField);
        messageScrollPane.setBorder(BorderFactory.createEmptyBorder());
        
        // Create send button
        sendButton = createSendButton(sendMessageAction);
        
        inputPanel.add(messageScrollPane, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);
        bottomPanel.add(inputPanel, BorderLayout.SOUTH);
        
        mainPanel.add(bottomPanel, BorderLayout.SOUTH);
    }
    
    /**
     * Creates the chat area component.
     * 
     * @return The configured chat area
     */
    private JTextPane createChatArea() {
        JTextPane area = new JTextPane();
        area.setEditable(false);
        
        // Use the system default font with larger size
        Font defaultFont = UIManager.getFont("TextField.font");
        Font largerFont = new Font(defaultFont.getFamily(), defaultFont.getStyle(), defaultFont.getSize() + 2);
        area.setFont(largerFont);
        
        // Set default paragraph attributes for left alignment
        StyledDocument doc = area.getStyledDocument();
        SimpleAttributeSet leftAlign = new SimpleAttributeSet();
        StyleConstants.setAlignment(leftAlign, StyleConstants.ALIGN_LEFT);
        doc.setParagraphAttributes(0, doc.getLength(), leftAlign, false);
        
        return area;
    }
    
    /**
     * Creates the chat panel containing the chat area and header.
     * 
     * @param chatArea The chat area component
     * @param newChatAction The action to perform when the new chat button is clicked
     * @return The configured chat panel
     */
    private JPanel createChatPanel(JTextPane chatArea, Runnable newChatAction) {
        JPanel chatPanel = new JPanel(new BorderLayout());
        chatPanel.setBorder(BorderFactory.createMatteBorder(0, 1, 1, 1, Color.LIGHT_GRAY));
        
        // Create a header panel for the title and new chat button
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 1, 0, Color.LIGHT_GRAY),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        headerPanel.setBackground(new Color(240, 240, 240));
        
        // Add a title to the left side of the header panel
        JLabel titleLabel = new JLabel("Lab 45 NFE - JMeter Agent v" + VersionUtils.getVersion());
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
        newChatButton.addActionListener(e -> newChatAction.run());
        
        // Add the button to the right side of the header panel
        headerPanel.add(newChatButton, BorderLayout.EAST);
        
        // Add the header panel to the top of the chat panel
        chatPanel.add(headerPanel, BorderLayout.NORTH);
        
        JScrollPane scrollPane = new JScrollPane(chatArea);
        scrollPane.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        chatPanel.add(scrollPane, BorderLayout.CENTER);
        
        return chatPanel;
    }
    
    /**
     * Creates the model selector component.
     * 
     * @param claudeService The Claude service to use for model information
     * @param modelSelectionAction The action to perform when a model is selected
     * @return The configured model selector
     */
    private JComboBox<ModelInfo> createModelSelector(ClaudeService claudeService, OpenAiService openAiService, Consumer<ModelInfo> modelSelectionAction) {
        JComboBox<ModelInfo> selector = new JComboBox<>();
        selector.addItem(null); // Add empty item while loading
        selector.setRenderer(new DefaultListCellRenderer() {
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
                // Get models from both services
                List<ModelInfo> models = new ArrayList<>();
                
                // Variable to store Anthropic models for reference
                ModelListPage anthropicModels = null;
                // com.openai.models.ModelListPage openAiModels = null; // OpenAI SDK removed
                
                try {
                    // Get Anthropic models
                    anthropicModels = Models.getAnthropicModels(claudeService.getClient());
                    if (anthropicModels != null && anthropicModels.data() != null) {
                        models.addAll(anthropicModels.data());
                        log.info("Added {} Anthropic models", anthropicModels.data().size());
                    }
                } catch (Exception e) {
                    log.error("Error loading Anthropic models: {}", e.getMessage(), e);
                }
                
                // Add Custom AI model(s) placeholder
                try {
                    // Call the new method that returns List<Models.ModelDetail>
                    List<Models.ModelDetail> customAiModelDetails = Models.getLab45ModelDetails(); 
                    if (customAiModelDetails != null && !customAiModelDetails.isEmpty()) {
                        for (Models.ModelDetail detail : customAiModelDetails) {
                            try {
                                // Create an Anthropic ModelInfo object for each Lab45 ModelDetail.
                                // The JComboBox is JComboBox<ModelInfo>, and its renderer uses model.id().
                                // So, we put the displayName into ModelInfo's id field for display.
                                ModelInfo modelInfoForDropdown = ModelInfo.builder()
                                    .id(detail.displayName) // Use displayName for display in JComboBox
                                    // Other ModelInfo fields like 'name' or 'description' are not standard for its builder.
                                    .build();
                                models.add(modelInfoForDropdown);
                                log.debug("Added Lab45 model to selector (as ModelInfo): {}", detail.displayName);
                            } catch (Exception e) {
                                log.warn("Could not create ModelInfo for Lab45 model {}: {}", detail.displayName, e.getMessage());
                            }
                        }
                        log.info("Added {} Lab45 model(s) to selector", customAiModelDetails.size());
                    } else {
                        log.info("No Lab45 models returned from Models.getLab45ModelDetails()");
                    }
                } catch (Exception e) {
                    log.error("Error adding Lab45 models: {}", e.getMessage(), e);
                }
                return models;
            }
            
            @Override
            protected void done() {
                try {
                    List<ModelInfo> models = get();
                    selector.removeAllItems();
                    
                    // Get the default model ID
                    String defaultModelId = claudeService.getCurrentModel();
                    log.info("Default model ID: {}", defaultModelId);
                    
                    ModelInfo defaultModelInfo = null;
                    
                    for (ModelInfo model : models) {
                        selector.addItem(model);
                        if (model.id().equals(defaultModelId)) {
                            defaultModelInfo = model;
                        }
                    }
                    
                    // Select the default model if found
                    if (defaultModelInfo != null) {
                        selector.setSelectedItem(defaultModelInfo);
                        log.info("Selected default model: {}", defaultModelInfo.id());
                    } else if (selector.getItemCount() > 0) {
                        // If default model not found, select the first one
                        selector.setSelectedIndex(0);
                        ModelInfo selectedModel = (ModelInfo) selector.getSelectedItem();
                        claudeService.setModel(selectedModel.id());
                        log.info("Default model not found, selected first available: {}", selectedModel.id());
                    }
                } catch (Exception e) {
                    log.error("Failed to load models", e);
                }
            }
        }.execute();
        
        // Add a listener to log model changes
        selector.addActionListener(e -> {
            ModelInfo selectedModel = (ModelInfo) selector.getSelectedItem();
            if (selectedModel != null) {
                log.info("Model selected from dropdown: {}", selectedModel.id());
                modelSelectionAction.accept(selectedModel);
            }
        });
        
        return selector;
    }
    
    /**
     * Creates the model panel containing the model selector.
     * 
     * @param modelSelector The model selector component
     * @return The configured model panel
     */
    private JPanel createModelPanel(JComboBox<ModelInfo> modelSelector) {
        FlowLayout flowLayout = new FlowLayout(FlowLayout.LEFT);
        JPanel modelPanel = new JPanel(flowLayout);
        JLabel modelLabel = new JLabel("Model: ");
        modelPanel.add(modelLabel);
        modelPanel.add(modelSelector);
        return modelPanel;
    }
    
    /**
     * Creates the navigation panel containing the tree navigation buttons.
     * 
     * @return The configured navigation panel
     */
    private JPanel createNavigationPanel() {
        JPanel navigationPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        navigationPanel.add(treeNavigationButtons.getUpButton());
        navigationPanel.add(treeNavigationButtons.getDownButton());
        return navigationPanel;
    }
    
    /**
     * Creates the message field component.
     * 
     * @param sendMessageAction The action to perform when Enter is pressed
     * @return The configured message field
     */
    private JTextArea createMessageField(Runnable sendMessageAction) {
        JTextArea field = new JTextArea(3, 20); // 3 rows tall
        field.setLineWrap(true);
        field.setWrapStyleWord(true);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.LIGHT_GRAY),
                BorderFactory.createEmptyBorder(5, 5, 5, 5)));
        
        // Add key listener to handle Enter key for sending messages
        field.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && !e.isShiftDown()) {
                    e.consume(); // Prevent newline
                    sendMessageAction.run();
                }
            }
        });
        
        return field;
    }
    
    /**
     * Creates the send button component.
     * 
     * @param sendMessageAction The action to perform when the button is clicked
     * @return The configured send button
     */
    private JButton createSendButton(Runnable sendMessageAction) {
        JButton button = new JButton("Send");
        button.setBackground(new Color(240, 240, 240)); // Light gray background
        button.setForeground(new Color(0, 0, 0)); // Black text
        button.setFocusPainted(false);
        button.setFont(new Font(button.getFont().getName(), Font.BOLD, 12));
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(200, 200, 200), 1, true),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));
        
        button.addActionListener(e -> sendMessageAction.run());
        
        return button;
    }
    
    /**
     * Adds a component to the navigation panel.
     * 
     * @param component The component to add
     */
    public void addToNavigationPanel(Component component) {
        navigationPanel.add(component);
    }
    
    /**
     * Gets the main panel containing all UI components.
     * 
     * @return The main panel
     */
    public JPanel getMainPanel() {
        return mainPanel;
    }
    
    /**
     * Gets the chat area component.
     * 
     * @return The chat area
     */
    public JTextPane getChatArea() {
        return chatArea;
    }
    
    /**
     * Gets the message field component.
     * 
     * @return The message field
     */
    public JTextArea getMessageField() {
        return messageField;
    }
    
    /**
     * Gets the send button component.
     * 
     * @return The send button
     */
    public JButton getSendButton() {
        return sendButton;
    }
    
    /**
     * Gets the model selector component.
     * 
     * @return The model selector
     */
    public JComboBox<ModelInfo> getModelSelector() {
        return modelSelector;
    }
    
    /**
     * Gets the tree navigation buttons.
     * 
     * @return The tree navigation buttons
     */
    public TreeNavigationButtons getTreeNavigationButtons() {
        return treeNavigationButtons;
    }
    
    /**
     * Removes the loading indicator from the chat area.
     */
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
}
