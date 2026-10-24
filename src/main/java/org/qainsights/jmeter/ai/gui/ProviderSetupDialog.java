package org.qainsights.jmeter.ai.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.border.Border;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.gui.theme.UiTokens;
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

    private final Consumer<ProviderSetupOption> onSaved;
    private final ProviderConnectionTester connectionTester;
    private final Path propertiesPath;
    private final Runnable testCompletionCallback;
    private final JPanel contentCard = new JPanel(new BorderLayout());
    private final JPanel providerRows = new JPanel();
    private final JTextArea statusText = new JTextArea(" ");
    private JPanel statusPanel;
    private final QuietButton testButton = new QuietButton("Test connection", QuietButton.Kind.OUTLINED);
    private final QuietButton saveButton = new QuietButton("Save and connect", QuietButton.Kind.PRIMARY);
    private final QuietButton closeButton = new QuietButton("Close", QuietButton.Kind.GHOST);
    private final QuietButton copySettingsButton = new QuietButton("Copy settings", QuietButton.Kind.OUTLINED);
    private final List<ProviderRow> rows;

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
        super(owner, "Connect a provider", ModalityType.APPLICATION_MODAL);
        this.onSaved = onSaved == null ? option -> { } : onSaved;
        this.connectionTester = connectionTester;
        this.propertiesPath = propertiesPath;
        this.testCompletionCallback = testCompletionCallback == null ? () -> { } : testCompletionCallback;
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
        ButtonGroup group = new ButtonGroup();
        for (ProviderSetupOption option : ProviderSetupOption.values()) {
            ProviderRow row = new ProviderRow(option, isConfigured(option));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.addActionListener(e -> selectOption(option));
            group.add(row);
            providerRows.add(row);
            providerRows.add(Box.createVerticalStrut(UiTokens.SPACE_2));
        }
        rows = providerRows.getComponents().length == 0
                ? List.of() : providerRows.getComponents().length > 0
                        ? java.util.Arrays.stream(providerRows.getComponents())
                                .filter(ProviderRow.class::isInstance)
                                .map(ProviderRow.class::cast)
                                .toList()
                        : List.of();

        copySettingsButton.setVisible(false);
        copySettingsButton.addActionListener(e -> copySettings());
        testButton.addActionListener(e -> startTest(false));
        saveButton.addActionListener(e -> saveOrTest());
        closeButton.addActionListener(e -> dispose());

        pack();
        setMinimumSize(new Dimension(790, 500));
        setSize(Math.max(850, getPreferredSize().width), Math.max(550, getPreferredSize().height));
        setLocationRelativeTo(owner);
        selectOption(ollamaDetected ? ProviderSetupOption.OLLAMA : ProviderSetupOption.ANTHROPIC);
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
        navigation.setOpaque(false);
        JLabel providerHeading = new JLabel("PROVIDERS");
        providerHeading.setFont(UiTokens.label(providerHeading.getFont()));
        providerHeading.setForeground(ThemeColors.secondaryText());
        providerHeading.setBorder(BorderFactory.createEmptyBorder(0, UiTokens.SPACE_2, UiTokens.SPACE_2, 0));
        navigation.add(providerHeading, BorderLayout.NORTH);
        providerRows.setOpaque(false);
        navigation.add(providerRows, BorderLayout.CENTER);
        navigation.setPreferredSize(new Dimension(236, 0));

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

    void selectOption(ProviderSetupOption option) {
        selectedOption = option;
        resultCurrent = false;
        lastResult = null;
        for (ProviderRow row : rows) {
            row.setSelected(option == row.option);
        }
        contentCard.removeAll();
        contentCard.add(buildProviderPane(option), BorderLayout.CENTER);
        setStatus(" ", ThemeColors.secondaryText());
        copySettingsButton.setVisible(false);
        updateActionButtons();
        contentCard.revalidate();
        contentCard.repaint();
    }

    private JPanel buildProviderPane(ProviderSetupOption option) {
        JPanel pane = new JPanel();
        pane.setOpaque(false);
        pane.setLayout(new BoxLayout(pane, BoxLayout.Y_AXIS));
        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        JLabel name = new JLabel(option.displayName());
        name.setFont(UiTokens.heading(name.getFont()));
        name.setForeground(ThemeColors.foreground());
        JLabel hint = new JLabel(option.description());
        hint.setForeground(ThemeColors.secondaryText());
        hint.setBorder(BorderFactory.createEmptyBorder(UiTokens.SPACE_1, 0, 0, 0));
        heading.add(name);
        heading.add(hint);
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        heading.setMaximumSize(new Dimension(Integer.MAX_VALUE, heading.getPreferredSize().height));
        pane.add(heading);
        pane.add(Box.createVerticalStrut(UiTokens.SPACE_4));

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
        statusText.setFont(UiTokens.body(statusText.getFont()));
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

    private JPanel buildKeyFields(ProviderSetupOption option) {
        JPanel fields = new JPanel(new BorderLayout(UiTokens.SPACE_2, UiTokens.SPACE_2));
        fields.setOpaque(false);
        JLabel keyLabel = new JLabel("API key");
        keyLabel.setFont(UiTokens.label(keyLabel.getFont()));
        keyLabel.setForeground(ThemeColors.foreground());
        fields.add(keyLabel, BorderLayout.NORTH);

        keyField = new PlaceholderPasswordField();
        keyLabel.setLabelFor(keyField);
        boolean configured = isConfigured(option);
        keyField.setPlaceholder(configured
                ? "A key is already saved. Paste a new one to replace it." : "Paste your API key");
        styleInput(keyField);
        keyField.setPreferredSize(new Dimension(420, 36));
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
        ollamaModelCombo.setPreferredSize(new Dimension(420, 36));
        ollamaModelCombo.setBackground(ThemeColors.surface());
        ollamaModelCombo.setForeground(ThemeColors.foreground());
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
        String key = selectedOption.requiresKey() ? readKey() : null;
        if (selectedOption.requiresKey() && key.isBlank()) {
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
        ProviderSetupOption option = selectedOption;
        String hostForRequest = host;
        int portForRequest = resolvedPort;
        String keyForRequest = key;
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
                return connectionTester.test(option, keyForRequest, hostForRequest, portForRequest);
            }

            @Override
            protected void done() {
                if (!isDisplayable()) {
                    return;
                }
                try {
                    if (!requestInputsMatch(option, keyForRequest, hostForRequest, portForRequest)) {
                        setStatus("Inputs changed during the test. Test again before saving.",
                                ThemeColors.info());
                        return;
                    }
                    presentResult(option, get());
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
            ProviderSetupOption option, String key, String host, int port) {
        if (selectedOption != option) {
            return false;
        }
        if (option.requiresKey()) {
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

    void presentResult(ProviderSetupOption option, ProviderConnectionTester.Result result) {
        if (option != selectedOption) {
            return;
        }
        lastResult = result;
        resultCurrent = true;
        copySettingsButton.setVisible(false);
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
                case OK -> setStatus("Connected. " + result.modelCount() + " models available.",
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
                        "Could not reach the provider: " + safeReason(result.reason())
                                + " You can save anyway if a proxy is blocking the test.",
                        ThemeColors.error());
            }
        }
        updateActionButtons();
    }

    private void saveOrTest() {
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
        if (selectedOption.requiresKey()) {
            String key = readKey();
            if (key.isBlank()) {
                setStatus("Paste an API key before saving.", ThemeColors.error());
                return;
            }
            settings.put(selectedOption.keyProperty(), key);
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
        try {
            UserPropertiesWriter.apply(propertiesPath, settings);
            onSaved.accept(selectedOption);
            dispose();
        } catch (IOException e) {
            onSaved.accept(selectedOption);
            String reason = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName() : e.getMessage();
            setStatus("Couldn't save to " + propertiesPath + ": " + reason
                    + ". Connected for this session only. Copy these lines into user.properties "
                    + "to keep the setting.", ThemeColors.error());
            copySettingsButton.setVisible(true);
        }
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
        testButton.setEnabled(!testing);
        closeButton.setEnabled(true);
        saveButton.setEnabled(!testing);
        saveButton.setText("Save and connect");
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

    private boolean isConfigured(ProviderSetupOption option) {
        if (option.requiresKey()) {
            return ProviderStatus.isApiKeyConfigured(option.keyProperty());
        }
        return "ollama".equalsIgnoreCase(AiConfig.getProperty("jmeter.ai.service.type", ""));
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
        String safe = SafeText.redactUrls(reason == null ? "" : reason);
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

    private static final class ProviderRow extends JButton {
        private final ProviderSetupOption option;

        private ProviderRow(ProviderSetupOption option, boolean configured) {
            super();
            this.option = option;
            setLayout(new BorderLayout(UiTokens.SPACE_1, 0));
            setBorder(BorderFactory.createEmptyBorder(
                    UiTokens.SPACE_2, UiTokens.SPACE_2, UiTokens.SPACE_2, UiTokens.SPACE_2));
            setHorizontalAlignment(SwingConstants.LEFT);
            setFocusPainted(false);
            setContentAreaFilled(false);
            JPanel copy = new JPanel();
            copy.setOpaque(false);
            copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
            JLabel name = new JLabel(option.displayName());
            name.setForeground(ThemeColors.foreground());
            name.setFont(UiTokens.label(name.getFont()));
            JLabel hint = new JLabel(option.listHint());
            hint.setForeground(ThemeColors.secondaryText());
            hint.setFont(UiTokens.caption(hint.getFont()));
            copy.add(name);
            copy.add(Box.createVerticalStrut(2));
            copy.add(hint);
            add(copy, BorderLayout.CENTER);
            if (configured) {
                JLabel badge = new JLabel("Configured");
                badge.setForeground(ThemeColors.success());
                badge.setFont(UiTokens.caption(badge.getFont()));
                add(badge, BorderLayout.SOUTH);
            }
            getAccessibleContext().setAccessibleName(option.displayName());
            getAccessibleContext().setAccessibleDescription(option.description()
                    + (configured ? " Configured." : ""));
            setPreferredSize(new Dimension(226, configured ? 64 : 54));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, getPreferredSize().height));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            try {
                g2.setColor(getModel().isSelected()
                        ? ThemeColors.accentSoft()
                        : getModel().isRollover() ? ThemeColors.hoverBackground() : ThemeColors.surface());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), UiTokens.RADIUS_MEDIUM, UiTokens.RADIUS_MEDIUM);
                if (getModel().isSelected()) {
                    g2.setColor(ThemeColors.accent());
                    g2.fillRoundRect(0, 0, 3, getHeight(), 3, 3);
                }
            } finally {
                g2.dispose();
            }
            super.paintComponent(graphics);
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
