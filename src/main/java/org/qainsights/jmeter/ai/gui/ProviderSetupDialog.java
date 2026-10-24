package org.qainsights.jmeter.ai.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.KeyStroke;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.gui.theme.UiTokens;
import org.qainsights.jmeter.ai.apple.AppleFoundationHelper;
import org.qainsights.jmeter.ai.agent.AgentJudgmentConfig;
import org.qainsights.jmeter.ai.agent.AgentRoutingConfig;
import org.qainsights.jmeter.ai.agent.AgentTriageConfig;
import org.qainsights.jmeter.ai.service.OllamaEmbeddingClient;
import org.qainsights.jmeter.ai.service.ProviderConnectionTester;
import org.qainsights.jmeter.ai.service.ProviderSetupOption;
import org.qainsights.jmeter.ai.service.ProviderStatus;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.qainsights.jmeter.ai.utils.SafeText;
import org.qainsights.jmeter.ai.utils.UserPropertiesWriter;

final class ProviderSetupDialog extends JDialog {
    private static final String DEFAULT_OLLAMA_HOST = "http://localhost";
    private static final String DEFAULT_OLLAMA_PORT = "11434";
    private static final String DEFAULT_OLLAMA_MODEL = "deepseek-r1:1.5b";
    private static final String SAVED_KEY_PLACEHOLDER =
            "Saved key. Paste a replacement.";

    private final Consumer<ProviderSetupOption> onSaved;
    private final ProviderConnectionTester connectionTester;
    private final Path propertiesPath;
    private final Runnable testCompletionCallback;
    private final JPanel contentCard = new JPanel(new BorderLayout());
    private final JPanel providerRows = new JPanel();
    private final JPanel routingRows = new JPanel();
    private JPanel providerNavigation;
    private JScrollPane navigationScrollPane;
    private final JTextArea statusText = createStatusText();
    private JPanel statusPanel;
    private final QuietButton testButton = new QuietButton("Test connection", QuietButton.Kind.OUTLINED);
    private final QuietButton saveButton = new QuietButton("Save and connect", QuietButton.Kind.PRIMARY);
    private final QuietButton closeButton = new QuietButton("Close", QuietButton.Kind.GHOST);
    private final QuietButton copySettingsButton = new QuietButton("Copy settings", QuietButton.Kind.OUTLINED);
    private final List<ProviderRow> rows;
    private final BooleanSupplier appleSupportedPlatform;
    private final Supplier<AppleFoundationHelper.Status> appleStatusSupplier;

    private ProviderSetupOption selectedOption;
    private PlaceholderPasswordField keyField;
    private JTextField ollamaHostField;
    private JTextField ollamaPortField;
    private JComboBox<String> ollamaModelCombo;
    private String prefilledOllamaModel;
    private boolean resultCurrent;
    private boolean testing;
    private ProviderConnectionTester.Result lastResult;
    private LinkedHashMap<String, String> lastSettings = new LinkedHashMap<>();
    private String smartBackend;
    private boolean routingSelection;
    private boolean lastTriageChoice = true;
    private JCheckBox routingEnabled;
    private JCheckBox triageEnabled;
    private JLabel triageHint;
    private JLabel savedOpenAiKeyHint;
    private final Map<String, PlaceholderPasswordField> smartKeyFields = new LinkedHashMap<>();
    private boolean appleAvailable;
    private boolean appleStatusReady;

    static JTextArea createStatusText() {
        JTextArea text = new JTextArea(" ");
        text.setFont(UiTokens.body(UIManager.getFont("Label.font")));
        return text;
    }

    ProviderSetupDialog(Window owner, Consumer<ProviderSetupOption> onSaved, boolean ollamaDetected) {
        this(owner, onSaved, ollamaDetected, new ProviderConnectionTester(), UserPropertiesWriter.resolve());
    }

    ProviderSetupDialog(
            Window owner,
            Consumer<ProviderSetupOption> onSaved,
            boolean ollamaDetected,
            ProviderConnectionTester connectionTester,
            Path propertiesPath) {
        this(owner, onSaved, ollamaDetected, connectionTester, propertiesPath, () -> { });
    }

    ProviderSetupDialog(
            Window owner,
            Consumer<ProviderSetupOption> onSaved,
            boolean ollamaDetected,
            ProviderConnectionTester connectionTester,
            Path propertiesPath,
            Runnable testCompletionCallback) {
        this(owner, onSaved, ollamaDetected, connectionTester, propertiesPath, testCompletionCallback,
                () -> AppleFoundationHelper.isSupportedPlatform(
                        System.getProperty("os.name", ""), System.getProperty("os.arch", "")),
                AppleFoundationHelper::status);
    }

