package org.qainsights.jmeter.ai.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.gui.theme.UiTokens;
import org.qainsights.jmeter.ai.load.CommandShell;
import org.qainsights.jmeter.ai.load.LoadCommandHandler;
import org.qainsights.jmeter.ai.load.LoadShapeChart;
import org.qainsights.jmeter.ai.load.WorkloadApplier;
import org.qainsights.jmeter.ai.load.WorkloadAiAssistant;
import org.qainsights.jmeter.ai.load.WorkloadCalculator;
import org.qainsights.jmeter.ai.load.WorkloadFormState;
import org.qainsights.jmeter.ai.load.WorkloadInputs;
import org.qainsights.jmeter.ai.load.WorkloadPlan;
import org.qainsights.jmeter.ai.load.WorkloadSpec;
import org.qainsights.jmeter.ai.telemetry.Telemetry;
import org.qainsights.jmeter.ai.telemetry.TelemetryFeature;
import org.qainsights.jmeter.ai.utils.ProviderErrors;

/** Modal, deterministic workload editor with a live load-shape preview. */
public final class WorkloadDesignerDialog extends JDialog {
    private final LoadCommandHandler.Preparation preparation;
    private final Consumer<Completion> onFinish;
    private final FormPanel form = new FormPanel();
    private final List<FormRow> formRows = new ArrayList<>();
    private final FormRow capRow;
    private final FormRow messagesPerSampleRow;
    private final FormRow hostsRow;
    private final JComboBox<JMeterTreeNode> groupCombo = new JComboBox<>();
    private final JComboBox<WorkloadInputs.TargetType> targetCombo =
            new JComboBox<>(WorkloadInputs.TargetType.values());
    private final JTextField target = new JTextField();
    private final JTextField cap = new JTextField();
    private final JTextField response = new JTextField();
    private final JTextField think = new JTextField();
    private final JTextField ramp = new JTextField();
    private final JComboBox<String> rampUnit = new JComboBox<>(new String[]{"s", "min", "h"});
    private final JTextField hold = new JTextField();
    private final JComboBox<String> holdUnit = new JComboBox<>(new String[]{"s", "min", "h"});
    private final JTextField agents = new JTextField();
    private final JTextField generatorHosts = new JTextField();
    private final JTextField maxThreadsPerGenerator = new JTextField();
    private final JTextField generatorVcpus = new JTextField();
    private final JTextField generatorRamGb = new JTextField();
    private final PlaceholderTextField samplesPerVcpu = new PlaceholderTextField();
    private final JTextField messagesPerSample = new JTextField("1");
    private final JTextField prefix = new JTextField();
    private final LoadShapeChart chart = new LoadShapeChart();
    private final JLabel errorLabel = new JLabel(" ");
    private final JLabel[] resultValues = new JLabel[10];
    private final JTextArea warningsArea = notesArea(2, ThemeColors.warning());
    private final JTextArea assumptionsArea = notesArea(2, ThemeColors.info());
    private final JTextArea infoArea = notesArea(2, ThemeColors.info());
    private final JTextArea parsedArea = notesArea(2, ThemeColors.secondaryText());
    private JPanel notes;
    private JPanel columns;
    private JScrollPane previewScroll;
    private final JTextArea cliArea = new JTextArea(4, 24);
    private final JButton applyButton = new JButton("Apply to Thread Group");
    private final JButton copyButton = new JButton("Copy");
    private final JComboBox<CommandShell> shellCombo = new JComboBox<>(CommandShell.values());
    private final JButton askAiButton = new JButton("Ask AI");
    private final JButton copySuggestionButton = new JButton("Copy");
    private final JLabel hostsSourceLabel = createHostsSourceLabel();
    private final JPanel hostsFieldPanel = new JPanel(new BorderLayout());
    private final JPanel aiPanel = new JPanel(new BorderLayout(UiTokens.SPACE_2, UiTokens.SPACE_1));
    private final PlaceholderTextArea aiGoal = new PlaceholderTextArea(3, 30);
    private final JLabel aiErrorLabel = new JLabel(" ");
    private final JPanel aiSuggestionPanel = new JPanel();
    private final JTextArea aiSuggestionText = notesArea(3, ThemeColors.info());
    private final Function<String, String> aiCall;
    private WorkloadAiAssistant.Suggestion latestSuggestion;
    private SwingWorker<WorkloadAiAssistant.Suggestion, Void> aiWorker;
    private long aiRequestId;
    private boolean aiPanelVisible;
    private boolean hostsFromPropertiesVisible;
    private boolean prefixEdited;
    private boolean capRowVisible;
    private boolean messagesPerSampleRowVisible;
    private boolean completed;
    private boolean updatingPreview;
    private boolean stackedColumns;
    private WorkloadFormState.Evaluation evaluation;

    public WorkloadDesignerDialog(LoadCommandHandler.Preparation preparation,
                                  Consumer<Completion> onFinish) {
        this(preparation, onFinish, null);
    }

