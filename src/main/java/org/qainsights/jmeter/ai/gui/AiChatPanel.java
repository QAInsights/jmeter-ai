package org.qainsights.jmeter.ai.gui;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputMethodEvent;
import java.awt.event.InputMethodListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.net.URI;
import java.text.AttributedCharacterIterator;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jorphan.gui.JMeterUIDefaults;
import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.gui.theme.UiTokens;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.qainsights.jmeter.ai.intellisense.InputBoxIntellisense;
import org.qainsights.jmeter.ai.service.AiService;
import com.google.genai.Client;
import org.qainsights.jmeter.ai.service.ClaudeService;
import org.qainsights.jmeter.ai.service.OllamaAiService;
import org.qainsights.jmeter.ai.service.DeepseekAiService;
import org.qainsights.jmeter.ai.service.GoogleAiService;
import org.qainsights.jmeter.ai.service.GrokAiService;
import org.qainsights.jmeter.ai.service.OpenAiService;
import org.qainsights.jmeter.ai.service.MetaMuseAiService;
import org.qainsights.jmeter.ai.service.BedrockAiService;
import org.qainsights.jmeter.ai.service.AiServiceHolder;
import org.qainsights.jmeter.ai.cli.SubscriptionCliProvider;
import org.qainsights.jmeter.ai.service.ClaudeCodeAiService;
import org.qainsights.jmeter.ai.service.CliSubscriptionAiService;
import org.qainsights.jmeter.ai.service.CodexAiService;
import org.qainsights.jmeter.ai.service.attach.AttachmentRegistry;
import org.qainsights.jmeter.ai.service.attach.FileContentPreparer;
import org.qainsights.jmeter.ai.service.prefs.ModelSelectorPreferences;
import org.qainsights.jmeter.ai.service.session.ConversationSession;
import org.qainsights.jmeter.ai.service.session.ConversationStore;
import org.qainsights.jmeter.ai.service.session.ConversationTracker;
import org.qainsights.jmeter.ai.service.usage.ContextEstimator;
import org.qainsights.jmeter.ai.service.usage.UsageStats;
import org.qainsights.jmeter.ai.service.reasoning.ModelCapabilityCatalog;
import org.qainsights.jmeter.ai.service.reasoning.ReasoningSettings;
import org.qainsights.jmeter.ai.utils.Constants;
import org.qainsights.jmeter.ai.utils.Models;
import org.qainsights.jmeter.ai.utils.VersionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import java.net.URL;

/**
 * Panel for interacting with AI to generate and modify JMeter test plans.
 * This class has been refactored to improve composability, readability, and
 * reusability
 * by delegating responsibilities to specialized component classes.
 */