    ProviderSetupDialog(
            Window owner,
            Consumer<ProviderSetupOption> onSaved,
            boolean ollamaDetected,
            ProviderConnectionTester connectionTester,
            Path propertiesPath,
            Runnable testCompletionCallback,
            BooleanSupplier appleSupportedPlatform,
            Supplier<AppleFoundationHelper.Status> appleStatusSupplier) {
        super(owner, "Connect a provider", ModalityType.APPLICATION_MODAL);
        this.onSaved = onSaved == null ? option -> { } : onSaved;
        this.connectionTester = connectionTester;
        this.propertiesPath = propertiesPath;
        this.testCompletionCallback = testCompletionCallback == null ? () -> { } : testCompletionCallback;
        this.appleSupportedPlatform = appleSupportedPlatform == null ? () -> false : appleSupportedPlatform;
        this.appleStatusSupplier = appleStatusSupplier == null ? AppleFoundationHelper::status : appleStatusSupplier;
        smartBackend = configuredSmartBackend();
        routingSelection = initialRoutingSelection(
                smartBackend, configuredFlag(AgentRoutingConfig.ROUTING_ENABLED_KEY, false));
        lastTriageChoice = initialTriageChoice(
                smartBackend, configuredFlag(AgentTriageConfig.TRIAGE_ENABLED_KEY, false));
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel content = new JPanel(new BorderLayout(UiTokens.SPACE_4, UiTokens.SPACE_4));
        content.setBackground(ThemeColors.canvas());
        content.setBorder(BorderFactory.createEmptyBorder(
                UiTokens.SPACE_5, UiTokens.SPACE_5, UiTokens.SPACE_4, UiTokens.SPACE_5));
        content.add(buildHeader(), BorderLayout.NORTH);
        content.add(buildBody(), BorderLayout.CENTER);
        content.add(buildFooter(), BorderLayout.SOUTH);
        setContentPane(content);

        providerRows.setLayout(new BoxLayout(providerRows, BoxLayout.Y_AXIS));
        routingRows.setLayout(new BoxLayout(routingRows, BoxLayout.Y_AXIS));
        List<ProviderRow> allRows = new java.util.ArrayList<>();
        int rowIndex = 0;
        for (ProviderSetupOption option : navigationProviderOptions(this.appleSupportedPlatform)) {
            int index = rowIndex++;
            ProviderRow row = new ProviderRow(option);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.setActionHandlers(
                    () -> selectOption(option),
                    () -> focusProviderRow(index - 1),
                    () -> focusProviderRow(index + 1));
            providerRows.add(row);
            providerRows.add(Box.createVerticalStrut(UiTokens.SPACE_2));
            allRows.add(row);
        }
        ProviderRow routingRow = new ProviderRow(ProviderSetupOption.SMART_ROUTING);
        routingRow.setHintText(smartRoutingHint());
        int routingIndex = rowIndex;
        routingRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        routingRow.setActionHandlers(
                () -> selectOption(ProviderSetupOption.SMART_ROUTING),
                () -> focusProviderRow(routingIndex - 1),
                () -> focusProviderRow(routingIndex + 1));
        routingRows.add(routingRow);
        allRows.add(routingRow);
        rows = List.copyOf(allRows);
        Dimension navigationSize = providerNavigation.getPreferredSize();
        providerNavigation.setPreferredSize(new Dimension(
                Math.max(236, navigationSize.width), navigationSize.height));
        Rectangle usableScreen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        int maxNavigationHeight = Math.max(UiTokens.SPACE_4 * 4, usableScreen.height - UiTokens.SPACE_4 * 6);
        int preferredNavigationHeight = Math.min(navigationSize.height, maxNavigationHeight);
        navigationScrollPane.setPreferredSize(new Dimension(
                Math.max(236, navigationSize.width),
                preferredNavigationHeight));
        providerNavigation.setPreferredSize(new Dimension(
                Math.max(236, navigationSize.width), preferredNavigationHeight));

        copySettingsButton.setVisible(false);
        copySettingsButton.addActionListener(e -> copySettings());
        testButton.addActionListener(e -> startTest(false));
        saveButton.addActionListener(e -> saveOrTest());
        closeButton.addActionListener(e -> dispose());

        selectOption(ollamaDetected ? ProviderSetupOption.OLLAMA : ProviderSetupOption.ANTHROPIC);
        pack();
        setMinimumSize(new Dimension(790, 500));
        setSize(Math.min(Math.max(850, getPreferredSize().width), usableScreen.width),
                Math.min(Math.max(550, getPreferredSize().height), usableScreen.height));
        setLocationRelativeTo(owner);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent event) {
                pack();
                setSize(Math.min(getWidth(), usableScreen.width), Math.min(getHeight(), usableScreen.height));
                setLocationRelativeTo(owner);
            }
        });
        if (ollamaDetected) {
            startTest(false);
        }
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("Connect a provider");
        title.setFont(UiTokens.title(title.getFont()));
        title.setForeground(ThemeColors.foreground());
        JLabel subtitle = new JLabel(
                "Paste a key and Feather Wand saves it to JMeter's user.properties. "
                        + "It works right away, no restart needed.");
        subtitle.setFont(UiTokens.body(subtitle.getFont()));
        subtitle.setForeground(ThemeColors.secondaryText());
        subtitle.setBorder(BorderFactory.createEmptyBorder(UiTokens.SPACE_1, 0, 0, 0));
        header.add(title);
        header.add(subtitle);
        return header;
    }

    private JPanel buildBody() {
        JPanel body = new JPanel(new BorderLayout(UiTokens.SPACE_4, 0));
        body.setOpaque(false);
        JPanel navigation = new JPanel(new BorderLayout());
        providerNavigation = navigation;
        navigation.setOpaque(false);
        JPanel navigationContent = new JPanel();
        navigationContent.setOpaque(false);
        navigationContent.setLayout(new BoxLayout(navigationContent, BoxLayout.Y_AXIS));
        JLabel providerHeading = new JLabel("PROVIDERS");
        providerHeading.setFont(UiTokens.label(providerHeading.getFont()));
        providerHeading.setForeground(ThemeColors.secondaryText());
        providerHeading.setBorder(BorderFactory.createEmptyBorder(0, UiTokens.SPACE_2, UiTokens.SPACE_2, 0));
        navigationContent.add(providerHeading);
        providerRows.setOpaque(false);
        navigationContent.add(providerRows);
        JLabel routingHeading = new JLabel("SMART ROUTING");
        routingHeading.setFont(UiTokens.label(routingHeading.getFont()));
        routingHeading.setForeground(ThemeColors.secondaryText());
        routingHeading.setBorder(BorderFactory.createEmptyBorder(
                UiTokens.SPACE_3, UiTokens.SPACE_2, UiTokens.SPACE_2, 0));
        navigationContent.add(routingHeading);
        routingRows.setOpaque(false);
        navigationContent.add(routingRows);
        navigationScrollPane = new JScrollPane(navigationContent);
        navigationScrollPane.setBorder(BorderFactory.createEmptyBorder());
        navigationScrollPane.setOpaque(false);
        navigationScrollPane.getViewport().setOpaque(false);
        navigationScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        navigationScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        navigation.add(navigationScrollPane, BorderLayout.CENTER);

        contentCard.setOpaque(true);
        contentCard.setBackground(ThemeColors.surface());
        contentCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ThemeColors.separator()),
                BorderFactory.createEmptyBorder(UiTokens.SPACE_4, UiTokens.SPACE_4,
                        UiTokens.SPACE_4, UiTokens.SPACE_4)));
        body.add(navigation, BorderLayout.WEST);
        body.add(contentCard, BorderLayout.CENTER);
        return body;
    }

    static List<ProviderSetupOption> navigationProviderOptions(BooleanSupplier appleSupported) {
        java.util.ArrayList<ProviderSetupOption> options = new java.util.ArrayList<>();
        for (ProviderSetupOption option : ProviderSetupOption.values()) {
            if (option.isChatProvider()) {
                options.add(option);
            } else if (option == ProviderSetupOption.APPLE_ON_DEVICE
                    && appleSupported != null && appleSupported.getAsBoolean()) {
                options.add(option);
            }
        }
        return List.copyOf(options);
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new BorderLayout(UiTokens.SPACE_2, 0));
        footer.setOpaque(false);
        JLabel note = new JLabel(
                "AWS Bedrock and corporate gateways are set in user.properties.");
        note.setForeground(ThemeColors.secondaryText());
        JButton quickstart = new JButton("See the quick-start file");
        quickstart.setBorderPainted(false);
        quickstart.setContentAreaFilled(false);
        quickstart.setFocusPainted(false);
        quickstart.setForeground(ThemeColors.accent());
        quickstart.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        quickstart.addActionListener(e -> openLink(WelcomeMessages.QUICKSTART_URL));
        JPanel noteLine = new JPanel(new FlowLayout(FlowLayout.LEFT, UiTokens.SPACE_1, 0));
        noteLine.setOpaque(false);
        noteLine.add(note);
        noteLine.add(quickstart);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiTokens.SPACE_2, 0));
        buttons.setOpaque(false);
        buttons.add(copySettingsButton);
        buttons.add(closeButton);
        buttons.add(saveButton);
        footer.add(noteLine, BorderLayout.CENTER);
        footer.add(buttons, BorderLayout.EAST);
        return footer;
    }

    private void focusProviderRow(int index) {
        if (index >= 0 && index < rows.size()) {
            ProviderRow row = rows.get(index);
            row.requestFocusInWindow();
            row.activate();
        }
    }

    void selectOption(ProviderSetupOption option) {
        selectedOption = option;
        resultCurrent = false;
        lastResult = null;
        for (ProviderRow row : rows) {
            row.setSelected(option == row.option());
        }
        contentCard.removeAll();
        if (option == ProviderSetupOption.SMART_ROUTING) {
            contentCard.add(buildSmartRoutingPane(), BorderLayout.CENTER);
            updateSmartBackendControls();
        } else if (option == ProviderSetupOption.APPLE_ON_DEVICE) {
            contentCard.add(buildApplePane(), BorderLayout.CENTER);
        } else {
            contentCard.add(buildProviderPane(option), BorderLayout.CENTER);
        }
        setStatus(" ", ThemeColors.secondaryText());
        copySettingsButton.setVisible(false);
        updateActionButtons();
        if (option == ProviderSetupOption.APPLE_ON_DEVICE) {
            probeAppleStatus();
        }
        contentCard.revalidate();
        contentCard.repaint();
    }

    private JPanel buildSmartRoutingPane() {
        JPanel pane = new JPanel(new BorderLayout());
        pane.setOpaque(false);
        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("Smart routing");
        title.setFont(UiTokens.heading(title.getFont()));
        title.setForeground(ThemeColors.foreground());
        JTextArea intro = new JTextArea(
                "Smart routing picks a focused tool set for each Agent Mode request, "
                        + "so the model sees fewer tools and answers faster. Failure triage groups failed samples by likely cause.",
                3, 58);
        intro.setEditable(false);
        intro.setFocusable(false);
        intro.setOpaque(false);
        intro.setLineWrap(true);
        intro.setWrapStyleWord(true);
        intro.setForeground(ThemeColors.secondaryText());
        intro.setFont(UiTokens.body(intro.getFont()));
        intro.setMaximumSize(new Dimension(Integer.MAX_VALUE, intro.getPreferredSize().height));
        addLeftAligned(heading, title);
        addLeftAligned(heading, Box.createVerticalStrut(UiTokens.SPACE_1));
        addLeftAligned(heading, intro);
        heading.setMaximumSize(new Dimension(Integer.MAX_VALUE, heading.getPreferredSize().height));
        addLeftAligned(content, heading);
        addLeftAligned(content, Box.createVerticalStrut(UiTokens.SPACE_4));

        JPanel choices = new JPanel();
        choices.setOpaque(false);
        choices.setLayout(new BoxLayout(choices, BoxLayout.Y_AXIS));
        ButtonGroup group = new ButtonGroup();
        addBackendChoice(group, choices, "off", "Off", "Off");
        addBackendChoice(group, choices, AgentJudgmentConfig.TYPESAFE,
                "Jev by TypeSafe", "Jev by TypeSafe");
        addBackendChoice(group, choices, AgentJudgmentConfig.OPENAI,
                "OpenAI Decisions", "OpenAI Decisions");
        addBackendChoice(group, choices, AgentJudgmentConfig.OPENROUTER,
                "<html>OpenRouter Decisions<br>(Microsoft-Decision-1)</html>",
                "OpenRouter Decisions (Microsoft-Decision-1)");
        addBackendChoice(group, choices, AgentJudgmentConfig.LOCAL,
                "EmbeddingGemma on this machine", "EmbeddingGemma on this machine");
        addBackendChoice(group, choices, AgentJudgmentConfig.OLLAMA,
                "Ollama embeddings", "Ollama embeddings");
        choices.setAlignmentX(Component.LEFT_ALIGNMENT);
        choices.setMaximumSize(new Dimension(Integer.MAX_VALUE, choices.getPreferredSize().height));
        addLeftAligned(content, choices);
        addLeftAligned(content, Box.createVerticalStrut(UiTokens.SPACE_2));

        JPanel details = new JPanel();
        details.setOpaque(false);
        details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
        details.add(smartKeyPanel(AgentJudgmentConfig.TYPESAFE,
                AgentRoutingConfig.API_KEY, "Jev by TypeSafe"));
        details.add(smartKeyPanel(AgentJudgmentConfig.OPENAI,
                AgentJudgmentConfig.OPENAI_API_KEY, "OpenAI Decisions"));
        details.add(smartKeyPanel(AgentJudgmentConfig.OPENROUTER,
                AgentJudgmentConfig.OPENROUTER_API_KEY, "OpenRouter Decisions"));
        JTextArea localHint = new JTextArea(
                "No API key. Downloads about 217 MB the first time it runs.", 2, 36);
        localHint.setEditable(false);
        localHint.setFocusable(false);
        localHint.setOpaque(false);
        localHint.setLineWrap(true);
        localHint.setWrapStyleWord(true);
        localHint.setForeground(ThemeColors.secondaryText());
        localHint.setFont(UiTokens.body(localHint.getFont()));
        details.add(detailPanel(AgentJudgmentConfig.LOCAL, localHint));
        JLabel ollamaHint = new JLabel("Uses the Ollama host already configured.");
        ollamaHint.setForeground(ThemeColors.secondaryText());
        details.add(detailPanel(AgentJudgmentConfig.OLLAMA, ollamaHint));
        details.setAlignmentX(Component.LEFT_ALIGNMENT);
        details.setMaximumSize(new Dimension(Integer.MAX_VALUE, details.getPreferredSize().height));
        addLeftAligned(content, details);

        routingEnabled = new JCheckBox("Route Agent Mode requests", routingSelection);
        triageEnabled = new JCheckBox(
                "Failure triage", triageSelectedForBackend(smartBackend, lastTriageChoice));
        routingEnabled.setOpaque(false);
        triageEnabled.setOpaque(false);
        JPanel toggles = new JPanel();
        toggles.setOpaque(false);
        toggles.setLayout(new BoxLayout(toggles, BoxLayout.Y_AXIS));
        addLeftAligned(toggles, routingEnabled);
        addLeftAligned(toggles, triageEnabled);
        triageHint = new JLabel("Not available with local embeddings");
        triageHint.setForeground(ThemeColors.secondaryText());
        triageHint.setBorder(BorderFactory.createEmptyBorder(0, UiTokens.SPACE_4, 0, 0));
        addLeftAligned(toggles, triageHint);
        toggles.setAlignmentX(Component.LEFT_ALIGNMENT);
        toggles.setMaximumSize(new Dimension(Integer.MAX_VALUE, toggles.getPreferredSize().height));
        addLeftAligned(content, Box.createVerticalStrut(UiTokens.SPACE_2));
        addLeftAligned(content, toggles);
        routingEnabled.addActionListener(e -> {
            routingSelection = routingEnabled.isSelected();
            inputChanged();
        });
        triageEnabled.addActionListener(e -> {
            lastTriageChoice = triageEnabled.isSelected();
            inputChanged();
        });

        JPanel bottom = buildStatusActions(true);
        addLeftAligned(content, Box.createVerticalStrut(UiTokens.SPACE_3));
        addLeftAligned(content, bottom);
        addLeftAligned(content, Box.createVerticalGlue());
        pane.add(content, BorderLayout.NORTH);
        return pane;
    }

    private static void addLeftAligned(JPanel parent, Component component) {
        if (component instanceof JComponent swingComponent) {
            swingComponent.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        parent.add(component);
    }

    private void addBackendChoice(
            ButtonGroup group,
            JPanel choices,
            String backend,
            String labelText,
            String accessibleName) {
        JRadioButton choice = new JRadioButton("", backend.equals(smartBackend));
        choice.setOpaque(false);
        choice.setAlignmentX(Component.LEFT_ALIGNMENT);
        choice.setForeground(ThemeColors.foreground());
        choice.setFont(UiTokens.body(choice.getFont()));
        choice.getAccessibleContext().setAccessibleName(accessibleName);
        group.add(choice);
        JLabel label = new JLabel(labelText, BrandLogo.icon(BrandLogo.logoForBackend(backend), 16),
                SwingConstants.LEFT);
        if (label.getIcon() == null) {
            label.setIcon(spacerIcon(16));
        }
        label.setIconTextGap(UiTokens.SPACE_2);
        label.setForeground(ThemeColors.foreground());
        label.setFont(UiTokens.body(label.getFont()));
        label.setLabelFor(choice);
        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getButton() == MouseEvent.BUTTON1) {
                    choice.doClick();
                }
            }
        });
        JPanel row = new JPanel(new BorderLayout(UiTokens.SPACE_1, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(choice, BorderLayout.WEST);
        row.add(label, BorderLayout.CENTER);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        choices.add(row);
        choice.addActionListener(e -> {
            if (!backend.equals(smartBackend)) {
                String previousBackend = smartBackend;
                smartBackend = backend;
                routingSelection = routingSelectionAfterBackendChange(
                        previousBackend, backend, routingSelection);
                routingEnabled.setSelected(routingSelection);
                triageEnabled.setSelected(triageSelectedForBackend(backend, lastTriageChoice));
                inputChanged();
                updateSmartBackendControls();
            }
        });
    }

    private static Icon spacerIcon(int size) {
        return new Icon() {
            @Override
            public void paintIcon(Component component, Graphics graphics, int x, int y) {
            }

            @Override
            public int getIconWidth() {
                return size;
            }

            @Override
            public int getIconHeight() {
                return size;
            }
        };
    }

    private JPanel smartKeyPanel(String backend, String property, String label) {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        JLabel name = new JLabel(label);
        name.setForeground(ThemeColors.foreground());
        name.setFont(UiTokens.label(name.getFont()));
        name.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(name);
        if (AgentJudgmentConfig.OPENAI.equals(backend)
                && ProviderStatus.isApiKeyConfigured(property)) {
            savedOpenAiKeyHint = new JLabel("Uses your saved OpenAI key");
            savedOpenAiKeyHint.setForeground(ThemeColors.secondaryText());
            savedOpenAiKeyHint.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(savedOpenAiKeyHint);
        } else {
            JPanel field = new JPanel(new BorderLayout());
            field.setOpaque(false);
            field.setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel keyLabel = new JLabel("API key");
            keyLabel.setForeground(ThemeColors.secondaryText());
            PlaceholderPasswordField input = new PlaceholderPasswordField();
            input.setPlaceholder(ProviderStatus.isApiKeyConfigured(property)
                    ? SAVED_KEY_PLACEHOLDER : "Paste your API key");
            styleInput(input);
            keyLabel.setLabelFor(input);
            field.add(keyLabel, BorderLayout.NORTH);
            field.add(input, BorderLayout.CENTER);
            smartKeyFields.put(backend, input);
            input.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent event) { inputChanged(); }
                @Override public void removeUpdate(DocumentEvent event) { inputChanged(); }
                @Override public void changedUpdate(DocumentEvent event) { inputChanged(); }
            });
            panel.add(field);
            if (AgentJudgmentConfig.OPENROUTER.equals(backend)) {
                JButton getKey = new JButton("Get a key");
                getKey.setBorderPainted(false);
                getKey.setContentAreaFilled(false);
                getKey.setFocusPainted(false);
                getKey.setMargin(new Insets(0, 0, 0, 0));
                getKey.setForeground(ThemeColors.accent());
                getKey.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                getKey.setAlignmentX(Component.LEFT_ALIGNMENT);
                getKey.addActionListener(e -> openLink("https://openrouter.ai/settings/keys"));
                panel.add(getKey);
            }
        }
        return detailPanel(backend, panel);
    }

    private JPanel detailPanel(String backend, Component detail) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(detail, BorderLayout.CENTER);
        panel.putClientProperty("backend", backend);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
        return panel;
    }

    private JPanel buildApplePane() {
        JPanel pane = new JPanel(new BorderLayout());
        pane.setOpaque(false);
        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        JPanel heading = new JPanel(new BorderLayout(UiTokens.SPACE_2, 0));
        heading.setOpaque(false);
        JLabel title = new JLabel("Apple on-device");
        title.setFont(UiTokens.heading(title.getFont()));
        title.setForeground(ThemeColors.foreground());
        JLabel description = new JLabel("No key, runs on this Mac");
        description.setForeground(ThemeColors.secondaryText());
        description.setBorder(BorderFactory.createEmptyBorder(UiTokens.SPACE_1, 0, 0, 0));
        JPanel titleCopy = new JPanel();
        titleCopy.setOpaque(false);
        titleCopy.setLayout(new BoxLayout(titleCopy, BoxLayout.Y_AXIS));
        addLeftAligned(titleCopy, title);
        addLeftAligned(titleCopy, description);
        heading.add(new JLabel(BrandLogo.tile(BrandLogo.logoFor(
                ProviderSetupOption.APPLE_ON_DEVICE), 36, 24)), BorderLayout.WEST);
        heading.add(titleCopy, BorderLayout.CENTER);
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        heading.setMaximumSize(new Dimension(Integer.MAX_VALUE, heading.getPreferredSize().height));
        addLeftAligned(content, heading);
        if (!Boolean.parseBoolean(AiConfig.getProperty("apple.enabled", "true"))) {
            JLabel disabled = new JLabel("Apple on-device is turned off. This button will turn it back on.");
            disabled.setForeground(ThemeColors.info());
            disabled.setBorder(BorderFactory.createEmptyBorder(UiTokens.SPACE_3, 0, 0, 0));
            addLeftAligned(content, disabled);
        }
        addLeftAligned(content, Box.createVerticalStrut(UiTokens.SPACE_3));
        addLeftAligned(content, buildStatusActions(false));
        JLabel requirements = new JLabel(
                "Requires Apple silicon, macOS 26 or later, and Apple Intelligence turned on.");
        requirements.setForeground(ThemeColors.secondaryText());
        requirements.setAlignmentX(Component.LEFT_ALIGNMENT);
        addLeftAligned(content, requirements);
        addLeftAligned(content, Box.createVerticalGlue());
        pane.add(content, BorderLayout.NORTH);
        return pane;
    }

    private JPanel buildStatusActions(boolean includeTestButton) {
        JPanel bottom = new JPanel(new BorderLayout(UiTokens.SPACE_2, UiTokens.SPACE_2));
        bottom.setOpaque(false);
        statusText.setEditable(false);
        statusText.setFocusable(false);
        statusText.setOpaque(false);
        statusText.setLineWrap(true);
        statusText.setWrapStyleWord(true);
        statusText.setBorder(BorderFactory.createEmptyBorder(UiTokens.SPACE_1, 0, 0, 0));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        actions.setOpaque(false);
        testButton.setVisible(includeTestButton);
        if (includeTestButton) {
            actions.add(testButton);
        }
        bottom.add(actions, BorderLayout.NORTH);
        bottom.add(statusText, BorderLayout.CENTER);
        bottom.setAlignmentX(Component.LEFT_ALIGNMENT);
        statusPanel = bottom;
        return bottom;
    }

    private void updateSmartBackendControls() {
        boolean local = AgentJudgmentConfig.LOCAL.equals(smartBackend)
                || AgentJudgmentConfig.OLLAMA.equals(smartBackend);
        boolean off = "off".equals(smartBackend);
        routingEnabled.setEnabled(!off);
        routingEnabled.setSelected(routingSelection);
        triageEnabled.setEnabled(triageSelectedForBackend(smartBackend, true));
        triageEnabled.setSelected(triageSelectedForBackend(smartBackend, lastTriageChoice));
        triageHint.setVisible(local);
        if (contentCard.getComponentCount() > 0) {
            setBackendPanelVisibility((Container) contentCard.getComponent(0), smartBackend);
        }
        updateActionButtons();
    }

    static boolean triageSelectedForBackend(String backend, boolean selected) {
        boolean supportsTriage = !"off".equals(backend)
                && !AgentJudgmentConfig.LOCAL.equals(backend)
                && !AgentJudgmentConfig.OLLAMA.equals(backend);
        return selected && supportsTriage;
    }

    static boolean initialRoutingSelection(String backend, boolean storedValue) {
        return !"off".equals(backend) && storedValue;
    }

    static boolean initialTriageChoice(String backend, boolean storedValue) {
        return "off".equals(backend) || storedValue;
    }

    static boolean routingSelectionAfterBackendChange(
            String previousBackend, String backend, boolean currentSelection) {
        if ("off".equals(backend)) {
            return false;
        }
        return "off".equals(previousBackend) || currentSelection;
    }

    private static void setBackendPanelVisibility(Container parent, String backend) {
        for (Component component : parent.getComponents()) {
            if (component instanceof JPanel panel
                    && panel.getClientProperty("backend") instanceof String panelBackend) {
                panel.setVisible(panelBackend.equals(backend));
            }
            if (component instanceof Container child) {
                setBackendPanelVisibility(child, backend);
            }
        }
    }

    private void updateSmartRoutingRow() {
        for (ProviderRow row : rows) {
            if (row.option() == ProviderSetupOption.SMART_ROUTING) {
                row.setHintText(smartRoutingHint());
                row.setConfigured(isSmartBackendConfigured());
                break;
            }
        }
    }

    private boolean isSmartBackendConfigured() {
        String property = switch (AgentJudgmentConfig.provider()) {
            case AgentJudgmentConfig.OPENAI -> AgentJudgmentConfig.OPENAI_API_KEY;
            case AgentJudgmentConfig.OPENROUTER -> AgentJudgmentConfig.OPENROUTER_API_KEY;
            default -> AgentRoutingConfig.API_KEY;
        };
        return AgentJudgmentConfig.LOCAL.equals(AgentJudgmentConfig.provider())
                || AgentJudgmentConfig.OLLAMA.equals(AgentJudgmentConfig.provider())
                || ProviderStatus.isApiKeyConfigured(property);
    }

    private String smartRoutingHint() {
        if (!Boolean.parseBoolean(AiConfig.getProperty(AgentRoutingConfig.TYPESAFE_ENABLED_KEY, "false"))
                || (!Boolean.parseBoolean(AiConfig.getProperty(AgentRoutingConfig.ROUTING_ENABLED_KEY, "false"))
                        && !Boolean.parseBoolean(AiConfig.getProperty(
                                "jmeter.ai.typesafe.agent.triage.enabled", "false")))) {
            return "Off";
        }
        return switch (AgentJudgmentConfig.provider()) {
            case AgentJudgmentConfig.OPENAI -> "OpenAI Decisions";
            case AgentJudgmentConfig.OPENROUTER -> "Microsoft Decision via OpenRouter";
            case AgentJudgmentConfig.LOCAL -> "EmbeddingGemma, on this machine";
            case AgentJudgmentConfig.OLLAMA -> AgentJudgmentConfig.brand() + ", via Ollama";
            default -> "Jev";
        };
    }

    static String configuredSmartBackend() {
        boolean active = Boolean.parseBoolean(AiConfig.getProperty(AgentRoutingConfig.TYPESAFE_ENABLED_KEY, "false"))
                && (Boolean.parseBoolean(AiConfig.getProperty(AgentRoutingConfig.ROUTING_ENABLED_KEY, "false"))
                        || Boolean.parseBoolean(AiConfig.getProperty(
                                "jmeter.ai.typesafe.agent.triage.enabled", "false")));
        return active ? AgentJudgmentConfig.provider() : "off";
    }

    private static boolean configuredFlag(String property, boolean defaultValue) {
        return Boolean.parseBoolean(AiConfig.getProperty(property, Boolean.toString(defaultValue)));
    }

    static String appleReasonCopy(String reason) {
        return switch (reason == null ? "" : reason) {
            case "deviceNotEligible" -> "This Mac doesn't support Apple Intelligence";
            case "appleIntelligenceNotEnabled" -> "Turn on Apple Intelligence in System Settings";
            case "modelNotReady" -> "The on-device model is still downloading. Try again in a few minutes";
            default -> "Not available right now";
        };
    }

    static String appleStatusCopy(AppleFoundationHelper.Status status) {
        return status != null && status.isAvailable()
                ? "Available on this Mac" : appleReasonCopy(status == null ? null : status.getReason());
    }

    private void probeAppleStatus() {
        appleAvailable = false;
        appleStatusReady = false;
        setStatus("Checking…", ThemeColors.info());
        updateActionButtons();
        new SwingWorker<AppleFoundationHelper.Status, Void>() {
            @Override protected AppleFoundationHelper.Status doInBackground() {
                return appleStatusSupplier.get();
            }

            @Override protected void done() {
                if (!isDisplayable() || selectedOption != ProviderSetupOption.APPLE_ON_DEVICE) {
                    return;
                }
                try {
                    AppleFoundationHelper.Status status = get();
                    appleStatusReady = true;
                    appleAvailable = status != null && status.isAvailable();
                    setStatus(appleStatusCopy(status),
                            appleAvailable ? ThemeColors.success() : ThemeColors.warning());
                } catch (Exception e) {
                    appleStatusReady = true;
                    setStatus("Not available right now", ThemeColors.warning());
                }
                updateActionButtons();
            }
        }.execute();
    }

    private JPanel buildProviderPane(ProviderSetupOption option) {
        JPanel pane = new JPanel();
        pane.setOpaque(false);
        pane.setLayout(new BoxLayout(pane, BoxLayout.Y_AXIS));
        JPanel heading = new JPanel(new BorderLayout(UiTokens.SPACE_2, 0));
        heading.setOpaque(false);
        JLabel name = new JLabel(option.displayName());
        name.setFont(UiTokens.heading(name.getFont()));
        name.setForeground(ThemeColors.foreground());
        JLabel hint = new JLabel(option.description());
        hint.setForeground(ThemeColors.secondaryText());
        hint.setBorder(BorderFactory.createEmptyBorder(UiTokens.SPACE_1, 0, 0, 0));
        JPanel titleCopy = new JPanel();
        titleCopy.setOpaque(false);
        titleCopy.setLayout(new BoxLayout(titleCopy, BoxLayout.Y_AXIS));
        addLeftAligned(titleCopy, name);
        addLeftAligned(titleCopy, hint);
        heading.add(new JLabel(BrandLogo.tile(BrandLogo.logoFor(option), 36, 24)),
                BorderLayout.WEST);
        heading.add(titleCopy, BorderLayout.CENTER);
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        heading.setMaximumSize(new Dimension(Integer.MAX_VALUE, heading.getPreferredSize().height));
        pane.add(heading);
        pane.add(Box.createVerticalStrut(UiTokens.SPACE_4));
        testButton.setVisible(true);

        JPanel fields = new JPanel();
        fields.setOpaque(false);
        fields.setLayout(new BoxLayout(fields, BoxLayout.Y_AXIS));
        if (option.requiresKey()) {
            fields.add(buildKeyFields(option));
        } else {
            fields.add(buildOllamaFields());
        }
        fields.setAlignmentX(Component.LEFT_ALIGNMENT);
        fields.setMaximumSize(new Dimension(Integer.MAX_VALUE, fields.getPreferredSize().height));
        pane.add(fields);
        pane.add(Box.createVerticalStrut(UiTokens.SPACE_3));

        JPanel bottom = new JPanel(new BorderLayout(UiTokens.SPACE_2, UiTokens.SPACE_2));
        bottom.setOpaque(false);
        statusText.setEditable(false);
        statusText.setFocusable(false);
        statusText.setOpaque(false);
        statusText.setLineWrap(true);
        statusText.setWrapStyleWord(true);
        statusText.setBorder(BorderFactory.createEmptyBorder(UiTokens.SPACE_1, 0, 0, 0));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        actions.setOpaque(false);
        actions.add(testButton);
        bottom.add(actions, BorderLayout.NORTH);
        bottom.add(statusText, BorderLayout.CENTER);
        statusPanel = bottom;
        bottom.setAlignmentX(Component.LEFT_ALIGNMENT);
        bottom.setMaximumSize(new Dimension(Integer.MAX_VALUE, bottom.getPreferredSize().height));
        pane.add(bottom);
        return pane;
    }

    boolean hasAppleRow() {
        return rows.stream().anyMatch(row -> row.option() == ProviderSetupOption.APPLE_ON_DEVICE);
    }

    private JPanel buildKeyFields(ProviderSetupOption option) {
        JPanel fields = new JPanel(new BorderLayout(UiTokens.SPACE_2, UiTokens.SPACE_2));
        fields.setOpaque(false);
        JLabel keyLabel = new JLabel("API key");
        keyLabel.setFont(UiTokens.label(keyLabel.getFont()));
        keyLabel.setForeground(ThemeColors.foreground());
        fields.add(keyLabel, BorderLayout.NORTH);

        keyField = new PlaceholderPasswordField();
        keyLabel.setLabelFor(keyField);
        keyField.setPlaceholder(keyPlaceholder(option));
        styleInput(keyField);
        Dimension keySize = keyField.getPreferredSize();
        keyField.setPreferredSize(new Dimension(420, Math.max(UiTokens.CONTROL_HEIGHT, keySize.height)));
        JToggleButton show = new JToggleButton("Show");
        show.setFocusPainted(false);
        show.setContentAreaFilled(false);
        show.setBorder(BorderFactory.createEmptyBorder(UiTokens.SPACE_2, UiTokens.SPACE_3,
                UiTokens.SPACE_2, UiTokens.SPACE_3));
        show.setForeground(ThemeColors.secondaryText());
        char echoChar = keyField.getEchoChar();
        show.addActionListener(e -> keyField.setEchoChar(show.isSelected() ? (char) 0 : echoChar));
        JPanel inputLine = new JPanel(new BorderLayout(UiTokens.SPACE_2, 0));
        inputLine.setOpaque(false);
        inputLine.add(keyField, BorderLayout.CENTER);
        inputLine.add(show, BorderLayout.EAST);
        fields.add(inputLine, BorderLayout.CENTER);
        keyField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                inputChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                inputChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                inputChanged();
            }
        });

        JPanel links = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        links.setOpaque(false);
        if (option.consoleUrl() != null) {
            JButton getKey = new JButton("Get a key");
            getKey.setBorderPainted(false);
            getKey.setContentAreaFilled(false);
            getKey.setFocusPainted(false);
            getKey.setMargin(new Insets(0, 0, 0, 0));
            getKey.setForeground(ThemeColors.accent());
            getKey.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            getKey.addActionListener(e -> openLink(option.consoleUrl()));
            links.add(getKey);
        }
        fields.add(links, BorderLayout.SOUTH);
        fields.setMaximumSize(new Dimension(Integer.MAX_VALUE, fields.getPreferredSize().height));
        return fields;
    }

    private JPanel buildOllamaFields() {
        JPanel fields = new JPanel(new BorderLayout(UiTokens.SPACE_3, UiTokens.SPACE_3));
        fields.setOpaque(false);
        JPanel hostRow = new JPanel(new BorderLayout(UiTokens.SPACE_2, 0));
        hostRow.setOpaque(false);
        JPanel hostBox = new JPanel(new BorderLayout(0, UiTokens.SPACE_1));
        hostBox.setOpaque(false);
        JLabel hostLabel = new JLabel("Host");
        hostLabel.setFont(UiTokens.label(hostLabel.getFont()));
        hostLabel.setForeground(ThemeColors.foreground());
        ollamaHostField = new JTextField(AiConfig.getProperty("ollama.host", DEFAULT_OLLAMA_HOST));
        hostLabel.setLabelFor(ollamaHostField);
        styleInput(ollamaHostField);
        hostBox.add(hostLabel, BorderLayout.NORTH);
        hostBox.add(ollamaHostField, BorderLayout.CENTER);
        JPanel portBox = new JPanel(new BorderLayout(0, UiTokens.SPACE_1));
        portBox.setOpaque(false);
        JLabel portLabel = new JLabel("Port");
        portLabel.setFont(UiTokens.label(portLabel.getFont()));
        portLabel.setForeground(ThemeColors.foreground());
        ollamaPortField = new JTextField(AiConfig.getProperty("ollama.port", DEFAULT_OLLAMA_PORT), 6);
        portLabel.setLabelFor(ollamaPortField);
        styleInput(ollamaPortField);
        portBox.add(portLabel, BorderLayout.NORTH);
        portBox.add(ollamaPortField, BorderLayout.CENTER);
        hostRow.add(hostBox, BorderLayout.CENTER);
        hostRow.add(portBox, BorderLayout.EAST);
        fields.add(hostRow, BorderLayout.NORTH);

        JLabel modelLabel = new JLabel("Default model");
        modelLabel.setFont(UiTokens.label(modelLabel.getFont()));
        modelLabel.setForeground(ThemeColors.foreground());
        ollamaModelCombo = new JComboBox<>();
        modelLabel.setLabelFor(ollamaModelCombo);
        ollamaModelCombo.setEditable(true);
        ollamaModelCombo.setBackground(ThemeColors.surface());
        ollamaModelCombo.setForeground(ThemeColors.foreground());
        Dimension modelSize = ollamaModelCombo.getPreferredSize();
        ollamaModelCombo.setPreferredSize(new Dimension(
                420, Math.max(UiTokens.CONTROL_HEIGHT, modelSize.height)));
        String configuredModel = AiConfig.getProperty("ollama.default.model", DEFAULT_OLLAMA_MODEL);
        prefilledOllamaModel = configuredModel;
        ollamaModelCombo.addItem(configuredModel);
        JPanel modelBox = new JPanel(new BorderLayout(0, UiTokens.SPACE_1));
        modelBox.setOpaque(false);
        modelBox.add(modelLabel, BorderLayout.NORTH);
        modelBox.add(ollamaModelCombo, BorderLayout.CENTER);
        fields.add(modelBox, BorderLayout.CENTER);
        DocumentListener ollamaChanged = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                inputChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                inputChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                inputChanged();
            }
        };
        ollamaHostField.getDocument().addDocumentListener(ollamaChanged);
        ollamaPortField.getDocument().addDocumentListener(ollamaChanged);
        fields.setMaximumSize(new Dimension(Integer.MAX_VALUE, fields.getPreferredSize().height));
        return fields;
    }

    private void startTest(boolean saveAfterTest) {
        if (testing) {
            return;
        }
        boolean smart = selectedOption == ProviderSetupOption.SMART_ROUTING;
        String backend = smart ? smartBackend : null;
        boolean keyedSmartBackend = smart && isKeyedSmartBackend(backend);
        String key = smart ? (keyedSmartBackend ? smartKeyForRequest(backend) : null)
                : selectedOption.requiresKey() ? readKey() : null;
        boolean savedKeyUsed = !smart && selectedOption.requiresKey()
                && key.isBlank()
                && ProviderStatus.isApiKeyConfigured(selectedOption.keyProperty());
        if (savedKeyUsed) {
            key = AiConfig.getProperty(selectedOption.keyProperty(), "");
        }
        if ((selectedOption.requiresKey() || keyedSmartBackend) && (key == null || key.isBlank())) {
            setStatus("Paste an API key before testing the connection.", ThemeColors.error());
            return;
        }
        int resolvedPort = -1;
        String host = null;
        if (selectedOption == ProviderSetupOption.OLLAMA) {
            ProviderConnectionTester.OllamaEndpoint endpoint;
            try {
                endpoint = ProviderConnectionTester.normalizeOllamaHost(ollamaHostField.getText());
            } catch (IllegalArgumentException e) {
                setStatus("Enter the Ollama host without a path, e.g. http://localhost.",
                        ThemeColors.error());
                return;
            }
            host = endpoint.host();
            String portValue = endpoint.port() == null
                    ? ollamaPortField.getText().trim() : endpoint.port();
            if (portValue.isBlank()) {
                portValue = DEFAULT_OLLAMA_PORT;
            }
            try {
                resolvedPort = Integer.parseInt(portValue);
                if (resolvedPort < 1 || resolvedPort > 65535) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                setStatus("Enter a valid Ollama port.", ThemeColors.error());
                return;
            }
        }
        if (smart && AgentJudgmentConfig.OLLAMA.equals(backend)) {
            host = AiConfig.getProperty("ollama.host", DEFAULT_OLLAMA_HOST);
            try {
                resolvedPort = Integer.parseInt(AiConfig.getProperty("ollama.port", DEFAULT_OLLAMA_PORT));
            } catch (NumberFormatException e) {
                resolvedPort = -1;
            }
        }
        ProviderSetupOption option = selectedOption;
        String hostForRequest = host;
        int portForRequest = resolvedPort;
        String keyForRequest = key;
        String backendForRequest = backend;
        boolean triageForRequest = smart && triageEnabled.isSelected();
        boolean savedKeyUsedForRequest = savedKeyUsed;
        resultCurrent = false;
        lastResult = null;
        testing = true;
        setStatus("Testing connection...", ThemeColors.info());
        updateActionButtons();
        for (ProviderRow row : rows) {
            row.setEnabled(false);
        }
        new SwingWorker<ProviderConnectionTester.Result, Void>() {
            @Override
            protected ProviderConnectionTester.Result doInBackground() {
                if (option == ProviderSetupOption.SMART_ROUTING) {
                    return AgentJudgmentConfig.OLLAMA.equals(backendForRequest)
                            ? connectionTester.testOllamaEmbeddings()
                            : connectionTester.testSmartRouting(
                                    backendForRequest, keyForRequest, triageForRequest);
                }
                return connectionTester.test(option, keyForRequest, hostForRequest, portForRequest);
            }

            @Override
            protected void done() {
                if (!isDisplayable()) {
                    return;
                }
                try {
                    if (!requestInputsMatch(
                            option, keyForRequest, hostForRequest, portForRequest,
                            backendForRequest, triageForRequest, savedKeyUsedForRequest)) {
                        resultCurrent = false;
                        setStatus("Inputs changed during the test. Test again before saving.",
                                ThemeColors.info());
                        return;
                    }
                    presentResult(option, get(), keyForRequest);
                    resultCurrent = true;
                    if (saveAfterTest && (lastResult.outcome() == ProviderConnectionTester.Outcome.OK
                            || lastResult.outcome() == ProviderConnectionTester.Outcome.RATE_LIMITED)) {
                        saveSettings();
                    }
                } catch (Exception e) {
                    resultCurrent = false;
                    setStatus("The connection test could not be completed.", ThemeColors.error());
                } finally {
                    testing = false;
                    for (ProviderRow row : rows) {
                        row.setEnabled(true);
                    }
                    updateActionButtons();
                    testCompletionCallback.run();
                }
            }
        }.execute();
    }

    private boolean requestInputsMatch(
            ProviderSetupOption option,
            String key,
            String host,
            int port,
            String backend,
            boolean triage,
            boolean savedKeyUsed) {
        if (selectedOption != option) {
            return false;
        }
        if (option == ProviderSetupOption.SMART_ROUTING) {
            if (!java.util.Objects.equals(backend, smartBackend)) {
                return false;
            }
            if (triage != triageEnabled.isSelected()) {
                return false;
            }
            if (isKeyedSmartBackend(backend)
                    && !java.util.Objects.equals(key, smartKeyForRequest(backend))) {
                return false;
            }
            return !AgentJudgmentConfig.OLLAMA.equals(backend)
                    || (java.util.Objects.equals(host, AiConfig.getProperty("ollama.host", DEFAULT_OLLAMA_HOST))
                            && port == parsePort(AiConfig.getProperty("ollama.port", DEFAULT_OLLAMA_PORT)));
        }
        if (option.requiresKey()) {
            if (savedKeyUsed) {
                return readKey().isBlank()
                        && ProviderStatus.isApiKeyConfigured(option.keyProperty())
                        && key.equals(AiConfig.getProperty(option.keyProperty(), ""));
            }
            return key.equals(readKey());
        }
        try {
            ProviderConnectionTester.OllamaEndpoint endpoint =
                    ProviderConnectionTester.normalizeOllamaHost(ollamaHostField.getText());
            String portValue = endpoint.port() == null
                    ? ollamaPortField.getText().trim() : endpoint.port();
            if (portValue.isBlank()) {
                portValue = DEFAULT_OLLAMA_PORT;
            }
            return host.equals(endpoint.host()) && port == Integer.parseInt(portValue);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static int parsePort(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    void presentResult(
            ProviderSetupOption option, ProviderConnectionTester.Result result, String testedKey) {
        if (option != selectedOption) {
            return;
        }
        lastResult = result;
        resultCurrent = true;
        copySettingsButton.setVisible(false);
        if (option == ProviderSetupOption.SMART_ROUTING) {
            presentSmartRoutingResult(result);
            updateActionButtons();
            return;
        }
        if (option == ProviderSetupOption.OLLAMA) {
            if (result.outcome() == ProviderConnectionTester.Outcome.OK) {
                updateOllamaModels(result.ollamaModelNames());
                setStatus("Ollama is running at " + SafeText.redactUrls(ollamaUrl())
                        + " with " + result.modelCount() + " models", ThemeColors.success());
            } else {
                setStatus("Ollama isn't running. Install it from ollama.com, pull a model, then test again.",
                        ThemeColors.error());
            }
        } else {
            switch (result.outcome()) {
                case OK -> setStatus(option == ProviderSetupOption.OPENROUTER
                        ? "Connected. Key accepted."
                        : "Connected. " + result.modelCount() + " models available.",
                        ThemeColors.success());
                case AUTH_REJECTED -> setStatus(
                        "Authentication was rejected. Check the key and try again.", ThemeColors.error());
                case RATE_LIMITED -> setStatus(
                        "Rate limited. The key can still be saved and connected.", ThemeColors.info());
                case HTTP_ERROR -> setStatus(
                        "The provider returned HTTP " + result.httpCode()
                                + ". You can save anyway if a proxy is blocking the test.",
                        ThemeColors.error());
                case NETWORK_ERROR -> setStatus(
                        "Could not reach the provider: " + safeReason(result.reason(), testedKey)
                                + " You can save anyway if a proxy is blocking the test.",
                        ThemeColors.error());
            }
        }
        updateActionButtons();
    }

    private void saveOrTest() {
        if (selectedOption == ProviderSetupOption.APPLE_ON_DEVICE) {
            if (appleAvailable) {
                saveSettings();
            }
            return;
        }
        if (selectedOption == ProviderSetupOption.SMART_ROUTING
                && smartRoutingFeaturesDisabled(routingEnabled.isSelected(), triageEnabled.isSelected())) {
            saveSettings();
            return;
        }
        if (selectedOption == ProviderSetupOption.SMART_ROUTING
                && ("off".equals(smartBackend)
                        || AgentJudgmentConfig.LOCAL.equals(smartBackend))) {
            saveSettings();
            return;
        }
        if (!resultCurrent) {
            startTest(true);
            return;
        }
        if (lastResult == null) {
            return;
        }
        if (lastResult.outcome() == ProviderConnectionTester.Outcome.AUTH_REJECTED) {
            return;
        }
        if (lastResult.outcome() == ProviderConnectionTester.Outcome.OK
                || lastResult.outcome() == ProviderConnectionTester.Outcome.RATE_LIMITED
                || lastResult.outcome() == ProviderConnectionTester.Outcome.HTTP_ERROR
                || lastResult.outcome() == ProviderConnectionTester.Outcome.NETWORK_ERROR) {
            saveSettings();
        }
    }

    private void saveSettings() {
        LinkedHashMap<String, String> settings = new LinkedHashMap<>();
        if (selectedOption == ProviderSetupOption.SMART_ROUTING) {
            PlaceholderPasswordField entered = smartKeyFields.get(smartBackend);
            String key = entered == null ? null : entered.getText();
            settings = smartRoutingSettings(smartBackend, routingEnabled.isSelected(),
                    triageEnabled.isSelected(), key);
        } else if (selectedOption == ProviderSetupOption.APPLE_ON_DEVICE) {
            if (!appleAvailable) {
                return;
            }
            settings.putAll(appleSettings());
        } else if (selectedOption.requiresKey()) {
            String key = readKey();
            if (key.isBlank()
                    && !ProviderStatus.isApiKeyConfigured(selectedOption.keyProperty())) {
                setStatus("Paste an API key before saving.", ThemeColors.error());
                return;
            }
            if (!key.isBlank()) {
                settings.put(selectedOption.keyProperty(), key);
            }
            if (selectedOption.serviceType() != null) {
                settings.put("jmeter.ai.service.type", selectedOption.serviceType());
            }
        } else {
            settings.put("jmeter.ai.service.type", "ollama");
            ProviderConnectionTester.OllamaEndpoint endpoint =
                    ProviderConnectionTester.normalizeOllamaHost(ollamaHostField.getText());
            String port = endpoint.port() == null
                    ? ollamaPortField.getText().trim() : endpoint.port();
            if (port.isBlank()) {
                port = DEFAULT_OLLAMA_PORT;
            }
            settings.put("ollama.host", endpoint.host());
            settings.put("ollama.port", port);
            Object model = ollamaModelCombo.getEditor().getItem();
            String selectedModel = model == null ? "" : model.toString().trim();
            if (selectedModel.isBlank()) {
                selectedModel = AiConfig.getProperty("ollama.default.model", DEFAULT_OLLAMA_MODEL);
            }
            settings.put("ollama.default.model", selectedModel);
        }
        lastSettings = settings;
        ProviderSetupOption callbackOption = callbackOptionAfterSave(selectedOption, settings);
        try {
            UserPropertiesWriter.apply(propertiesPath, settings);
            if (selectedOption == ProviderSetupOption.SMART_ROUTING) {
                updateSmartRoutingRow();
            }
            onSaved.accept(callbackOption);
            dispose();
        } catch (IOException e) {
            if (selectedOption == ProviderSetupOption.SMART_ROUTING) {
                updateSmartRoutingRow();
            }
            onSaved.accept(callbackOption);
            String reason = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName() : e.getMessage();
            setStatus("Couldn't save to " + propertiesPath + ": " + reason
                    + ". Connected for this session only. Copy these lines into user.properties "
                    + "to keep the setting.", ThemeColors.error());
            copySettingsButton.setVisible(true);
        }
    }

    static LinkedHashMap<String, String> smartRoutingSettings(
            String backend, boolean routing, boolean triage, String enteredKey) {
        LinkedHashMap<String, String> settings = new LinkedHashMap<>();
        if ("off".equals(backend)) {
            settings.put(AgentRoutingConfig.ROUTING_ENABLED_KEY, "false");
            settings.put("jmeter.ai.typesafe.agent.triage.enabled", "false");
            return settings;
        }
        boolean local = AgentJudgmentConfig.LOCAL.equals(backend)
                || AgentJudgmentConfig.OLLAMA.equals(backend);
        settings.put(AgentRoutingConfig.TYPESAFE_ENABLED_KEY, "true");
        settings.put(AgentRoutingConfig.ROUTING_ENABLED_KEY, Boolean.toString(routing));
        settings.put("jmeter.ai.typesafe.agent.triage.enabled",
                Boolean.toString(!local && triage));
        settings.put(AgentJudgmentConfig.PROVIDER_KEY, backend);
        String keyProperty = switch (backend) {
            case AgentJudgmentConfig.TYPESAFE -> AgentRoutingConfig.API_KEY;
            case AgentJudgmentConfig.OPENAI -> AgentJudgmentConfig.OPENAI_API_KEY;
            case AgentJudgmentConfig.OPENROUTER -> AgentJudgmentConfig.OPENROUTER_API_KEY;
            default -> null;
        };
        if ((routing || triage) && keyProperty != null && enteredKey != null && !enteredKey.isBlank()) {
            settings.put(keyProperty, enteredKey);
        }
        return settings;
    }

    static boolean smartRoutingFeaturesDisabled(boolean routing, boolean triage) {
        return !routing && !triage;
    }

    static ProviderSetupOption callbackOptionAfterSave(
            ProviderSetupOption option, Map<String, String> settings) {
        if (option != ProviderSetupOption.SMART_ROUTING) {
            return option;
        }
        return settings.containsKey(AgentJudgmentConfig.OPENAI_API_KEY)
                ? ProviderSetupOption.SMART_ROUTING : null;
    }

    static LinkedHashMap<String, String> appleSettings() {
        LinkedHashMap<String, String> settings = new LinkedHashMap<>();
        settings.put("apple.enabled", "true");
        return settings;
    }

    private static boolean isKeyedSmartBackend(String backend) {
        return AgentJudgmentConfig.TYPESAFE.equals(backend)
                || AgentJudgmentConfig.OPENAI.equals(backend)
                || AgentJudgmentConfig.OPENROUTER.equals(backend);
    }

    private String smartKeyForRequest(String backend) {
        PlaceholderPasswordField field = smartKeyFields.get(backend);
        String entered = field == null ? "" : field.getText();
        if (!entered.isBlank()) {
            return entered;
        }
        String property = switch (backend) {
            case AgentJudgmentConfig.OPENAI -> AgentJudgmentConfig.OPENAI_API_KEY;
            case AgentJudgmentConfig.OPENROUTER -> AgentJudgmentConfig.OPENROUTER_API_KEY;
            default -> AgentRoutingConfig.API_KEY;
        };
        return AiConfig.getProperty(property, "");
    }

    private void presentSmartRoutingResult(ProviderConnectionTester.Result result) {
        if (result.outcome() == ProviderConnectionTester.Outcome.OK) {
            setStatus("Connection test passed.", ThemeColors.success());
            return;
        }
        if (result.outcome() == ProviderConnectionTester.Outcome.AUTH_REJECTED) {
            setStatus("Authentication was rejected. Check the key and try again.", ThemeColors.error());
        } else if (result.outcome() == ProviderConnectionTester.Outcome.RATE_LIMITED) {
            setStatus("Rate limited. The key can still be saved.", ThemeColors.info());
        } else if (AgentJudgmentConfig.OLLAMA.equals(smartBackend)
                && isOllamaModelUnavailable(result.httpCode(), result.reason(), AiConfig.getProperty(
                        OllamaEmbeddingClient.MODEL_KEY, OllamaEmbeddingClient.DEFAULT_MODEL))) {
            setStatus(ollamaMissingModelCopy(AiConfig.getProperty(
                    OllamaEmbeddingClient.MODEL_KEY, OllamaEmbeddingClient.DEFAULT_MODEL)),
                    ThemeColors.error());
        } else if (result.outcome() == ProviderConnectionTester.Outcome.HTTP_ERROR) {
            setStatus(smartRoutingHttpErrorCopy(result), ThemeColors.error());
        } else {
            setStatus("Could not reach the provider: " + safeReason(result.reason())
                    + " You can save anyway.", ThemeColors.error());
        }
    }

    static String ollamaMissingModelCopy(String model) {
        return "Run ollama pull " + OllamaEmbeddingClient.model(model) + ".";
    }

    static boolean isOllamaModelUnavailable(int httpCode, String reason, String model) {
        return httpCode == 404 || OllamaEmbeddingClient.modelUnavailableMessage(model).equals(reason);
    }

    static String unexpectedProviderResponseCopy(String reason) {
        return "Unexpected response from the provider: " + safeReason(reason) + " You can save anyway.";
    }

    static String smartRoutingHttpErrorCopy(ProviderConnectionTester.Result result) {
        return result.httpCode() == 0
                ? unexpectedProviderResponseCopy(result.reason())
                : "The provider returned HTTP " + result.httpCode() + ". You can save anyway.";
    }

    private void updateOllamaModels(List<String> names) {
        Object editorItem = ollamaModelCombo.getEditor().getItem();
        String editorText = editorItem == null ? "" : editorItem.toString();
        String selectedModel = modelAfterRefresh(editorText, prefilledOllamaModel, names);
        ollamaModelCombo.removeAllItems();
        for (String name : names) {
            ollamaModelCombo.addItem(name);
        }
        ollamaModelCombo.setSelectedItem(selectedModel);
    }

    static String modelAfterRefresh(String editorText, String prefilledModel, List<String> names) {
        String current = editorText == null ? "" : editorText.trim();
        String prefilled = prefilledModel == null ? "" : prefilledModel;
        if (!current.isBlank() && !current.equals(prefilled)) {
            return current;
        }
        if (names.contains(prefilled)) {
            return prefilled;
        }
        if (!prefilled.isBlank() && !prefilled.contains(":")
                && names.contains(prefilled + ":latest")) {
            return prefilled;
        }
        return names.isEmpty() ? prefilled : names.get(0);
    }

    private void inputChanged() {
        if (testing) {
            return;
        }
        resultCurrent = false;
        lastResult = null;
        copySettingsButton.setVisible(false);
        setStatus(" ", ThemeColors.secondaryText());
        updateActionButtons();
    }

    private void updateActionButtons() {
        boolean smart = selectedOption == ProviderSetupOption.SMART_ROUTING;
        boolean apple = selectedOption == ProviderSetupOption.APPLE_ON_DEVICE;
        boolean smartFeaturesDisabled = smart
                && smartRoutingFeaturesDisabled(routingEnabled.isSelected(), triageEnabled.isSelected());
        boolean smartTestable = smart && !"off".equals(smartBackend)
                && !AgentJudgmentConfig.LOCAL.equals(smartBackend);
        testButton.setVisible(!apple && (!smart || smartTestable));
        testButton.setEnabled(!testing);
        closeButton.setEnabled(true);
        saveButton.setEnabled(!testing);
        if (apple) {
            saveButton.setText("Use Apple on-device");
            saveButton.setEnabled(!testing && appleStatusReady && appleAvailable);
        } else if (smart && (smartFeaturesDisabled
                || "off".equals(smartBackend)
                || AgentJudgmentConfig.LOCAL.equals(smartBackend))) {
            saveButton.setText("Save");
        } else {
            saveButton.setText("Save and connect");
        }
        if (resultCurrent && lastResult != null) {
            switch (lastResult.outcome()) {
                case AUTH_REJECTED -> saveButton.setEnabled(false);
                case HTTP_ERROR, NETWORK_ERROR -> saveButton.setText("Save anyway");
                default -> {
                }
            }
        }
    }

    private void setStatus(String text, Color color) {
        String message = text == null ? " " : text;
        statusText.setText(message);
        statusText.setForeground(color);
        statusText.setRows(estimatedStatusRows(message));
        statusText.setCaretPosition(0);
        if (statusPanel != null) {
            statusPanel.setMaximumSize(new Dimension(
                    Integer.MAX_VALUE, statusPanel.getPreferredSize().height));
            statusPanel.revalidate();
            statusPanel.repaint();
            if (statusPanel.getParent() != null) {
                statusPanel.getParent().revalidate();
            }
        }
    }

    private int estimatedStatusRows(String message) {
        FontMetrics metrics = statusText.getFontMetrics(statusText.getFont());
        int width = statusText.getWidth();
        if (width <= 0) {
            width = Math.max(320, getWidth() - 400);
        }
        int charsPerRow = Math.max(1, width / Math.max(1, metrics.charWidth('m')));
        int rows = 0;
        for (String line : message.split("\\R", -1)) {
            rows += Math.max(1, (line.length() + charsPerRow - 1) / charsPerRow);
        }
        return Math.max(1, rows);
    }

    private String readKey() {
        char[] characters = keyField.getPassword();
        try {
            return new String(characters);
        } finally {
            java.util.Arrays.fill(characters, '\0');
        }
    }

    private String ollamaUrl() {
        String port = ollamaPortField.getText().trim();
        if (port.isBlank()) {
            port = DEFAULT_OLLAMA_PORT;
        }
        return ProviderConnectionTester.ollamaBaseUrl(ollamaHostField.getText().trim(), port);
    }

    static boolean isConfigured(ProviderSetupOption option) {
        if (option.requiresKey()) {
            return ProviderStatus.isApiKeyConfigured(option.keyProperty());
        }
        if (option == ProviderSetupOption.SMART_ROUTING) {
            String backend = AgentJudgmentConfig.provider();
            boolean active = Boolean.parseBoolean(AiConfig.getProperty(
                    AgentRoutingConfig.TYPESAFE_ENABLED_KEY, "false"))
                    && (Boolean.parseBoolean(AiConfig.getProperty(AgentRoutingConfig.ROUTING_ENABLED_KEY, "false"))
                            || Boolean.parseBoolean(AiConfig.getProperty(
                                    "jmeter.ai.typesafe.agent.triage.enabled", "false")));
            if (!active) {
                return false;
            }
            if (AgentJudgmentConfig.LOCAL.equals(backend) || AgentJudgmentConfig.OLLAMA.equals(backend)) {
                return true;
            }
            String keyProperty = AgentJudgmentConfig.OPENAI.equals(backend)
                    ? AgentJudgmentConfig.OPENAI_API_KEY
                    : AgentJudgmentConfig.OPENROUTER.equals(backend)
                            ? AgentJudgmentConfig.OPENROUTER_API_KEY : AgentRoutingConfig.API_KEY;
            return ProviderStatus.isApiKeyConfigured(keyProperty);
        }
        if (option == ProviderSetupOption.APPLE_ON_DEVICE) {
            return false;
        }
        return "ollama".equalsIgnoreCase(AiConfig.getProperty("jmeter.ai.service.type", ""));
    }

    static String keyPlaceholder(ProviderSetupOption option) {
        return isConfigured(option) ? SAVED_KEY_PLACEHOLDER : "Paste your API key";
    }

    private void copySettings() {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                    new StringSelection(UserPropertiesWriter.propertyLines(lastSettings)), null);
            setStatus("Settings copied. The API key remains masked in this dialog.", ThemeColors.success());
        } catch (RuntimeException e) {
            setStatus("Could not copy settings to the clipboard.", ThemeColors.error());
        }
    }

    private void openLink(String url) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(url));
            } else {
                setStatus("A browser is not available on this system.", ThemeColors.error());
            }
        } catch (Exception e) {
            setStatus("Could not open the link in a browser.", ThemeColors.error());
        }
    }

    private static String safeReason(String reason) {
        return safeReason(reason, null);
    }

    private static String safeReason(String reason, String secret) {
        String redacted = reason == null ? "" : reason;
        if (secret != null && !secret.isBlank()) {
            redacted = redacted.replace(secret, "***");
        }
        String safe = SafeText.redactUrls(redacted);
        return safe.length() > 300 ? safe.substring(0, 297) + "..." : safe;
    }

    private static void styleInput(JTextField field) {
        field.setForeground(ThemeColors.foreground());
        field.setBackground(ThemeColors.surface());
        field.setCaretColor(ThemeColors.accent());
        Border border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ThemeColors.separator()),
                BorderFactory.createEmptyBorder(UiTokens.SPACE_1, UiTokens.SPACE_2,
                        UiTokens.SPACE_1, UiTokens.SPACE_2));
        field.setBorder(border);
    }

    static final class ProviderRow extends JPanel {
        private final ProviderSetupOption option;
        private final JPanel copy = new JPanel();
        private final JLabel logo;
        private final JLabel hint;
        private final JLabel configuredBadge;
        private Component configuredGap;
        private boolean selected;
        private boolean rollover;
        private Runnable activation = () -> { };
        private Runnable previousProvider = () -> { };
        private Runnable nextProvider = () -> { };

        ProviderRow(ProviderSetupOption option) {
            this.option = option;
            boolean configured = isConfigured(option);
            setOpaque(false);
            setFocusable(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setLayout(new BorderLayout(UiTokens.SPACE_2, 0));
            setBorder(BorderFactory.createEmptyBorder(
                    UiTokens.SPACE_2, UiTokens.SPACE_2, UiTokens.SPACE_2, UiTokens.SPACE_2));
            logo = new JLabel(BrandLogo.tile(BrandLogo.logoFor(option), 28, 18));
            JPanel logoSlot = new JPanel(new BorderLayout());
            logoSlot.setOpaque(false);
            logoSlot.add(logo, BorderLayout.NORTH);
            add(logoSlot, BorderLayout.WEST);
            copy.setOpaque(false);
            copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
            JLabel name = new JLabel(option.displayName());
            name.setForeground(ThemeColors.foreground());
            name.setFont(UiTokens.label(name.getFont()));
            hint = new JLabel(option.listHint());
            hint.setFont(UiTokens.caption(hint.getFont()));
            copy.add(name);
            copy.add(Box.createVerticalStrut(UiTokens.SPACE_1));
            copy.add(hint);
            configuredBadge = new JLabel("Configured");
            configuredBadge.setForeground(ThemeColors.success());
            configuredBadge.setFont(UiTokens.caption(configuredBadge.getFont()));
            configuredBadge.setVisible(configured);
            if (configured) {
                configuredGap = Box.createVerticalStrut(UiTokens.SPACE_1);
                copy.add(configuredGap);
            }
            copy.add(configuredBadge);
            add(copy, BorderLayout.CENTER);
            updateHintColor();
            getAccessibleContext().setAccessibleName(option.displayName() + ", " + option.listHint());
            getAccessibleContext().setAccessibleDescription(option.description()
                    + (configured ? " Configured." : ""));
            installMouseHandlers(this);
            bindKey(java.awt.event.KeyEvent.VK_SPACE, "activate-provider", this::activate);
            bindKey(java.awt.event.KeyEvent.VK_ENTER, "activate-provider", this::activate);
            bindKey(java.awt.event.KeyEvent.VK_UP, "previous-provider", () -> previousProvider.run());
            bindKey(java.awt.event.KeyEvent.VK_DOWN, "next-provider", () -> nextProvider.run());
            addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusGained(java.awt.event.FocusEvent event) {
                    repaint();
                }

                @Override
                public void focusLost(java.awt.event.FocusEvent event) {
                    repaint();
                }
            });
        }

        void setActionHandlers(Runnable activation, Runnable previousProvider, Runnable nextProvider) {
            this.activation = activation == null ? () -> { } : activation;
            this.previousProvider = previousProvider == null ? () -> { } : previousProvider;
            this.nextProvider = nextProvider == null ? () -> { } : nextProvider;
        }

        private void bindKey(int keyCode, String actionName, Runnable action) {
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(keyCode, 0), actionName);
            getActionMap().put(actionName, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent event) {
                    if (isEnabled()) {
                        action.run();
                    }
                }
            });
        }

        private void installMouseHandlers(Component component) {
            MouseAdapter mouseHandler = new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent event) {
                    setRollover(true);
                }

                @Override
                public void mouseExited(MouseEvent event) {
                    Point point = SwingUtilities.convertPoint(
                            event.getComponent(), event.getPoint(), ProviderRow.this);
                    setRollover(contains(point));
                }

                @Override
                public void mouseClicked(MouseEvent event) {
                    if (isEnabled() && event.getButton() == MouseEvent.BUTTON1) {
                        requestFocusInWindow();
                        activate();
                    }
                }
            };
            component.addMouseListener(mouseHandler);
            if (component instanceof Container container) {
                for (Component child : container.getComponents()) {
                    installMouseHandlers(child);
                }
            }
        }

        private void activate() {
            if (isEnabled()) {
                activation.run();
            }
        }

        void setRollover(boolean rollover) {
            if (this.rollover != rollover) {
                this.rollover = rollover;
                updateHintColor();
                repaint();
            }
        }

        void setSelected(boolean selected) {
            if (this.selected != selected) {
                this.selected = selected;
                updateHintColor();
                repaint();
            }
        }

        void setHintText(String value) {
            hint.setText(value);
            if (option == ProviderSetupOption.SMART_ROUTING) {
                logo.setIcon(BrandLogo.tile(BrandLogo.logoFor(option), 28, 18));
            }
            getAccessibleContext().setAccessibleName(option.displayName() + ", " + value);
            revalidate();
            repaint();
        }

        void setConfigured(boolean configured) {
            if (configuredBadge.isVisible() == configured) {
                return;
            }
            if (configured) {
                configuredGap = Box.createVerticalStrut(UiTokens.SPACE_1);
                copy.add(configuredGap, Math.max(0, copy.getComponentCount() - 1));
            } else if (configuredGap != null) {
                copy.remove(configuredGap);
                configuredGap = null;
            }
            configuredBadge.setVisible(configured);
            getAccessibleContext().setAccessibleDescription(option.description()
                    + (configured ? " Configured." : ""));
            revalidate();
            repaint();
        }

        ProviderSetupOption option() {
            return option;
        }

        boolean hasConfiguredBadge() {
            return configuredBadge.isVisible();
        }

        Color rowBackground() {
            return selected ? ThemeColors.accentSoft()
                    : rollover ? ThemeColors.hoverBackground() : ThemeColors.surface();
        }

        Color hintColor() {
            return hint.getForeground();
        }

        private void updateHintColor() {
            if (hint != null) {
                hint.setForeground(ThemeColors.secondaryText(rowBackground()));
            }
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension preferred = super.getPreferredSize();
            if (copy == null) {
                return preferred;
            }
            Dimension contentSize = copy.getPreferredSize();
            Insets insets = getInsets();
            return new Dimension(
                    Math.max(226, Math.max(preferred.width, contentSize.width + insets.left + insets.right)),
                    Math.max(preferred.height, contentSize.height + insets.top + insets.bottom));
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(rowBackground());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), UiTokens.RADIUS_MEDIUM, UiTokens.RADIUS_MEDIUM);
                if (selected) {
                    g2.setColor(ThemeColors.accent());
                    g2.fillRoundRect(0, 0, UiTokens.SPACE_1, getHeight(),
                            UiTokens.SPACE_1, UiTokens.SPACE_1);
                }
                if (isFocusOwner()) {
                    g2.setColor(ThemeColors.focusRing());
                    g2.drawRoundRect(1, 1, Math.max(0, getWidth() - 3), Math.max(0, getHeight() - 3),
                            UiTokens.RADIUS_MEDIUM, UiTokens.RADIUS_MEDIUM);
                }
            } finally {
                g2.dispose();
            }
            super.paintComponent(graphics);
        }

        @Override
        public AccessibleContext getAccessibleContext() {
            if (accessibleContext == null) {
                accessibleContext = new AccessibleProviderRow();
            }
            return accessibleContext;
        }

        protected class AccessibleProviderRow extends AccessibleJPanel {
            @Override
            public AccessibleRole getAccessibleRole() {
                return AccessibleRole.PUSH_BUTTON;
            }
        }
    }

    private static final class PlaceholderPasswordField extends JPasswordField {
        private String placeholder = "";

        private void setPlaceholder(String placeholder) {
            this.placeholder = placeholder;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (placeholder.isEmpty() || getPassword().length > 0 || hasFocus()) {
                return;
            }
            Graphics2D g2 = (Graphics2D) graphics.create();
            try {
                g2.setColor(ThemeColors.secondaryText());
                g2.setFont(getFont());
                Insets insets = getInsets();
                int baseline = (getHeight() - g2.getFontMetrics().getHeight()) / 2
                        + g2.getFontMetrics().getAscent();
                g2.drawString(placeholder, insets.left + 2, baseline);
            } finally {
                g2.dispose();
            }
        }
    }
}