    public WorkloadDesignerDialog(LoadCommandHandler.Preparation preparation,
                                  Consumer<Completion> onFinish, Function<String, String> aiCall) {
        super(mainFrame(), "Workload Designer", ModalityType.APPLICATION_MODAL);
        this.preparation = preparation;
        this.onFinish = onFinish == null ? ignored -> { } : onFinish;
        this.aiCall = aiCall;
        shellCombo.setSelectedItem(CommandShell.forOsName(System.getProperty("os.name")));
        maxThreadsPerGenerator.setToolTipText(
                "From a calibration run on one generator at under 80% CPU");
        samplesPerVcpu.setPlaceholder("Default: 250 samples/s per vCPU");
        hostsSourceLabel.setForeground(ThemeColors.secondaryText());
        hostsSourceLabel.setFont(UiTokens.caption(hostsSourceLabel.getFont()));
        hostsFieldPanel.setOpaque(false);
        hostsFieldPanel.add(generatorHosts, BorderLayout.CENTER);
        hostsFieldPanel.add(hostsSourceLabel, BorderLayout.SOUTH);
        aiPanel.setOpaque(false);
        aiSuggestionPanel.setOpaque(false);
        capRow = new FormRow(new JLabel("<html>Cap at samples<br>per second</html>"), cap);
        messagesPerSampleRow = new FormRow(new JLabel("Messages per sample"), messagesPerSample);
        hostsRow = new FormRow(new JLabel("Generator hosts"), hostsFieldPanel);
        buildAiPanel();
        setContentPane(buildContent());
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                cancel();
            }
        });
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "cancel");
        getRootPane().getActionMap().put("cancel", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                cancel();
            }
        });
        setPreferredSize(new Dimension(1140, 840));
        GraphicsConfiguration configuration = getGraphicsConfiguration();
        setSize(sizeWithinScreen(getPreferredSize(), configuration.getBounds(),
                Toolkit.getDefaultToolkit().getScreenInsets(configuration)));
        setLocationRelativeTo(getOwner());
        updateColumnsLayout();
        for (JTextArea area : List.of(warningsArea, assumptionsArea, infoArea, parsedArea)) {
            area.addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent event) {
                    updateNotesRows(area);
                }
            });
        }
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                updateColumnsLayout();
                fitChartToPreviewWidth();
            }
        });
        prefill(preparation.spec());
        installListeners();
        updatePreview();
    }

    private JPanel buildContent() {
        JPanel root = new JPanel(new BorderLayout(UiTokens.SPACE_3, UiTokens.SPACE_3));
        root.setBorder(BorderFactory.createEmptyBorder(UiTokens.SPACE_4, UiTokens.SPACE_4,
                UiTokens.SPACE_3, UiTokens.SPACE_4));
        root.setBackground(ThemeColors.canvas());
        JPanel header = new JPanel(new BorderLayout(0, UiTokens.SPACE_1));
        header.setOpaque(false);
        JLabel title = new JLabel("Workload Designer");
        title.setFont(UiTokens.title(title.getFont()));
        JLabel caption = new JLabel("<html>Little's Law: threads ≈ samples per second × (response time + think time), "
                + "sized with 1.5× headroom.<br>Values are written as ${__P()} properties so CI can override them.</html>");
        caption.setFont(UiTokens.caption(caption.getFont()));
        caption.setForeground(ThemeColors.secondaryText());
        header.add(title, BorderLayout.NORTH);
        header.add(caption, BorderLayout.CENTER);
        root.add(header, BorderLayout.NORTH);
        columns = new JPanel(new GridLayout(1, 2, UiTokens.SPACE_4, 0));
        columns.setOpaque(false);
        columns.add(scrollColumn(buildForm()));
        previewScroll = scrollColumn(buildPreview());
        previewScroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                fitChartToPreviewWidth();
            }
        });
        columns.add(previewScroll);
        root.add(columns, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        errorLabel.setForeground(ThemeColors.error());
        footer.add(errorLabel, BorderLayout.CENTER);
        JPanel leftButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        leftButtons.setOpaque(false);
        askAiButton.setVisible(aiCall != null);
        askAiButton.addActionListener(event -> openAiPanel());
        if (aiCall != null) {
            leftButtons.add(askAiButton);
        }
        footer.add(leftButtons, BorderLayout.WEST);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiTokens.SPACE_2, 0));
        buttons.setOpaque(false);
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(event -> cancel());
        applyButton.addActionListener(event -> apply());
        buttons.add(cancel);
        buttons.add(applyButton);
        footer.add(buttons, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);
        return root;
    }

    private static JScrollPane scrollColumn(Component content) {
        JScrollPane scrollPane = new JScrollPane(content);
        scrollPane.setBorder(null);
        scrollPane.setOpaque(false);
        scrollPane.setBackground(ThemeColors.canvas());
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.getViewport().setBackground(ThemeColors.canvas());
        scrollPane.getViewport().setOpaque(true);
        return scrollPane;
    }

    static Dimension sizeWithinScreen(Dimension preferred, Rectangle bounds, Insets insets) {
        int usableWidth = Math.max(1, bounds.width - insets.left - insets.right);
        int usableHeight = Math.max(1, bounds.height - insets.top - insets.bottom);
        return new Dimension(Math.min(preferred.width, (int) (usableWidth * 0.9)),
                Math.min(preferred.height, (int) (usableHeight * 0.9)));
    }

    private void updateColumnsLayout() {
        if (columns == null) {
            return;
        }
        boolean stack = getWidth() < 900;
        if (stackedColumns != stack) {
            stackedColumns = stack;
            columns.setLayout(stack
                    ? new GridLayout(2, 1, UiTokens.SPACE_3, 0)
                    : new GridLayout(1, 2, UiTokens.SPACE_4, 0));
            columns.revalidate();
        }
    }

    private void fitChartToPreviewWidth() {
        if (previewScroll == null) {
            return;
        }
        int viewportWidth = previewScroll.getViewport().getWidth();
        if (viewportWidth < 1) {
            return;
        }
        Dimension preferred = chart.getPreferredSize();
        int width = Math.min(420, viewportWidth);
        if (preferred.width != width) {
            chart.setPreferredSize(new Dimension(width, preferred.height));
            chart.revalidate();
        }
    }

    private JPanel buildForm() {
        groupCombo.setRenderer((list, value, index, selected, focus) ->
                new JLabel(value == null ? "" : value.getName()));
        for (JMeterTreeNode node : preparation.threadGroups()) {
            groupCombo.addItem(node);
        }
        groupCombo.setSelectedItem(preparation.selectedGroup());
        addFormRow("Thread Group", groupCombo);
        addFormRow("Target type", targetCombo);
        addFormRow("Target value", target);
        formRows.add(messagesPerSampleRow);
        formRows.add(capRow);
        addFormRow("<html>Average response time<br>(ms, default 500)</html>", response);
        addFormRow("<html>Think time per sample<br>(ms, default 0)</html>", think);
        addFormRow("<html>Ramp-up<br>(default 10% of hold)</html>", withUnit(ramp, rampUnit));
        addFormRow("<html>Hold<br>(default 10 min)</html>", withUnit(hold, holdUnit));
        formRows.add(hostsRow);
        addFormRow("Generator vCPUs", generatorVcpus);
        addFormRow("Generator RAM (GB)", generatorRamGb);
        addFormRow("<html>Measured max threads per<br>generator (optional)</html>", maxThreadsPerGenerator);
        addFormRow("<html>Load generators<br>(default 1)</html>", agents);
        addFormRow("Samples/s per vCPU", samplesPerVcpu);
        addFormRow("Property prefix", prefix);
        targetCombo.setRenderer((list, value, index, selected, focus) ->
                new JLabel(targetName(value)));
        rebuildFormRows();
        return form;
    }

    private void buildAiPanel() {
        JTextField inputStyle = new JTextField();
        aiGoal.setLineWrap(true);
        aiGoal.setWrapStyleWord(true);
        aiGoal.setFont(inputStyle.getFont());
        aiGoal.setForeground(inputStyle.getForeground());
        aiGoal.setBackground(inputStyle.getBackground());
        aiGoal.setCaretColor(inputStyle.getCaretColor());
        aiGoal.setSelectionColor(inputStyle.getSelectionColor());
        aiGoal.setMargin(inputStyle.getMargin());
        aiGoal.setBorder(inputStyle.getBorder());
        aiGoal.setOpaque(inputStyle.isOpaque());
        aiGoal.setPlaceholder(
                "e.g. Black Friday peak, 2x last year's 120k orders/hour, checkout in 1 of 10 visits");
        JPanel content = new JPanel(new BorderLayout(UiTokens.SPACE_1, 0));
        content.setOpaque(false);
        content.setBorder(BorderFactory.createTitledBorder("Describe the goal"));
        JScrollPane goalScroll = new JScrollPane(aiGoal);
        goalScroll.setBorder(BorderFactory.createEmptyBorder());
        goalScroll.setOpaque(false);
        goalScroll.getViewport().setOpaque(false);
        content.add(goalScroll, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiTokens.SPACE_1, 0));
        actions.setOpaque(false);
        JButton run = new JButton("Run");
        JButton close = new JButton("Close");
        run.addActionListener(event -> runAi(run));
        close.addActionListener(event -> closeAiPanel(run));
        actions.add(aiErrorLabel);
        actions.add(run);
        actions.add(close);
        content.add(actions, BorderLayout.SOUTH);
        aiPanel.add(content, BorderLayout.CENTER);
        aiPanel.setVisible(false);
        aiErrorLabel.setForeground(ThemeColors.error());

        aiSuggestionPanel.setLayout(new BorderLayout(UiTokens.SPACE_1, UiTokens.SPACE_1));
        JLabel title = new JLabel("AI suggestion. Check the values before applying.");
        title.setForeground(ThemeColors.info());
        aiSuggestionPanel.add(title, BorderLayout.NORTH);
        aiSuggestionText.setRows(3);
        JScrollPane suggestionScroll = new JScrollPane(aiSuggestionText);
        suggestionScroll.setBorder(null);
        suggestionScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        aiSuggestionPanel.add(suggestionScroll, BorderLayout.CENTER);
        copySuggestionButton.addActionListener(event -> copySuggestionSummary());
        aiSuggestionPanel.add(copySuggestionButton, BorderLayout.EAST);
        aiSuggestionPanel.setVisible(false);
    }

    static JLabel createHostsSourceLabel() {
        JLabel label = new JLabel("From remote_hosts in jmeter.properties");
        label.setVisible(false);
        return label;
    }

    private void openAiPanel() {
        if (completed) {
            return;
        }
        aiPanelVisible = true;
        aiPanel.setVisible(true);
        rebuildFormRows();
        aiGoal.requestFocusInWindow();
    }

    private void closeAiPanel(JButton runButton) {
        aiRequestId++;
        if (aiWorker != null) {
            aiWorker.cancel(true);
            aiWorker = null;
        }
        runButton.setEnabled(true);
        runButton.setText("Run");
        aiPanelVisible = false;
        aiPanel.setVisible(false);
        rebuildFormRows();
    }

    private void runAi(JButton runButton) {
        if (aiCall == null || completed || aiWorker != null && !aiWorker.isDone()) {
            return;
        }
        if (aiGoal.getText().isBlank()) {
            aiErrorLabel.setText("Describe the workload goal first.");
            return;
        }
        aiErrorLabel.setText(" ");
        runButton.setEnabled(false);
        runButton.setText("Working...");
        String prompt;
        try {
            prompt = WorkloadAiAssistant.buildPrompt(aiGoal.getText(), currentFormValues(),
                    LoadCommandHandler.planSummary((JMeterTreeNode) groupCombo.getSelectedItem()));
        } catch (RuntimeException e) {
            runButton.setEnabled(true);
            runButton.setText("Run");
            failAi(e.getMessage() == null ? "Could not prepare the AI prompt." : e.getMessage());
            return;
        }
        long requestId = ++aiRequestId;
        Telemetry.record(TelemetryFeature.LOAD_AI_USED);
        aiWorker = new SwingWorker<>() {
            @Override
            protected WorkloadAiAssistant.Suggestion doInBackground() {
                return WorkloadAiAssistant.parse(aiCall.apply(prompt));
            }

            @Override
            protected void done() {
                if (requestId != aiRequestId || completed || isCancelled()) {
                    return;
                }
                aiWorker = null;
                runButton.setEnabled(true);
                runButton.setText("Run");
                try {
                    showAiSuggestion(get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failAi("The AI suggestion was interrupted.");
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    String message = cause instanceof IllegalArgumentException
                            ? cause.getMessage()
                            : ProviderErrors.describe(cause, "suggesting workload inputs", false);
                    failAi(message == null ? "Could not read the AI suggestion." : message);
                }
            }
        };
        aiWorker.execute();
    }

    private Map<String, Object> currentFormValues() {
        Map<String, Object> values = new LinkedHashMap<>();
        WorkloadInputs.TargetType type = (WorkloadInputs.TargetType) targetCombo.getSelectedItem();
        values.put("target_type", jsonTargetType(type));
        values.put("target_value", target.getText());
        values.put("rate_cap_per_second", blankToNull(cap.getText()));
        values.put("response_ms", blankToNull(response.getText()));
        values.put("think_ms", blankToNull(think.getText()));
        values.put("ramp_seconds", blankToNull(durationSecondsText(ramp, rampUnit)));
        values.put("hold_seconds", blankToNull(durationSecondsText(hold, holdUnit)));
        values.put("samples_per_vcpu", blankToNull(samplesPerVcpu.getText()));
        values.put("generator_vcpus", blankToNull(generatorVcpus.getText()));
        values.put("generator_ram_gb", blankToNull(generatorRamGb.getText()));
        values.put("max_threads_per_generator", blankToNull(maxThreadsPerGenerator.getText()));
        return values;
    }

    private static String jsonTargetType(WorkloadInputs.TargetType type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case REQUEST_RATE -> "samples_per_second";
            case ITERATION_RATE -> "iterations_per_second";
            case MESSAGE_RATE -> "messages_per_second";
            case USERS -> "users";
        };
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String durationSecondsText(JTextField field, JComboBox<String> unit) {
        String value = blankToNull(field.getText());
        if (value == null) {
            return null;
        }
        long multiplier = switch ((String) unit.getSelectedItem()) {
            case "min" -> 60;
            case "h" -> 3600;
            default -> 1;
        };
        try {
            return Long.toString(Math.round(Double.parseDouble(value) * multiplier));
        } catch (NumberFormatException e) {
            return value;
        }
    }

    private void showAiSuggestion(WorkloadAiAssistant.Suggestion suggestion) {
        latestSuggestion = suggestion;
        targetCombo.setSelectedItem(suggestion.targetType());
        target.setText(WorkloadCalculatorFormat.format(suggestion.targetValue()));
        if (suggestion.rateCap() != null) {
            cap.setText(WorkloadCalculatorFormat.format(suggestion.rateCap()));
        }
        setSuggestedLong(response, suggestion.responseMs());
        setSuggestedLong(think, suggestion.thinkMs());
        setSuggestedDuration(ramp, rampUnit, suggestion.rampSeconds());
        setSuggestedDuration(hold, holdUnit, suggestion.holdSeconds());
        if (suggestion.samplesPerVcpu() != null) {
            samplesPerVcpu.setText(WorkloadCalculatorFormat.format(suggestion.samplesPerVcpu()));
        }
        StringBuilder text = new StringBuilder(suggestion.summary());
        appendSuggestionSection(text, "Reasoning", suggestion.reasoning());
        appendSuggestionSection(text, "Risks", suggestion.risks());
        aiSuggestionText.setText(text.toString());
        aiSuggestionText.setCaretPosition(0);
        aiSuggestionPanel.setVisible(true);
        copySuggestionButton.setEnabled(true);
        updatePreview();
    }

    private static void appendSuggestionSection(StringBuilder text, String heading, List<String> items) {
        if (!items.isEmpty()) {
            text.append("\n\n").append(heading);
            items.forEach(item -> text.append("\n• ").append(item));
        }
    }

    private static void setSuggestedLong(JTextField field, Double value) {
        if (value != null) {
            field.setText(Long.toString(Math.round(value)));
        }
    }

    private static void setSuggestedDuration(JTextField field, JComboBox<String> unit, Double value) {
        if (value != null) {
            setDuration(field, unit, Math.round(value));
        }
    }

    private void failAi(String message) {
        Telemetry.record(TelemetryFeature.LOAD_AI_FAILED);
        aiErrorLabel.setText(message);
    }

    private void copySuggestionSummary() {
        if (latestSuggestion == null) {
            return;
        }
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(latestSuggestion.summary()), null);
            copySuggestionButton.setText("Copied");
            javax.swing.Timer timer = new javax.swing.Timer(1500,
                    event -> copySuggestionButton.setText("Copy"));
            timer.setRepeats(false);
            timer.start();
        } catch (Exception ignored) {
            // Clipboard access is not available in every desktop environment.
        }
    }

    void showAiSuggestionForRender(WorkloadAiAssistant.Suggestion suggestion) {
        openAiPanel();
        aiGoal.setText("Black Friday peak, 2x last year's 120k orders/hour");
        showAiSuggestion(suggestion);
    }

    private void addFormRow(String label, JComponent field) {
        formRows.add(new FormRow(new JLabel(label), field));
    }

    private void rebuildFormRows() {
        form.removeAll();
        int gridy = 0;
        if (aiPanelVisible) {
            GridBagConstraints panelConstraints = new GridBagConstraints();
            panelConstraints.gridx = 0;
            panelConstraints.gridy = gridy++;
            panelConstraints.gridwidth = 2;
            panelConstraints.weightx = 1;
            panelConstraints.fill = GridBagConstraints.HORIZONTAL;
            panelConstraints.insets = new Insets(0, 0, UiTokens.SPACE_2, 0);
            form.add(aiPanel, panelConstraints);
        }
        for (FormRow row : formRows) {
            if ((row == capRow && !capRowVisible)
                    || (row == messagesPerSampleRow && !messagesPerSampleRowVisible)) {
                continue;
            }
            GridBagConstraints labelConstraints = new GridBagConstraints();
            labelConstraints.gridx = 0;
            labelConstraints.gridy = gridy;
            labelConstraints.anchor = GridBagConstraints.LINE_START;
            labelConstraints.fill = GridBagConstraints.HORIZONTAL;
            labelConstraints.insets = new Insets(0, 0, UiTokens.SPACE_1, UiTokens.SPACE_2);
            form.add(row.label(), labelConstraints);

            GridBagConstraints fieldConstraints = new GridBagConstraints();
            fieldConstraints.gridx = 1;
            fieldConstraints.gridy = gridy++;
            fieldConstraints.weightx = 1;
            fieldConstraints.anchor = GridBagConstraints.LINE_START;
            fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
            fieldConstraints.insets = new Insets(0, 0, UiTokens.SPACE_1, 0);
            form.add(row.field(), fieldConstraints);
        }
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridx = 0;
        filler.gridy = gridy;
        filler.gridwidth = 2;
        filler.weightx = 1;
        filler.weighty = 1;
        filler.fill = GridBagConstraints.VERTICAL;
        JPanel fillerPanel = new JPanel();
        fillerPanel.setOpaque(false);
        form.add(fillerPanel, filler);
        form.revalidate();
        form.repaint();
    }

    private JPanel buildPreview() {
        JPanel preview = new ScrollablePanel(new BorderLayout(UiTokens.SPACE_2, UiTokens.SPACE_2));
        preview.setOpaque(false);
        preview.add(chart, BorderLayout.NORTH);
        JPanel lower = new JPanel(new BorderLayout(UiTokens.SPACE_2, UiTokens.SPACE_2));
        lower.setOpaque(false);
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);
        String[] labels = {"Threads", "Samples / iteration", "Samples / second",
                "Throughput samples / min", "Total duration", "Per agent",
                "Sampler mix", "Generators needed", "Capacity per generator", "Heap per generator"};
        for (int index = 0; index < labels.length; index++) {
            GridBagConstraints labelConstraints = new GridBagConstraints();
            labelConstraints.gridx = 0;
            labelConstraints.gridy = index;
            labelConstraints.anchor = GridBagConstraints.NORTHWEST;
            labelConstraints.fill = GridBagConstraints.HORIZONTAL;
            labelConstraints.insets = new Insets(0, 0, UiTokens.SPACE_1, UiTokens.SPACE_2);
            grid.add(new JLabel(labels[index]), labelConstraints);
            resultValues[index] = new JLabel("-");
            GridBagConstraints valueConstraints = new GridBagConstraints();
            valueConstraints.gridx = 1;
            valueConstraints.gridy = index;
            valueConstraints.weightx = 1;
            valueConstraints.anchor = GridBagConstraints.NORTHWEST;
            valueConstraints.fill = GridBagConstraints.HORIZONTAL;
            valueConstraints.insets = new Insets(0, 0, UiTokens.SPACE_1, 0);
            grid.add(resultValues[index], valueConstraints);
        }
        lower.add(grid, BorderLayout.NORTH);
        notes = new ScrollablePanel(new GridBagLayout());
        JScrollPane notesScroll = new JScrollPane(notes);
        notesScroll.setBorder(BorderFactory.createTitledBorder("Notes"));
        notesScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        lower.add(notesScroll, BorderLayout.CENTER);
        cliArea.setEditable(false);
        cliArea.setLineWrap(true);
        cliArea.setWrapStyleWord(true);
        cliArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        cliArea.setBackground(ThemeColors.codeBackground());
        cliArea.setForeground(ThemeColors.foreground());
        JPanel cliPanel = new JPanel(new BorderLayout(UiTokens.SPACE_1, 0));
        cliPanel.setOpaque(false);
        cliPanel.setBorder(BorderFactory.createTitledBorder("Command line"));
        JPanel shellRow = new JPanel(new FlowLayout(FlowLayout.LEFT, UiTokens.SPACE_1, 0));
        shellRow.setOpaque(false);
        shellRow.add(new JLabel("Shell"));
        shellRow.add(shellCombo);
        cliPanel.add(shellRow, BorderLayout.NORTH);
        cliPanel.add(new JScrollPane(cliArea), BorderLayout.CENTER);
        copyButton.addActionListener(event -> copyCommand());
        cliPanel.add(copyButton, BorderLayout.EAST);
        lower.add(cliPanel, BorderLayout.SOUTH);
        preview.add(lower, BorderLayout.CENTER);
        return preview;
    }

    private static JPanel withUnit(JTextField field, JComboBox<String> unit) {
        unit.setPrototypeDisplayValue("min");
        JPanel panel = new JPanel(new BorderLayout(UiTokens.SPACE_1, 0));
        panel.setOpaque(false);
        panel.add(field, BorderLayout.CENTER);
        panel.add(unit, BorderLayout.EAST);
        return panel;
    }

    private static JTextArea notesArea(int rows, java.awt.Color color) {
        JTextArea area = new JTextArea(rows, 20);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(UIManager.getFont("TextArea.font"));
        area.setForeground(color);
        area.setBackground(ThemeColors.canvas());
        area.setOpaque(false);
        area.setAlignmentX(Component.LEFT_ALIGNMENT);
        area.setAlignmentY(Component.TOP_ALIGNMENT);
        area.setMaximumSize(new Dimension(Integer.MAX_VALUE, area.getPreferredSize().height));
        return area;
    }

    private static void setNotesText(JTextArea area, String text) {
        area.setText(text);
        updateNotesRows(area);
    }

    private static void updateNotesRows(JTextArea area) {
        int availableWidth = area.getWidth() - area.getInsets().left - area.getInsets().right;
        int charactersPerLine = 72;
        if (availableWidth > 0 && availableWidth < 480) {
            int characterWidth = Math.max(1, area.getFontMetrics(area.getFont()).charWidth('m'));
            charactersPerLine = Math.max(20, availableWidth / characterWidth);
        }
        int rows = 0;
        for (String line : area.getText().split("\n", -1)) {
            rows += Math.max(1, (line.length() + charactersPerLine - 1) / charactersPerLine);
        }
        int desiredRows = Math.min(18, Math.max(2, rows));
        if (area.getRows() != desiredRows) {
            area.setRows(desiredRows);
            area.setMaximumSize(new Dimension(Integer.MAX_VALUE, area.getPreferredSize().height));
        }
    }

    private void prefill(WorkloadSpec spec) {
        WorkloadInputs.TargetType type = WorkloadInputs.TargetType.REQUEST_RATE;
        String amount = "";
        if (spec.users() != null) {
            type = WorkloadInputs.TargetType.USERS;
            amount = Integer.toString(spec.users());
            if (spec.requestsPerSecond() != null) {
                cap.setText(WorkloadCalculatorFormat.format(spec.requestsPerSecond()));
            }
        } else if (spec.requestsPerSecond() != null) {
            amount = WorkloadCalculatorFormat.format(spec.requestsPerSecond());
        } else if (spec.iterationsPerSecond() != null) {
            type = WorkloadInputs.TargetType.ITERATION_RATE;
            amount = WorkloadCalculatorFormat.format(spec.iterationsPerSecond());
        } else if (spec.messagesPerSecond() != null) {
            type = WorkloadInputs.TargetType.MESSAGE_RATE;
            amount = WorkloadCalculatorFormat.format(spec.messagesPerSecond());
        }
        targetCombo.setSelectedItem(type);
        target.setText(amount);
        if (spec.generatorVcpus() != null) {
            generatorVcpus.setText(Integer.toString(spec.generatorVcpus()));
        }
        if (spec.generatorRamGb() != null) {
            generatorRamGb.setText(WorkloadCalculatorFormat.format(spec.generatorRamGb()));
        }
        if (!preparation.generatorHosts().isEmpty()) {
            generatorHosts.setText(String.join(",", preparation.generatorHosts()));
            hostsFromPropertiesVisible = preparation.hostsFromProperties();
            hostsSourceLabel.setVisible(hostsFromPropertiesVisible);
        }
        setLong(response, spec.responseMillis());
        setLong(think, spec.thinkMillis());
        setDuration(ramp, rampUnit, spec.rampSeconds());
        setDuration(hold, holdUnit, spec.holdSeconds());
        if (spec.agents() != null) {
            agents.setText(Integer.toString(spec.agents()));
        }
        prefix.setText(LoadCommandHandler.prefixFor(preparation.selectedGroup(), preparation.eligibleCount()));
    }

    private void installListeners() {
        DocumentListener listener = new DeferredPreviewListener(
                () -> {
                    if (prefix.hasFocus()) {
                        prefixEdited = true;
                    }
                    updatePreview();
                }, () -> updatingPreview);
        for (JTextField field : List.of(target, cap, response, think, ramp, hold, agents,
                generatorHosts, maxThreadsPerGenerator, messagesPerSample, prefix, generatorVcpus,
                generatorRamGb, samplesPerVcpu)) {
            field.getDocument().addDocumentListener(listener);
        }
        generatorHosts.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                hideHostsSource();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                hideHostsSource();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                hideHostsSource();
            }

            private void hideHostsSource() {
                if (hostsFromPropertiesVisible) {
                    hostsFromPropertiesVisible = false;
                    hostsSourceLabel.setVisible(false);
                    hostsFieldPanel.revalidate();
                }
            }
        });
        targetCombo.addActionListener(event -> {
            updateTargetRows();
            updatePreview();
        });
        rampUnit.addActionListener(event -> updatePreview());
        holdUnit.addActionListener(event -> updatePreview());
        groupCombo.addActionListener(event -> {
            if (!prefixEdited) {
                JMeterTreeNode node = (JMeterTreeNode) groupCombo.getSelectedItem();
                prefix.setText(LoadCommandHandler.prefixFor(node, preparation.eligibleCount()));
            }
            updatePreview();
        });
        shellCombo.addActionListener(event -> updatePreview());
    }

    private void updatePreview() {
        if (updatingPreview) {
            return;
        }
        updatingPreview = true;
        try {
            updatePreviewContent();
        } finally {
            updatingPreview = false;
        }
    }

    private void updatePreviewContent() {
        updateTargetRows();
        JMeterTreeNode node = (JMeterTreeNode) groupCombo.getSelectedItem();
        int samplers = WorkloadApplier.countEnabledSamplers(node);
        boolean scriptHeavy = LoadCommandHandler.scriptHeavy(node);
        int defaultSamplesPerVcpu = (int) (scriptHeavy
                ? WorkloadCalculator.DEFAULT_SAMPLES_PER_VCPU / 2
                : WorkloadCalculator.DEFAULT_SAMPLES_PER_VCPU);
        samplesPerVcpu.setPlaceholder("Default: " + defaultSamplesPerVcpu + " samples/s/vCPU");
        samplesPerVcpu.setToolTipText("Default: " + defaultSamplesPerVcpu + " samples/s per vCPU");
        String hostText = generatorHosts.getText().trim();
        boolean hostsSet = !hostText.isEmpty();
        agents.setEnabled(!hostsSet);
        if (hostsSet) {
            int hostCount = hostText.split(",", -1).length;
            String countText = Integer.toString(hostCount);
            if (!agents.getText().equals(countText)) {
                agents.setText(countText);
            }
        }
        WorkloadFormState.Context context = new WorkloadFormState.Context(hostText,
                maxThreadsPerGenerator.getText(), messagesPerSample.getText(),
                LoadCommandHandler.enabledSamplerClasses(node), LoadCommandHandler.jdbcPools(node),
                generatorVcpus.getText(), generatorRamGb.getText(), samplesPerVcpu.getText(),
                scriptHeavy);
        WorkloadInputs.TargetType targetType = (WorkloadInputs.TargetType) targetCombo.getSelectedItem();
        String rateCap = targetType == WorkloadInputs.TargetType.USERS ? cap.getText() : "";
        evaluation = WorkloadFormState.evaluate(targetType,
                target.getText(), rateCap, response.getText(), think.getText(), ramp.getText(),
                (String) rampUnit.getSelectedItem(), hold.getText(), (String) holdUnit.getSelectedItem(),
                agents.getText(), samplers, prefix.getText(), preparation.planFileName(), context);
        String error = samplers == 0 ? LoadCommandHandler.NO_REQUESTS : evaluation.error();
        errorLabel.setText(error == null || error.isBlank() ? " " : error);
        applyButton.setEnabled(samplers > 0 && evaluation.valid());
        WorkloadPlan plan = evaluation.plan();
        chart.setPlan(plan);
        if (plan != null) {
            resultValues[0].setText(Integer.toString(plan.threads()));
            resultValues[1].setText(Integer.toString(plan.samplersPerIteration()));
            resultValues[2].setText(samplesPerSecondText(plan));
            resultValues[3].setText(plan.rpm() == null ? "No timer" : WorkloadCalculatorFormat.format(plan.rpm()));
            resultValues[4].setText(duration(plan.durationSeconds()));
            resultValues[5].setText(plan.agents() > 1 || !plan.generatorHosts().isEmpty()
                    ? plan.perAgentThreads() + " threads"
                    + (plan.perAgentRpm() == null ? "" : ", "
                    + WorkloadCalculatorFormat.format(plan.perAgentRpm()) + " samples/min")
                    : "Single generator");
            resultValues[6].setText(plan.samplerMix().display());
            resultValues[7].setText(Integer.toString(plan.generatorsNeeded()));
            String capacity = plan.capacityPerGenerator();
            resultValues[8].setText("<html>" + capacity.replace(", ", ",<br>") + "</html>");
            resultValues[8].setToolTipText(capacity);
            String heapSize = plan.heapPerGeneratorMb() % 1024 == 0
                    ? plan.heapPerGeneratorMb() / 1024 + "g" : plan.heapPerGeneratorMb() + "m";
            resultValues[9].setText("HEAP=\"-Xms" + heapSize + " -Xmx" + heapSize + "\"");
            cliArea.setText(plan.command(selectedShell()));
            setNotesText(warningsArea, String.join("\n", plan.warnings()));
            setNotesText(assumptionsArea, String.join("\n", plan.assumptions()));
            setNotesText(infoArea, String.join("\n", plan.notes()));
        } else {
            for (JLabel value : resultValues) {
                value.setText("-");
            }
            cliArea.setText("");
            setNotesText(warningsArea, "");
            setNotesText(assumptionsArea, "");
            setNotesText(infoArea, "");
        }
        warningsArea.setVisible(!warningsArea.getText().isBlank());
        assumptionsArea.setVisible(!assumptionsArea.getText().isBlank());
        infoArea.setVisible(!infoArea.getText().isBlank());
        setNotesText(parsedArea, parsedNotes(preparation.spec()));
        parsedArea.setVisible(!parsedArea.getText().isBlank());
        notes.removeAll();
        int noteRow = 0;
        for (JTextArea area : List.of(warningsArea, assumptionsArea, infoArea, parsedArea)) {
            if (area.isVisible()) {
                area.setAlignmentX(Component.LEFT_ALIGNMENT);
                GridBagConstraints noteConstraints = new GridBagConstraints();
                noteConstraints.gridx = 0;
                noteConstraints.gridy = noteRow++;
                noteConstraints.weightx = 1;
                noteConstraints.anchor = GridBagConstraints.NORTHWEST;
                noteConstraints.fill = GridBagConstraints.HORIZONTAL;
                noteConstraints.insets = new Insets(0, 0, UiTokens.SPACE_1, 0);
                notes.add(area, noteConstraints);
            }
        }
        aiSuggestionPanel.setVisible(latestSuggestion != null);
        if (latestSuggestion != null) {
            GridBagConstraints suggestionConstraints = new GridBagConstraints();
            suggestionConstraints.gridx = 0;
            suggestionConstraints.gridy = noteRow++;
            suggestionConstraints.weightx = 1;
            suggestionConstraints.anchor = GridBagConstraints.NORTHWEST;
            suggestionConstraints.fill = GridBagConstraints.HORIZONTAL;
            suggestionConstraints.insets = new Insets(0, 0, UiTokens.SPACE_1, 0);
            notes.add(aiSuggestionPanel, suggestionConstraints);
        }
        GridBagConstraints notesFillerConstraints = new GridBagConstraints();
        notesFillerConstraints.gridx = 0;
        notesFillerConstraints.gridy = noteRow;
        notesFillerConstraints.weightx = 1;
        notesFillerConstraints.weighty = 1;
        notesFillerConstraints.fill = GridBagConstraints.VERTICAL;
        JPanel notesFiller = new JPanel();
        notesFiller.setOpaque(false);
        notes.add(notesFiller, notesFillerConstraints);
        notes.revalidate();
        notes.repaint();
    }

    private void updateTargetRows() {
        boolean users = targetCombo.getSelectedItem() == WorkloadInputs.TargetType.USERS;
        boolean messages = targetCombo.getSelectedItem() == WorkloadInputs.TargetType.MESSAGE_RATE;
        if (capRowVisible != users || messagesPerSampleRowVisible != messages) {
            capRowVisible = users;
            messagesPerSampleRowVisible = messages;
            rebuildFormRows();
        }
    }

    private void apply() {
        if (completed || evaluation == null || !evaluation.valid()) {
            return;
        }
        try {
            JMeterTreeNode node = (JMeterTreeNode) groupCombo.getSelectedItem();
            WorkloadApplier.ApplyResult result = WorkloadApplier.apply(
                    preparation.model(), node, evaluation.plan());
            cancelAiWorker();
            completed = true;
            onFinish.accept(new Completion(evaluation.plan(), result, "", false, selectedShell()));
            dispose();
        } catch (Exception e) {
            cancelAiWorker();
            completed = true;
            onFinish.accept(new Completion(null, null,
                    e.getMessage() == null ? "Could not apply the workload." : e.getMessage(),
                    false, selectedShell()));
            dispose();
        }
    }

    private void cancel() {
        if (!completed) {
            cancelAiWorker();
            completed = true;
            onFinish.accept(new Completion(null, null, "", true, selectedShell()));
        }
        dispose();
    }

    private void cancelAiWorker() {
        aiRequestId++;
        if (aiWorker != null) {
            aiWorker.cancel(true);
            aiWorker = null;
        }
    }

    private void copyCommand() {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(cliArea.getText()), null);
            copyButton.setText("Copied");
            javax.swing.Timer timer = new javax.swing.Timer(1500, event -> copyButton.setText("Copy"));
            timer.setRepeats(false);
            timer.start();
        } catch (Exception ignored) {
            // Clipboard access is not available in every desktop environment.
        }
    }

    private CommandShell selectedShell() {
        return (CommandShell) shellCombo.getSelectedItem();
    }

    private static String parsedNotes(WorkloadSpec spec) {
        List<String> notes = new ArrayList<>();
        spec.unsupported().forEach(value -> notes.add("Not supported: " + value));
        spec.unrecognized().forEach(value -> notes.add("Not understood: " + value));
        notes.addAll(spec.duplicates());
        return String.join("\n", notes);
    }

    private static String targetName(WorkloadInputs.TargetType type) {
        return switch (type) {
            case REQUEST_RATE -> "Samples per second (requests, queries, messages)";
            case ITERATION_RATE -> "Iterations per second";
            case MESSAGE_RATE -> "Messages per second";
            case USERS -> "Concurrent users";
        };
    }

    private static String duration(long seconds) {
        return seconds >= 3600 ? seconds / 3600 + " h" + (seconds % 3600 == 0 ? "" : " "
                + (seconds % 3600) / 60 + " min") : seconds / 60 + " min "
                + seconds % 60 + " s";
    }

    private static void setLong(JTextField field, Long value) {
        if (value != null) {
            field.setText(Long.toString(value));
        }
    }

    static void setDuration(JTextField field, JComboBox<String> unit, Long seconds) {
        if (seconds == null) {
            return;
        }
        List<String> available = new ArrayList<>();
        for (int index = 0; index < unit.getItemCount(); index++) {
            available.add(unit.getItemAt(index));
        }
        for (String candidate : List.of("h", "min", "s")) {
            if (!available.contains(candidate)) {
                continue;
            }
            long multiplier = switch (candidate) {
                case "h" -> 3600;
                case "min" -> 60;
                default -> 1;
            };
            if (seconds % multiplier == 0) {
                field.setText(Long.toString(seconds / multiplier));
                unit.setSelectedItem(candidate);
                return;
            }
        }
    }

    private static Frame mainFrame() {
        GuiPackage gui = GuiPackage.getInstance();
        return gui == null ? null : gui.getMainFrame();
    }

    static final class DeferredPreviewListener implements DocumentListener {
        private final Runnable update;
        private final BooleanSupplier updating;
        private boolean pending;

        DeferredPreviewListener(Runnable update, BooleanSupplier updating) {
            this.update = update;
            this.updating = updating;
        }

        @Override
        public void insertUpdate(DocumentEvent event) {
            changed();
        }

        @Override
        public void removeUpdate(DocumentEvent event) {
            changed();
        }

        @Override
        public void changedUpdate(DocumentEvent event) {
            changed();
        }

        private void changed() {
            if (pending || updating.getAsBoolean()) {
                return;
            }
            pending = true;
            SwingUtilities.invokeLater(() -> {
                pending = false;
                if (!updating.getAsBoolean()) {
                    update.run();
                }
            });
        }
    }

    public record Completion(WorkloadPlan plan, WorkloadApplier.ApplyResult result, String error,
                             boolean cancelled, CommandShell commandShell) {
        public Completion {
            commandShell = commandShell == null
                    ? CommandShell.forOsName(System.getProperty("os.name")) : commandShell;
        }

        public Completion(WorkloadPlan plan, WorkloadApplier.ApplyResult result,
                          String error, boolean cancelled) {
            this(plan, result, error, cancelled, CommandShell.forOsName(System.getProperty("os.name")));
        }
    }

    private record FormRow(JComponent label, JComponent field) {
    }

    private static final class FormPanel extends JPanel implements Scrollable {
        private FormPanel() {
            super(new GridBagLayout());
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return UiTokens.CONTROL_HEIGHT;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(UiTokens.CONTROL_HEIGHT, visibleRect.height - UiTokens.CONTROL_HEIGHT);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            Container parent = getParent();
            return parent instanceof JViewport viewport && getPreferredSize().height < viewport.getHeight();
        }
    }

    private static final class ScrollablePanel extends JPanel implements Scrollable {
        private ScrollablePanel() {
            setOpaque(false);
        }

        private ScrollablePanel(java.awt.LayoutManager layout) {
            super(layout);
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return UiTokens.CONTROL_HEIGHT;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(UiTokens.CONTROL_HEIGHT, visibleRect.height - UiTokens.CONTROL_HEIGHT);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            Container parent = getParent();
            return parent instanceof JViewport viewport && getPreferredSize().height < viewport.getHeight();
        }
    }

    private static final class WorkloadCalculatorFormat {
        private static String format(Double value) {
            return value == null ? "-" : java.math.BigDecimal.valueOf(value)
                    .stripTrailingZeros().toPlainString();
        }
    }

    private static String samplesPerSecondText(WorkloadPlan plan) {
        Double target = plan.targetRequestsPerSecond();
        Double expected = plan.expectedRequestsPerSecond();
        if (target == null) {
            return "Expected " + WorkloadCalculatorFormat.format(expected);
        }
        if (expected != null && expected < target) {
            return WorkloadCalculatorFormat.format(target) + " target, "
                    + WorkloadCalculatorFormat.format(expected) + " reachable";
        }
        return WorkloadCalculatorFormat.format(target);
    }
}