public class AiChatPanel
    extends JPanel
    implements PropertyChangeListener, CommandCallback
{

    private static final Logger log = LoggerFactory.getLogger(
        AiChatPanel.class
    );

    // UI components (kept for backward compatibility)
    private TranscriptView transcript;

    /**
     * Built on first use rather than in the constructor: it reads
     * {@code ~/.jmeter-ai/prompts.json}, which tests don't want touched.
     * Shared by the {@code @prompts} intellisense mode and the MessageCard
     * "Save prompt" action so both see the same prompts.
     */
    private org.qainsights.jmeter.ai.service.prompts.PromptLibrary promptLibrary;
    private JTextArea messageField;
    private InputOptionsRow inputOptionsRow;
    private Runnable currentCancelHandle;
    private ModelSelectorPanel modelSelectorPanel;
    private ClaudeService claudeService;
    private OpenAiService openAiService;
    private OllamaAiService ollamaService;
    private DeepseekAiService deepseekService;
    private GoogleAiService googleService;
    private GrokAiService grokService;
    private MetaMuseAiService metaMuseService;
    private BedrockAiService bedrockService;
    private CodexAiService codexService;
    private ClaudeCodeAiService claudeCodeService;
    private TreeNavigationButtons treeNavigationButtons;
    private JPanel navigationPanel; // Added field for navigation panel
    private JPanel headerPanel;
    private JPanel bottomPanel;
    private JPanel titleStack;
    private JLabel versionLabel;
    private JSeparator headerSeparator;
    private JComponent recordingControl;
    private boolean recordingEnabled;
    private DonateButton donateButton;
    private GeminiBorderPanel geminiBorderPanel;
    private final ReasoningSettings reasoningSettings = new ReasoningSettings();
    private ReasoningControls reasoningControls;
    private final AttachmentRegistry attachmentRegistry = new AttachmentRegistry();
    private AttachmentBar attachmentBar;

    // Conversation persistence (F5): the tracker owns history + session id and
    // autosaves after every turn; restored on open when jmeter.ai.session.restore=true.
    private final ConversationStore sessionStore = ConversationStore.open();
    private final ConversationTracker conversationTracker = new ConversationTracker(sessionStore);
    /** Model carried by a restored session; reselected once the model list loads. */
    private String restoredSessionModel;

    // Context stats (F9): session token/cost accumulator injected into every
    // service; the label renders in the input options row.
    private final UsageStats usageStats = new UsageStats();
    private final ContextStatsLabel contextStatsLabel = new ContextStatsLabel();

    // Store the base font sizes for scaling
    private float baseChatFontSize;
    private float baseMessageFontSize;

    // Component managers
    private final ElementInfoProvider elementInfoProvider;
    private final AiResponseRouter aiResponseRouter;
    private final CommandDispatcher commandDispatcher;
    private final UndoRedoDispatcher undoRedoDispatcher;

    // Track the last command type for undo/redo operations
    private enum LastCommandType {
        NONE,
        LINT,
        WRAP,
    }

    private LastCommandType lastCommandType = LastCommandType.NONE;

    /**
     * Tracks whether the system IME (Input Method Editor) is currently composing
     * a character sequence. Used to avoid intercepting ENTER key presses that
     * are meant to confirm an IME candidate (e.g. Chinese Pinyin on Windows).
     */
    private volatile boolean imeComposing = false;

    /**
     * Constructs a new AiChatPanel.
     */
    public AiChatPanel() {
        // Initialize services and utilities
        claudeService = new ClaudeService();
        openAiService = new OpenAiService();
        ollamaService = new OllamaAiService();
        deepseekService = new DeepseekAiService();
        grokService = new GrokAiService();
        metaMuseService = new MetaMuseAiService();
        bedrockService = new BedrockAiService();
        codexService = new CodexAiService();
        claudeCodeService = new ClaudeCodeAiService();

        String googleApiKey = AiConfig.getProperty("google.api.key", "");
        if (googleApiKey != null && !googleApiKey.isEmpty() && !googleApiKey.equals("YOUR_API_KEY")) {
            Client googleClient = Client.builder().apiKey(googleApiKey).build();
            googleService = new GoogleAiService(googleClient);
        }

        injectReasoningSettings();
        injectUsageStats();

        elementInfoProvider = new ElementInfoProvider();
        aiResponseRouter = new AiResponseRouter(getServiceHolder());
        aiResponseRouter.setAttachmentRegistry(attachmentRegistry);
        commandDispatcher = new CommandDispatcher(this);
        undoRedoDispatcher = new UndoRedoDispatcher(this);

        // Initialize tree navigation buttons with action listeners
        treeNavigationButtons = new TreeNavigationButtons();
        treeNavigationButtons.setUpButtonActionListener();
        treeNavigationButtons.setDownButtonActionListener();

        // Register for UI refresh events (for zoom functionality)
        UIManager.addPropertyChangeListener(this);

        // Set up the panel layout
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(500, 600));
        setMinimumSize(new Dimension(350, 400));
        setBorder(BorderFactory.createEmptyBorder());
        setBackground(ThemeColors.canvas());

        // Compute shared font once - used by both chat area and message field.
        // Use deriveFont() instead of new Font(family, ...) so that the JVM's
        // composite-font (CJK fallback) chain is preserved. Creating a brand-new
        // Font object from a physical family name (e.g. "Segoe UI") produces a
        // non-composite font that cannot render Chinese / Japanese / Korean glyphs
        // and instead shows empty boxes on Windows.
        Font defaultFont = UIManager.getFont("TextField.font");
        if (defaultFont == null) {
            defaultFont = new Font(Font.DIALOG, Font.PLAIN, 12);
        }
        Font largerFont = defaultFont.deriveFont(defaultFont.getSize2D() + 2f);

        reasoningControls = new ReasoningControls(reasoningSettings);
        modelSelectorPanel = new ModelSelectorPanel(
                ModelSelectorPreferences.load(), ModelCapabilityCatalog.getInstance());
        modelSelectorPanel.setSelectionListener(this::onModelSelected);
        modelSelectorPanel.setCliProviders(enabledCliProviders());
        loadModelsInBackground();
        add(createChatPanel(largerFont), BorderLayout.CENTER);
        add(createBottomPanel(largerFont), BorderLayout.SOUTH);

        // Display welcome message (skipped when a previous session is restored)
        if (!restoreLastSessionIfEnabled()) {
            displayWelcomeMessage();
        }
    }

    /**
     * Reloads the most recently saved session into history, transcript and the
     * attachment registry when {@code jmeter.ai.session.restore=true}. The
     * restored session keeps its id, so autosaves continue the same file.
     *
     * @return true when a session was restored (and the welcome message should
     * be skipped)
     */
    private boolean restoreLastSessionIfEnabled() {
        if (!Boolean.parseBoolean(
                AiConfig.getProperty(ConversationStore.RESTORE_PROPERTY, "false"))) {
            return false;
        }
        java.util.Optional<ConversationSession> loaded = sessionStore.loadMostRecent();
        if (loaded.isEmpty()) {
            return false;
        }
        ConversationSession session = loaded.get();
        log.info("Restoring conversation session {} ({} turns)", session.id(), session.turns().size());
        conversationTracker.adopt(session);
        if (!session.model().isEmpty()) {
            // reselected once the model list arrives in loadModelsInBackground
            restoredSessionModel = session.model();
        }
        for (ConversationSession.AttachmentSnapshot snapshot : session.attachments()) {
            attachmentRegistry.restore(snapshot.id(), snapshot.fileName(), snapshot.content(),
                    FileContentPreparer.Mode.parse(snapshot.mode()));
        }
        for (ConversationSession.Turn turn : session.turns()) {
            if ("user".equals(turn.role())) {
                transcript.addUserMessage(turn.text());
            } else {
                transcript.addAssistantMessage(turn.text());
            }
        }
        // show the estimate for the restored history immediately (no usage
        // was recorded this process, so the label would otherwise stay blank
        // until the model list arrives)
        refreshContextStats();
        return true;
    }

    /** Snapshots the current conversation for saving/exporting (package-private for tests). */
    ConversationSession buildSession() {
        return conversationTracker.snapshot(modelSelectorPanel.getSelectedModel(), attachmentRegistry.all());
    }

    /** The model id carried by a restored session, or null (package-private for tests). */
    String restoredSessionModel() {
        return restoredSessionModel;
    }

    /** The id of the active session (package-private for tests). */
    String currentSessionId() {
        return conversationTracker.sessionId();
    }

    /** Shares the session usage accumulator with every service (context-stats label). */
    private void injectUsageStats() {
        claudeService.setUsageStats(usageStats);
        openAiService.setUsageStats(usageStats);
        ollamaService.setUsageStats(usageStats);
        deepseekService.setUsageStats(usageStats);
        grokService.setUsageStats(usageStats);
        metaMuseService.setUsageStats(usageStats);
        bedrockService.setUsageStats(usageStats);
        if (googleService != null) {
            googleService.setUsageStats(usageStats);
        }
    }

    /**
     * Re-renders the context-stats label. The context numerator is the last
     * server-reported prompt size when available, else an estimate over the
     * (attachment-resolved) history marked with {@code ~}. Runs on the EDT.
     */
    private void refreshContextStats() {
        runOnEdt(() -> {
            UsageStats.Snapshot snapshot = usageStats.snapshot();
            long contextTokens;
            boolean estimated;
            if (snapshot.calls() > 0 && snapshot.lastInputTokens() > 0) {
                contextTokens = snapshot.lastInputTokens();
                estimated = false;
            } else {
                contextTokens = ContextEstimator.estimateTokens(
                        resolveAttachmentMarkers(conversationTracker.historyCopy()));
                estimated = true;
            }
            String model = modelSelectorPanel.getSelectedModel();
            long contextWindow = model == null ? 0 : ModelCapabilityCatalog.getInstance()
                    .capabilities(model)
                    .map(ModelCapabilityCatalog.CapabilityInfo::getContextWindow)
                    .orElse(0L);
            contextStatsLabel.showStats(contextTokens, estimated, contextWindow, snapshot);
        });
    }

    /**
     * Routes a model selection from the selector panel into the owning
     * provider service (via {@link AiResponseRouter#resolveAiService}, which
     * also sets the bare id on that service), refreshes the reasoning
     * controls, and (for Ollama) kicks off the live capability probe.
     */
    private void onModelSelected(String selectedModel) {
        log.info("Model selected: {}", selectedModel);
        resolveAiService(selectedModel);
        reasoningControls.updateForModel(selectedModel);
        refreshContextStats();
        if (selectedModel.startsWith("ollama:")) {
            // Probe the local model's real capabilities in the background
            // and refresh the controls when the answer arrives.
            String ollamaModelId = selectedModel.substring(7);
            ollamaService.resolveThinkingCapability(ollamaModelId,
                    () -> reasoningControls.updateForModel(selectedModel));
        }
    }

    /**
     * Shares the user's reasoning (thinking/effort) choices with every service.
     * Providers without reasoning support ignore the settings via the
     * {@link AiService#setReasoningSettings} default no-op. Also registers the
     * live Ollama capability probe with the capability registry.
     */
    private void injectReasoningSettings() {
        claudeService.setReasoningSettings(reasoningSettings);
        openAiService.setReasoningSettings(reasoningSettings);
        ollamaService.setReasoningSettings(reasoningSettings);
        deepseekService.setReasoningSettings(reasoningSettings);
        grokService.setReasoningSettings(reasoningSettings);
        metaMuseService.setReasoningSettings(reasoningSettings);
        bedrockService.setReasoningSettings(reasoningSettings);
        if (googleService != null) {
            googleService.setReasoningSettings(reasoningSettings);
        }
        org.qainsights.jmeter.ai.service.reasoning.ReasoningCapabilities
                .setOllamaThinkingProbe(ollamaService::probeThinkingCapability);
    }

    /**
     * Creates the chat panel containing the header, chat area and undo/redo keybindings.
     *
     * @param font the font to apply to the chat area
     * @return the assembled chat panel
     */
    private JPanel createChatPanel(Font font) {
        JPanel chatPanel = new JPanel(new BorderLayout());
        chatPanel.setBorder(BorderFactory.createEmptyBorder());
        chatPanel.setBackground(ThemeColors.canvas());
        chatPanel.add(createHeaderPanel(), BorderLayout.NORTH);

        transcript = new TranscriptView(font);
        baseChatFontSize = font.getSize2D();
        transcript.setBackground(ThemeColors.canvas());
        transcript.setOpaque(true);

        registerUndoRedoKeyBindings();

        JScrollPane scrollPane = new JScrollPane(transcript);
        scrollPane.setBorder(BorderFactory.createEmptyBorder(
                0, UiTokens.SPACE_2, 0, UiTokens.SPACE_2));
        scrollPane.getViewport().setBackground(ThemeColors.canvas());
        scrollPane.getVerticalScrollBar().setUnitIncrement(UiTokens.SPACE_4);
        chatPanel.add(scrollPane, BorderLayout.CENTER);
        return chatPanel;
    }

    /**
     * Registers undo and redo keyboard shortcuts on the chat area.
     */
    private void registerUndoRedoKeyBindings() {
        InputMap inputMap = transcript.getInputMap(
            JComponent.WHEN_IN_FOCUSED_WINDOW
        );
        ActionMap actionMap = transcript.getActionMap();

        inputMap.put(Constants.UNDO_KEY_STROKE, "undoAction");
        actionMap.put(
            "undoAction",
            new AbstractAction() {
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
                                JMeterTreeNode currentNode = guiPackage
                                    .getTreeListener()
                                    .getCurrentNode();
                                if (
                                    currentNode != null &&
                                    currentNode.getTestElement() instanceof
                                        TransactionController
                                ) {
                                    undoLastWrap();
                                } else {
                                    undoLastRename();
                                }
                            }
                            break;
                    }
                }
            }
        );

        inputMap.put(Constants.REDO_KEY_STROKE, "redoAction");
        actionMap.put(
            "redoAction",
            new AbstractAction() {
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
                                JMeterTreeNode currentNode = guiPackage
                                    .getTreeListener()
                                    .getCurrentNode();
                                if (
                                    currentNode != null &&
                                    currentNode.getTestElement() instanceof
                                        TransactionController
                                ) {
                                    showWrapRedoNotSupported();
                                } else {
                                    redoLastUndo();
                                }
                            }
                            break;
                    }
                }
            }
        );
    }

    /**
     * Creates the bottom panel containing the model selector row, navigation panel
     * and input panel.
     *
     * @param font the font to apply to the message input field
     * @return the assembled bottom panel
     */
    private JPanel createBottomPanel(Font font) {
        bottomPanel = new JPanel(new BorderLayout(0, UiTokens.SPACE_1));
        bottomPanel.setBackground(ThemeColors.canvas());
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(
                UiTokens.SPACE_2, UiTokens.SPACE_3, UiTokens.SPACE_3, UiTokens.SPACE_3));

        attachmentBar = new AttachmentBar(attachmentRegistry,
                message -> runOnEdt(() -> transcript.addSystemMessage(message, ThemeColors.warning())));
        transcript.setAttachmentLookup(attachmentRegistry::find);
        transcript.setSavePromptHandler(body -> PromptEditDialog
                .forNew(javax.swing.SwingUtilities.getWindowAncestor(this), promptLibrary(), body)
                .setVisible(true));

        bottomPanel.add(attachmentBar, BorderLayout.NORTH);
        bottomPanel.add(createInputPanel(font), BorderLayout.CENTER);
        return bottomPanel;
    }

    /**
     * Creates the slim model row kept directly inside the composer, replacing
     * the old fixed-height "Navigation" block and avoiding pointer travel.
     *
     * @return the assembled toolbar row
     */
    private JPanel createToolbarRow() {
        navigationPanel = new JPanel(new BorderLayout(UiTokens.SPACE_1, 0));
        navigationPanel.setOpaque(false);
        navigationPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, UiTokens.SPACE_1, 0));

        navigationPanel.add(modelSelectorPanel, BorderLayout.CENTER);

        JPanel modelActions = new JPanel();
        modelActions.setLayout(new BoxLayout(modelActions, BoxLayout.X_AXIS));
        modelActions.setOpaque(false);
        modelActions.add(reasoningControls);
        modelActions.add(Box.createHorizontalStrut(UiTokens.SPACE_1));
        modelActions.add(modelSelectorPanel.favoriteButton());
        navigationPanel.add(modelActions, BorderLayout.EAST);

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
        geminiBorderPanel = new GeminiBorderPanel();

        PlaceholderTextArea input = new PlaceholderTextArea(4, 20);
        input.setPlaceholder("Ask Feather Wand about your test plan");
        messageField = input;
        messageField.setLineWrap(true);
        messageField.setWrapStyleWord(true);
        messageField.setFont(font);
        baseMessageFontSize = font.getSize2D();
        
        // Remove line border and set empty border for messageField
        messageField.setBorder(BorderFactory.createEmptyBorder(
                UiTokens.SPACE_3, UiTokens.SPACE_3, UiTokens.SPACE_2, UiTokens.SPACE_3));
        messageField.setOpaque(false); // Make it non-opaque to use GeminiBorderPanel's background
        messageField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent event) {
                geminiBorderPanel.setFocused(true);
            }

            @Override
            public void focusLost(java.awt.event.FocusEvent event) {
                geminiBorderPanel.setFocused(false);
            }
        });
        
        InputBoxIntellisense intellisense = new InputBoxIntellisense(messageField);
        intellisense.setPromptLibrary(promptLibrary());

        // -----------------------------------------------------------------------
        // IME (Input Method Editor) awareness – fixes Chinese / Japanese / Korean
        // input on Windows 11.
        //
        // When the user types with an IME (e.g. Pinyin for Simplified Chinese)
        // the keyPressed ENTER event fires twice:
        //   1. While the candidate popup is open  → should confirm the character
        //   2. After the character is committed    → should send the message
        //
        // Without tracking composition state, the first ENTER is swallowed by
        // the KeyAdapter below and sendMessage() is called prematurely, breaking
        // Chinese input entirely.
        // -----------------------------------------------------------------------
        messageField.addInputMethodListener(
            new InputMethodListener() {
                @Override
                public void inputMethodTextChanged(InputMethodEvent event) {
                    AttributedCharacterIterator text = event.getText();
                    if (text != null) {
                        int composingChars =
                            (text.getEndIndex() - text.getBeginIndex()) -
                            event.getCommittedCharacterCount();
                        imeComposing = composingChars > 0;
                    } else {
                        imeComposing = false;
                    }
                }

                @Override
                public void caretPositionChanged(InputMethodEvent event) {
                    // no-op – only composition changes matter
                }
            }
        );

        messageField.addKeyListener(
            new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    // Only send on ENTER when:
                    //  - Shift is NOT held (plain Enter, not Shift+Enter newline)
                    //  - IME is NOT composing (avoid swallowing IME confirm key)
                    if (
                        e.getKeyCode() == KeyEvent.VK_ENTER &&
                        !e.isShiftDown() &&
                        !imeComposing
                    ) {
                        e.consume();
                        sendMessage();
                    }
                }
            }
        );

        JScrollPane messageScrollPane = new JScrollPane(messageField);
        messageScrollPane.setBorder(BorderFactory.createEmptyBorder());
        messageScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        messageScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        messageScrollPane.setOpaque(false);
        messageScrollPane.getViewport().setOpaque(false);
        geminiBorderPanel.add(messageScrollPane, BorderLayout.CENTER);

        messageField.setTransferHandler(new AttachmentTransferHandler(
                messageField.getTransferHandler(), attachmentBar::addFileAsync));

        // Options area below the text box keeps model selection and reasoning
        // beside the composer, with input actions and status on the final row.
        // The trailing Send button swaps to Stop while the AI is processing.
        QuietButton attachButton = new QuietButton("").iconOnly();
        inputOptionsRow = new InputOptionsRow(
                this, attachmentBar, attachButton, this::sendMessage);
        inputOptionsRow.setModelRow(createToolbarRow());
        inputOptionsRow.addOption(treeNavigationButtons.getUpButton());
        inputOptionsRow.addOption(treeNavigationButtons.getDownButton());
        inputOptionsRow.setStatsComponent(contextStatsLabel);
        geminiBorderPanel.add(inputOptionsRow, BorderLayout.SOUTH);

        return geminiBorderPanel;
    }

    /**
     * Creates the header panel with title and new chat button.
     *
     * @return The header panel
     */
    private JPanel createHeaderPanel() {
        headerPanel = new JPanel(new BorderLayout());
        headerPanel.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                updateHeaderDensity(headerPanel.getWidth());
            }
        });
        headerPanel.setBorder(BorderFactory.createEmptyBorder(
                UiTokens.SPACE_2, UiTokens.SPACE_3, 0, UiTokens.SPACE_3));
        headerPanel.setBackground(ThemeColors.surface());

        JPanel titleRow = new JPanel(new BorderLayout(UiTokens.SPACE_3, 0));
        titleRow.setOpaque(false);
        titleRow.setBorder(BorderFactory.createEmptyBorder(0, 0, UiTokens.SPACE_2, 0));

        JPanel identityPanel = new JPanel();
        identityPanel.setLayout(new BoxLayout(identityPanel, BoxLayout.X_AXIS));
        identityPanel.setOpaque(false);
        URL markResource = AiChatPanel.class.getResource(
                "/org/qainsights/jmeter/ai/featherwand-22x22.png");
        JLabel mark = new JLabel(markResource == null ? null : new ImageIcon(markResource));
        mark.setAlignmentY(Component.CENTER_ALIGNMENT);
        mark.getAccessibleContext().setAccessibleName("Feather Wand");
        identityPanel.add(mark);
        identityPanel.add(Box.createRigidArea(new Dimension(UiTokens.SPACE_2, 0)));

        titleStack = new JPanel();
        titleStack.setLayout(new BoxLayout(titleStack, BoxLayout.Y_AXIS));
        titleStack.setOpaque(false);
        JLabel titleLabel = new JLabel("Feather Wand");
        titleLabel.setFont(UiTokens.title(titleLabel.getFont()));
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        versionLabel = new JLabel("JMeter Agent · v" + VersionUtils.getVersion());
        versionLabel.setFont(UiTokens.caption(versionLabel.getFont()));
        versionLabel.setForeground(ThemeColors.secondaryText());
        versionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        titleStack.add(titleLabel);
        titleStack.add(versionLabel);
        titleStack.setAlignmentY(Component.CENTER_ALIGNMENT);
        identityPanel.add(titleStack);
        titleRow.add(identityPanel, BorderLayout.WEST);
        recordingEnabled = Boolean.parseBoolean(
                AiConfig.getProperty("jmeter.ai.record.enabled", "false"));
        if (recordingEnabled) {
            org.qainsights.jmeter.ai.record.RecordingSessionController recController =
                org.qainsights.jmeter.ai.record.RecordingSessionController.getInstance();
            org.qainsights.jmeter.ai.record.RecordingArtifactStore recStore =
                new org.qainsights.jmeter.ai.record.RecordingArtifactStore();
            recordingControl =
                new org.qainsights.jmeter.ai.record.RecordingControlPanel(recController, recStore);
        } else {
            // Discovery-only chip when recording is off; does not start sessions
            recordingControl = org.qainsights.jmeter.ai.record.DisabledRecordChip.create();
        }

        JPanel headerActions = createHeaderActionsPanel();
        titleRow.add(headerActions, BorderLayout.EAST);

        headerPanel.add(titleRow, BorderLayout.CENTER);
        headerSeparator = new JSeparator();
        headerSeparator.setForeground(ThemeColors.separator());
        headerPanel.add(headerSeparator, BorderLayout.SOUTH);
        return headerPanel;
    }

    private void openDonateLink() {
        try {
            Desktop.getDesktop().browse(new URI(Constants.DONATE_LINK));
        } catch (Exception e) {
            log.error("Failed to open donate link", e);
        }
    }

    private JPanel createHeaderActionsPanel() {
        JPanel panel = new JPanel(new FlowLayout(
                FlowLayout.RIGHT, UiTokens.HEADER_ACTION_GAP, 0));
        panel.setOpaque(false);
        panel.add(recordingControl);

        donateButton = new DonateButton();
        donateButton.addActionListener(e -> openDonateLink());
        panel.add(donateButton);

        SessionMenuButton sessionMenu = new SessionMenuButton(this, this::buildSession);
        panel.add(sessionMenu);

        QuietButton newChatButton = createStyledButton("", 16);
        newChatButton.iconOnly(UiTokens.HEADER_CONTROL_HEIGHT);
        newChatButton.setIcon(ActionIcons.plus(16));
        newChatButton.setToolTipText("Start a new conversation");
        newChatButton.getAccessibleContext().setAccessibleName("Start a new conversation");
        newChatButton.addActionListener(e -> startNewConversation());
        panel.add(newChatButton);
        return panel;
    }

    private void updateHeaderDensity(int width) {
        if (donateButton == null) {
            return;
        }
        boolean narrow = width < UiTokens.HEADER_COMPACT_WIDTH;
        if (recordingControl != null) {
            recordingControl.setVisible(!narrow || recordingEnabled);
        }
        if (titleStack != null) {
            titleStack.setVisible(!narrow || !recordingEnabled);
        }
    }

    /**
     * Creates a quiet rounded button with the requested label size.
     *
     * @param text     the button label
     * @param fontSize the bold font size
     * @return the configured JButton
     */
    private QuietButton createStyledButton(String text, int fontSize) {
        QuietButton button = new QuietButton(text);
        button.setFont(button.getFont().deriveFont(Font.BOLD, fontSize));
        if (text == null || text.isEmpty()) {
            button.iconOnly();
        }

        // Subtle theme-aware hover tint and focus treatment are supplied
        // by the shared quiet button primitive for every look-and-feel.
        return button;
    }

    private AiServiceHolder getServiceHolder() {
        AiServiceHolder holder = new AiServiceHolder();
        holder.setClaudeService(claudeService);
        holder.setOpenAiService(openAiService);
        holder.setOllamaService(ollamaService);
        holder.setDeepseekService(deepseekService);
        holder.setGoogleService(googleService);
        holder.setGrokService(grokService);
        holder.setMetaMuseService(metaMuseService);
        holder.setBedrockService(bedrockService);
        holder.setCodexService(codexService);
        holder.setClaudeCodeService(claudeCodeService);
        return holder;
    }

    /**
     * Loads the available models in the background.
     */
    private void loadModelsInBackground() {
        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() {
                return Models.loadAllModels(getServiceHolder());
            }

            @Override
            protected void done() {
                try {
                    List<String> models = get();
                    // Get the default model ID
                    String defaultModelId = claudeService.getCurrentModel();
                    log.info("Default model ID: {}", defaultModelId);
                    modelSelectorPanel.setModels(models, defaultModelId);
                    // A restored session carries its model; reselect it now that
                    // the list is available (no-op when the model is gone).
                    if (restoredSessionModel != null) {
                        modelSelectorPanel.applyIfAvailable(restoredSessionModel);
                    }
                } catch (Exception e) {
                    log.error("Failed to load models", e);
                }
            }
        }
            .execute();
    }

    /**
     * The subscription CLI providers offered in the model picker footer. A
     * provider that is switched off in the properties is hidden entirely.
     */
    private List<SubscriptionCliProvider> enabledCliProviders() {
        List<SubscriptionCliProvider> providers = new ArrayList<>();
        for (CliSubscriptionAiService service : List.of(codexService, claudeCodeService)) {
            if (service.getProvider().isEnabled()) {
                providers.add(service.getProvider());
            }
        }
        return providers;
    }

    /**
     * Displays a welcome message in the chat area.
     */
    private void displayWelcomeMessage() {
        log.info("Displaying welcome message");
        transcript.showWelcome(WelcomeMessages.forCurrentConfig());
    }

    /** The session attachment registry (package-private for tests). */
    AttachmentRegistry attachmentRegistry() {
        return attachmentRegistry;
    }

    /** The shared prompt library, loaded on first use (package-private for tests). */
    org.qainsights.jmeter.ai.service.prompts.PromptLibrary promptLibrary() {
        if (promptLibrary == null) {
            promptLibrary = org.qainsights.jmeter.ai.service.prompts.PromptLibrary.load();
        }
        return promptLibrary;
    }

    /**
     * Starts a new conversation by clearing the chat area and conversation history.
     */
    void startNewConversation() {
        log.info("Starting new conversation");

        // Archive the outgoing session (autosave keeps it current), then start a fresh id
        conversationTracker.rotate(modelSelectorPanel.getSelectedModel(), attachmentRegistry.all());
        usageStats.reset();
        refreshContextStats();

        // Clear the transcript
        transcript.clearTranscript();

        // Clear pending attachments and the registry
        attachmentBar.clear();
        attachmentRegistry.clear();

        // Reset the last command type
        lastCommandType = LastCommandType.NONE;

        // Display welcome message
        displayWelcomeMessage();
    }

    /**
     * Sends the message from the input field to the chat.
     */
    private void sendMessage() {
        String text = messageField.getText().trim();
        if (attachmentBar.hasAttachments()) {
            text += attachmentBar.consumeMarkers();
        }
        commandDispatcher.dispatch(text);
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
        runOnEdt(() -> {
            log.info("Removing loading indicator");
            transcript.hideThinking();
        });
    }

    /**
     * Processes an AI response and displays it in the chat area.
     *
     * @param response The AI response to process
     */
    @Override
    public void processAiResponse(String response) {
        runOnEdt(() -> {
            if (response == null || response.isEmpty()) {
                transcript.addSystemMessage(
                    "No response from AI. Please try again.",
                    ThemeColors.error()
                );
                log.warn("Empty AI response");
                return;
            }

            log.info(
                "Processing AI response: {}",
                response.substring(0, Math.min(100, response.length()))
            );

            // Add the AI response to the chat
            log.info("Appending AI response to chat");
            JScrollPane scrollPane = ChatScroller.scrollPaneOf(transcript);
            boolean wasPinned = ChatScroller.isPinnedToBottom(scrollPane);
            transcript.addAssistantMessage(response);

            // Create element buttons for context-aware suggestions after the AI response
            log.info("Creating element buttons for context-aware suggestions");

            // Make sure the navigation panel is visible
            navigationPanel.setVisible(true);

            // Ensure the navigation panel is visible and properly laid out
            navigationPanel.revalidate();
            navigationPanel.repaint();

            // Log the number of components in the navigation panel
            log.info(
                "Navigation panel now has {} components",
                navigationPanel.getComponentCount()
            );

            // Scroll to the bottom of the chat area to show the latest message
            SwingUtilities.invokeLater(() -> {
                ChatScroller.scrollToBottomIfPinned(scrollPane, wasPinned);
                playResponseChime();
                refreshContextStats();
            });
        });
    }

    // -------------------------------------------------------------------------
    // CommandCallback implementation
    // -------------------------------------------------------------------------

    @Override
    public void setInputEnabled(boolean enabled) {
        messageField.setEnabled(enabled);
        if (inputOptionsRow != null) {
            inputOptionsRow.setSendEnabled(enabled);
        }
        if (geminiBorderPanel != null) {
            geminiBorderPanel.setThinking(!enabled);
        }
        if (enabled) {
            messageField.requestFocusInWindow();
        }
    }

    /** Lets the pet know the AI is (or is no longer) thinking; no-op when the pet is off. */
    private void setPetBusy(boolean busy) {
        org.qainsights.jmeter.ai.pet.PetAnimator animator =
                org.qainsights.jmeter.ai.pet.PetBootstrap.animator();
        if (animator != null) {
            if (busy) {
                animator.onBusyStarted();
            } else {
                animator.onBusyEnded();
            }
        }
    }

    @Override
    public void showStopButton() {
        setPetBusy(true);
        SwingUtilities.invokeLater(() -> inputOptionsRow.showStop(() -> {
            if (currentCancelHandle != null) {
                currentCancelHandle.run();
                appendMessageToChat("\nResponse stopped.");
                hideStopButton();
                setInputEnabled(true);
                removeLoadingIndicator();
            }
        }));
    }

    @Override
    public void hideStopButton() {
        setPetBusy(false);
        SwingUtilities.invokeLater(() -> {
            inputOptionsRow.hideStop();
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
            JScrollPane scrollPane = ChatScroller.scrollPaneOf(transcript);
            boolean wasPinned = ChatScroller.isPinnedToBottom(scrollPane);
            transcript.appendStreamToken(token);
            ChatScroller.scrollToBottomIfPinned(scrollPane, wasPinned);
        });
    }

    @Override
    public void onStreamComplete(String fullResponse) {
        SwingUtilities.invokeLater(() -> {
            playResponseChime();
            // Capture the pinned state before the re-render changes the
            // transcript height (and with it the scrollbar maximum).
            JScrollPane scrollPane = ChatScroller.scrollPaneOf(transcript);
            boolean wasPinned = ChatScroller.isPinnedToBottom(scrollPane);
            transcript.finishReasoning();
            // Re-render the streamed card with full markdown so code blocks
            // get their styled panel with the Copy button.
            transcript.completeStream(fullResponse);
            ChatScroller.scrollToBottomIfPinned(scrollPane, wasPinned);
            firstTokenReceived = false;
            hideStopButton();
            setInputEnabled(true);
            refreshContextStats();
        });
    }

    @Override
    public void onStreamError(
        String logMessage,
        Exception e,
        String userMessage
    ) {
        SwingUtilities.invokeLater(() -> {
            firstTokenReceived = false;
            hideStopButton();
            onWorkerError(logMessage, e, userMessage);
        });
    }

    @Override
    public Runnable getAiStreamResponse(
        String message,
        java.util.function.Consumer<String> tokenConsumer,
        Runnable onComplete,
        java.util.function.Consumer<Exception> onError
    ) {
        firstTokenReceived = false;
        Runnable cancelHandle = aiResponseRouter.generateStreamResponse(
            modelSelectorPanel.getSelectedModel(),
            conversationTracker.historyCopy(),
            tokenConsumer,
            this::appendReasoningToken,
            onComplete,
            onError
        );
        currentCancelHandle = cancelHandle;
        return cancelHandle;
    }

    /** Routes a streamed reasoning token into the transcript's thinking card (on the EDT). */
    @Override
    public void appendReasoningToken(String token) {
        SwingUtilities.invokeLater(() -> {
            if (!firstTokenReceived) {
                removeLoadingIndicator();
                firstTokenReceived = true;
            }
            JScrollPane scrollPane = ChatScroller.scrollPaneOf(transcript);
            boolean wasPinned = ChatScroller.isPinnedToBottom(scrollPane);
            transcript.appendReasoningToken(token);
            ChatScroller.scrollToBottomIfPinned(scrollPane, wasPinned);
        });
    }

    @Override
    public void clearMessageField() {
        messageField.setText("");
    }

    @Override
    public void appendUserMessage(String message) {
        runOnEdt(() -> {
            // Render as a user bubble; strip the legacy "You: " prefix that
            // CommandDispatcher still prepends (the card shows its own header).
            String body = message.startsWith("You: ")
                ? message.substring(5)
                : message;
            transcript.addUserMessage(body);
        });
    }

    @Override
    public void appendLoadingIndicator() {
        runOnEdt(() -> {
            transcript.showThinking();
        });
    }

    @Override
    public void appendRedMessage(String message) {
        runOnEdt(() -> {
            transcript.addSystemMessage(message, ThemeColors.error());
        });
    }

    @Override
    public String getSelectedModel() {
        return modelSelectorPanel.getSelectedModel();
    }

    @Override
    public List<String> getConversationHistory() {
        return conversationTracker.history();
    }

    @Override
    public void addToConversationHistory(String entry) {
        conversationTracker.addTurn(entry, modelSelectorPanel.getSelectedModel(), attachmentRegistry.all());
        refreshContextStats();
    }

    @Override
    public List<String> resolveAttachmentMarkers(List<String> turns) {
        return turns.stream()
                .map(attachmentRegistry::resolveInlineMarkers)
                .collect(java.util.stream.Collectors.toList());
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
        return aiResponseRouter.getAiResponse(
            modelSelectorPanel.getSelectedModel(),
            conversationTracker.historyCopy()
        );
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

        // Update transcript font (propagated to every message card)
        Font currentChatFont = transcript.getFont();
        float newChatSize = baseChatFontSize * scale;
        transcript.applyFont(currentChatFont.deriveFont(newChatSize));

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
        runOnEdt(() -> {
            transcript.addSystemMessage(message, null);
        });
    }

    @Override
    public void appendToolActivity(String message) {
        runOnEdt(() -> {
            transcript.addToolActivity(message);
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
        runOnEdt(() -> {
            transcript.addSystemMessage(
                context + ": " + e.getMessage(),
                ThemeColors.error()
            );
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
        runOnEdt(() -> {
            transcript.addSystemMessage(
                "Redo isn't supported for @wrap. Run @wrap again if needed.",
                ThemeColors.info()
            );
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
        runOnEdt(() -> transcript.finishReasoning());
        showNonStreamReasoning();
        processAiResponse(response);
        playResponseChime();
        setInputEnabled(true);
    }

    /**
     * Renders the reasoning captured by the service from a non-streaming
     * response (Claude thinking block, Ollama thinking, DeepSeek
     * reasoning_content) as an already-collapsed thinking card.
     */
    private void showNonStreamReasoning() {
        AiService service = resolveAiService(getSelectedModel());
        if (service == null) {
            return;
        }
        String reasoning = service.consumeLastReasoning();
        if (reasoning != null && !reasoning.isBlank()) {
            runOnEdt(() -> transcript.addReasoningBlock(reasoning));
        }
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
    public void onWorkerError(
        String logMessage,
        Exception e,
        String userMessage
    ) {
        log.error(logMessage, e);
        runOnEdt(() -> {
            removeLoadingIndicator();
            transcript.addSystemMessage(userMessage, ThemeColors.error());
            setInputEnabled(true);
        });
    }

    /**
     * Gets a color from the current UIManager theme, falling back to a default if not available.
     *
     * @param key      The UIManager color key
     * @param fallback The fallback color if the key is not found
     * @return The theme color or the fallback
     */

    private static final URL CHIME_RESOURCE = AiChatPanel.class.getResource(
            "/org/qainsights/jmeter/ai/sound/jmeter-chime.wav");

    private void playResponseChime() {
        if (!AiConfig.isResponseChimeEnabled()) {
            return;
        }
        try {
            if (CHIME_RESOURCE != null) {
                AudioInputStream audioIn = AudioSystem.getAudioInputStream(CHIME_RESOURCE);
                Clip clip = AudioSystem.getClip();
                clip.open(audioIn);
                clip.start();
            } else {
                log.debug("Chime WAV not found, falling back to system beep");
                Toolkit.getDefaultToolkit().beep();
            }
        } catch (Exception e) {
            log.debug("Could not play response chime", e);
        }
    }

    private static Color getThemeColor(String key, Color fallback) {
        Color color = UIManager.getColor(key);
        return color != null ? color : fallback;
    }

    private void refreshChatColors() {
        Color newBg = ThemeColors.canvas();
        setBackground(newBg);
        transcript.setBackground(newBg);
        if (bottomPanel != null) {
            bottomPanel.setBackground(newBg);
        }
        if (headerPanel != null) {
            headerPanel.setBackground(ThemeColors.surface());
        }
        if (versionLabel != null) {
            versionLabel.setForeground(ThemeColors.secondaryText());
        }
        if (headerSeparator != null) {
            headerSeparator.setForeground(ThemeColors.separator());
        }
        JScrollPane scrollPane = ChatScroller.scrollPaneOf(transcript);
        if (scrollPane != null) {
            scrollPane.getViewport().setBackground(newBg);
        }
        transcript.refreshTheme();
        transcript.repaint();

        // Re-theme the composer and its animated border so they never keep
        // stale colors after a light/dark theme switch.
        if (geminiBorderPanel != null) {
            geminiBorderPanel.applyThemeBackground();
        }
        if (inputOptionsRow != null) {
            inputOptionsRow.applyTheme();
        }
        if (reasoningControls != null) {
            reasoningControls.applyTheme();
        }
        if (attachmentBar != null) {
            attachmentBar.applyTheme();
        }
        if (messageField != null) {
            messageField.setBackground(ThemeColors.elevatedSurface());
            Color textColor = getThemeColor("TextArea.foreground", ThemeColors.foreground());
            messageField.setForeground(textColor);
            messageField.setCaretColor(textColor);
        }
    }

    private void runOnEdt(Runnable runnable) {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
        } else {
            SwingUtilities.invokeLater(runnable);
        }
    }
}
