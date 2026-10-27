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
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTable;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.action.ActionRouter;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.gui.theme.UiTokens;
import org.qainsights.jmeter.ai.load.CommandShell;
import org.qainsights.jmeter.ai.load.ArrivalMode;
import org.qainsights.jmeter.ai.load.LoadCommandHandler;
import org.qainsights.jmeter.ai.load.LoadShapeChart;
import org.qainsights.jmeter.ai.load.WorkloadApplier;
import org.qainsights.jmeter.ai.load.WorkloadAiAssistant;
import org.qainsights.jmeter.ai.load.WorkloadCalculator;
import org.qainsights.jmeter.ai.load.WorkloadFormState;
import org.qainsights.jmeter.ai.load.WorkloadInputs;
import org.qainsights.jmeter.ai.load.WorkloadPlan;
import org.qainsights.jmeter.ai.load.WorkloadShape;
import org.qainsights.jmeter.ai.load.WorkloadShapeConfig;
import org.qainsights.jmeter.ai.load.WorkloadStage;
import org.qainsights.jmeter.ai.load.WorkloadSpec;
import org.qainsights.jmeter.ai.telemetry.Telemetry;
import org.qainsights.jmeter.ai.telemetry.TelemetryFeature;
import org.qainsights.jmeter.ai.utils.ProviderErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Modal, deterministic workload editor with a live load-shape preview. */
public final class WorkloadDesignerDialog extends JDialog {
    private static final int MAX_CUSTOM_ROWS = 10;
    private static final String OPEN_MODEL_ASK_AI_TOOLTIP = "Ask AI fills Load and Endurance workloads.";
    private static final String ASK_AI_RUNNING_TOOLTIP = "Ask AI is working on a suggestion.";
    private static final Logger log = LoggerFactory.getLogger(WorkloadDesignerDialog.class);
    private static final String WIDTH_PREFERENCE = "workloadDesigner.width";
    private static final String HEIGHT_PREFERENCE = "workloadDesigner.height";

    private final LoadCommandHandler.Preparation preparation;
    private final Consumer<Completion> onFinish;
    private final Preferences preferences;
    private final FormPanel form = new FormPanel();
    private final List<FormRow> formRows = new ArrayList<>();
    private final FormRow capRow;
    private final FormRow messagesPerSampleRow;
    private final FormRow hostsRow;
    private FormRow targetRow;
    private FormRow rampRow;
    private FormRow holdRow;
    private FormRow rampDownRow;
    private FormRow arrivalsRow;
    private final Map<WorkloadShape, List<FormRow>> shapeRows = new LinkedHashMap<>();
    private final JComboBox<JMeterTreeNode> groupCombo = new JComboBox<>();
    private final JComboBox<WorkloadShape> shapeCombo = new JComboBox<>(WorkloadShape.values());
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
    private final PlaceholderTextField stepStart = new PlaceholderTextField();
    private final PlaceholderTextField stepEnd = new PlaceholderTextField();
    private final PlaceholderTextField stepSize = new PlaceholderTextField();
    private final PlaceholderTextField stepDuration = new PlaceholderTextField();
    private final PlaceholderTextField stepRamp = new PlaceholderTextField();
    private final PlaceholderTextField spikeBaseline = new PlaceholderTextField();
    private final PlaceholderTextField spikePeak = new PlaceholderTextField();
    private final PlaceholderTextField spikeWarmup = new PlaceholderTextField();
    private final PlaceholderTextField spikeDuration = new PlaceholderTextField();
    private final PlaceholderTextField spikeRecovery = new PlaceholderTextField();
    private final PlaceholderTextField stressStart = new PlaceholderTextField();
    private final PlaceholderTextField stressCeiling = new PlaceholderTextField();
    private final PlaceholderTextField stressStep = new PlaceholderTextField();
    private final PlaceholderTextField stressDuration = new PlaceholderTextField();
    private final PlaceholderTextField rampDown = new PlaceholderTextField();
    private final JCheckBox rampDownEnabled = new JCheckBox("Enable");
    private final JComboBox<ArrivalMode> arrivalModeCombo = new JComboBox<>(ArrivalMode.values());
    private final DefaultTableModel customStageModel = new DefaultTableModel(
            new Object[][]{{"100", "10", "60"}}, new Object[]{"Target (samples/s)", "Ramp (s)", "Hold (s)"}) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return true;
        }
    };
    private final JTable customStagesTable = new JTable(customStageModel);
    private JScrollPane customStagesScroll;
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
    private JLabel titleLabel;
    private JLabel captionLabel;
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
    private boolean aiRequestRunning;
    private boolean hostsFromPropertiesVisible;
    private boolean prefixEdited;
    private boolean capRowVisible;
    private boolean messagesPerSampleRowVisible;
    private boolean completed;
    private boolean updatingPreview;
    private boolean stackedColumns;
    private WorkloadFormState.Evaluation evaluation;
    private final PropertyChangeListener lookAndFeelListener = event -> {
        if ("lookAndFeel".equals(event.getPropertyName())) {
            SwingUtilities.invokeLater(this::refreshLookAndFeel);
        }
    };

    public WorkloadDesignerDialog(LoadCommandHandler.Preparation preparation,
                                  Consumer<Completion> onFinish) {
        this(preparation, onFinish, null);
    }

    public WorkloadDesignerDialog(LoadCommandHandler.Preparation preparation,
                                  Consumer<Completion> onFinish, Function<String, String> aiCall) {
        this(preparation, onFinish, aiCall, userPreferences());
    }

    WorkloadDesignerDialog(LoadCommandHandler.Preparation preparation,
                           Consumer<Completion> onFinish, Function<String, String> aiCall,
                           Preferences preferences) {
        super(mainFrame(), "Workload Designer", ModalityType.APPLICATION_MODAL);
        this.preparation = preparation;
        this.onFinish = onFinish == null ? ignored -> { } : onFinish;
        this.aiCall = aiCall;
        this.preferences = preferences;
        shellCombo.setSelectedItem(CommandShell.forOsName(System.getProperty("os.name")));
        maxThreadsPerGenerator.setToolTipText(
                "From a calibration run on one generator at under 80% CPU");
        samplesPerVcpu.setPlaceholder("Default: 250 samples/s per vCPU");
        stepStart.setPlaceholder("Default: 100");
        stepEnd.setPlaceholder("Default: 500");
        stepSize.setPlaceholder("Default: 100");
        stepDuration.setPlaceholder("Default: 300");
        spikeBaseline.setPlaceholder("Default: 100");
        spikePeak.setPlaceholder("Default: 500");
        spikeWarmup.setPlaceholder("Default: 60");
        spikeDuration.setPlaceholder("Default: 60");
        spikeRecovery.setPlaceholder("Default: 60");
        stressStart.setPlaceholder("Default: 100");
        stressCeiling.setPlaceholder("Default: 1000");
        stressStep.setPlaceholder("Default: 100");
        stressDuration.setPlaceholder("Default: 120");
        rampDown.setPlaceholder("Default: 120");
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
        installZoomBindings();
        setPreferredSize(new Dimension(1140, 840));
        GraphicsConfiguration configuration = getGraphicsConfiguration();
        Rectangle bounds = configuration.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        Dimension minimumSize = minimumSizeWithinScreen(bounds, insets);
        setMinimumSize(minimumSize);
        setSize(initialSize(readSavedSize(preferences), getPreferredSize(), minimumSize, bounds, insets));
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
        UIManager.addPropertyChangeListener(lookAndFeelListener);
    }

    private JPanel buildContent() {
        JPanel root = new JPanel(new BorderLayout(UiTokens.SPACE_3, UiTokens.SPACE_3));
        root.setBorder(BorderFactory.createEmptyBorder(UiTokens.SPACE_4, UiTokens.SPACE_4,
                UiTokens.SPACE_3, UiTokens.SPACE_4));
        root.setBackground(ThemeColors.canvas());
        JPanel header = new JPanel(new BorderLayout(0, UiTokens.SPACE_1));
        header.setOpaque(false);
        titleLabel = new JLabel("Workload Designer");
        titleLabel.setFont(UiTokens.title(titleLabel.getFont()));
        captionLabel = new JLabel("<html>Little's Law: threads ≈ samples per second × (response time + think time), "
                + "sized with 1.5× headroom.<br>Values are written as ${__P()} properties so CI can override them.</html>");
        captionLabel.setFont(UiTokens.caption(captionLabel.getFont()));
        captionLabel.setForeground(ThemeColors.secondaryText());
        header.add(titleLabel, BorderLayout.NORTH);
        header.add(captionLabel, BorderLayout.CENTER);
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
        Dimension usable = usableScreenSize(bounds, insets);
        return new Dimension(Math.min(preferred.width, (int) (usable.width * 0.9)),
                Math.min(preferred.height, (int) (usable.height * 0.9)));
    }

    static Map<KeyStroke, String> zoomKeyStrokes(int menuMask) {
        Map<KeyStroke, String> keyStrokes = new LinkedHashMap<>();
        keyStrokes.put(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, menuMask), ActionNames.ZOOM_IN);
        keyStrokes.put(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS,
                menuMask | InputEvent.SHIFT_DOWN_MASK), ActionNames.ZOOM_IN);
        keyStrokes.put(KeyStroke.getKeyStroke(KeyEvent.VK_PLUS, menuMask), ActionNames.ZOOM_IN);
        keyStrokes.put(KeyStroke.getKeyStroke(KeyEvent.VK_ADD, menuMask), ActionNames.ZOOM_IN);
        keyStrokes.put(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, menuMask), ActionNames.ZOOM_OUT);
        keyStrokes.put(KeyStroke.getKeyStroke(KeyEvent.VK_SUBTRACT, menuMask), ActionNames.ZOOM_OUT);
        return Map.copyOf(keyStrokes);
    }

    static Dimension initialSize(Dimension saved, Dimension fallback, Dimension minimum,
                                 Rectangle bounds, Insets insets) {
        Dimension usable = usableScreenSize(bounds, insets);
        Dimension screenClamped = sizeWithinScreen(saved == null ? fallback : saved, bounds, insets);
        int minimumWidth = Math.min(Math.max(1, minimum.width), usable.width);
        int minimumHeight = Math.min(Math.max(1, minimum.height), usable.height);
        return new Dimension(Math.max(minimumWidth, screenClamped.width),
                Math.max(minimumHeight, screenClamped.height));
    }

    static Dimension minimumSizeWithinScreen(Rectangle bounds, Insets insets) {
        Dimension usable = usableScreenSize(bounds, insets);
        return new Dimension(Math.min(640, usable.width), Math.min(480, usable.height));
    }

    private static Dimension usableScreenSize(Rectangle bounds, Insets insets) {
        int usableWidth = Math.max(1, bounds.width - insets.left - insets.right);
        int usableHeight = Math.max(1, bounds.height - insets.top - insets.bottom);
        return new Dimension(usableWidth, usableHeight);
    }

    private static Preferences userPreferences() {
        try {
            return Preferences.userNodeForPackage(WorkloadDesignerDialog.class);
        } catch (SecurityException e) {
            log.debug("Could not access Workload Designer size preferences.", e);
            return null;
        }
    }

    static Dimension readSavedSize(Preferences preferences) {
        if (preferences == null) {
            return null;
        }
        try {
            preferences.sync();
            int width = preferences.getInt(WIDTH_PREFERENCE, 0);
            int height = preferences.getInt(HEIGHT_PREFERENCE, 0);
            if (width <= 0 || height <= 0) {
                return null;
            }
            return new Dimension(width, height);
        } catch (BackingStoreException | SecurityException e) {
            log.debug("Could not read Workload Designer size preferences.", e);
            return null;
        }
    }

    static void saveWindowSize(Preferences preferences, Dimension size) {
        if (preferences == null || size == null || size.width <= 0 || size.height <= 0) {
            return;
        }
        try {
            preferences.putInt(WIDTH_PREFERENCE, size.width);
            preferences.putInt(HEIGHT_PREFERENCE, size.height);
            preferences.flush();
        } catch (BackingStoreException | SecurityException e) {
            log.debug("Could not save Workload Designer size preferences.", e);
        }
    }

    @Override
    public void dispose() {
        UIManager.removePropertyChangeListener(lookAndFeelListener);
        saveWindowSize(preferences, getSize());
        super.dispose();
    }

    private void installZoomBindings() {
        Map<KeyStroke, String> keyStrokes =
                zoomKeyStrokes(Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        for (Map.Entry<KeyStroke, String> entry : keyStrokes.entrySet()) {
            String binding = "workload-designer-zoom-" + entry.getKey().getKeyCode()
                    + "-" + entry.getKey().getModifiers();
            getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                    .put(entry.getKey(), binding);
            getRootPane().getActionMap().put(binding, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent event) {
                    if (GuiPackage.getInstance() != null) {
                        ActionRouter.getInstance().doActionNow(new ActionEvent(
                                WorkloadDesignerDialog.this, ActionEvent.ACTION_PERFORMED, entry.getValue()));
                    }
                }
            });
        }
    }

    private void refreshLookAndFeel() {
        SwingUtilities.updateComponentTreeUI(this);
        installZoomBindings();
        Font labelFont = UIManager.getFont("Label.font");
        if (labelFont == null) {
            labelFont = new JLabel().getFont();
        }
        Font textAreaFont = UIManager.getFont("TextArea.font");
        if (textAreaFont == null) {
            textAreaFont = new JTextArea().getFont();
        }
        titleLabel.setFont(UiTokens.title(labelFont));
        captionLabel.setFont(UiTokens.caption(labelFont));
        hostsSourceLabel.setFont(UiTokens.caption(labelFont));
        aiGoal.setFont(new JTextField().getFont());
        for (JTextArea area : List.of(warningsArea, assumptionsArea, infoArea, parsedArea, aiSuggestionText)) {
            area.setFont(textAreaFont);
        }
        for (JTextArea area : List.of(warningsArea, assumptionsArea, infoArea, parsedArea)) {
            updateNotesRows(area);
        }
        cliArea.setFont(cliFont(textAreaFont));
        chart.setFont(labelFont);
        customStagesTable.setRowHeight(customStagesTable.getFontMetrics(customStagesTable.getFont()).getHeight() + 4);
        sizeCustomTable();
        chart.repaint();
        revalidate();
        repaint();
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
        addFormRow("Shape", shapeCombo);
        addFormRow("Target type", targetCombo);
        targetRow = addFormRow("Target value", target);
        formRows.add(messagesPerSampleRow);
        formRows.add(capRow);
        addFormRow("<html>Average response time<br>(ms, default 500)</html>", response);
        addFormRow("<html>Think time per sample<br>(ms, default 0)</html>", think);
        rampRow = addFormRow("<html>Ramp-up<br>(default 10% of hold)</html>", withUnit(ramp, rampUnit));
        holdRow = addFormRow("<html>Hold<br>(default 10 min)</html>", withUnit(hold, holdUnit));
        addShapeFormRow(WorkloadShape.STEP, "Start target", stepStart);
        addShapeFormRow(WorkloadShape.STEP, "End target", stepEnd);
        addShapeFormRow(WorkloadShape.STEP, "Step size", stepSize);
        addShapeFormRow(WorkloadShape.STEP, "Step duration (s)", stepDuration);
        addShapeFormRow(WorkloadShape.STEP, "Ramp per step (s)", stepRamp);
        addShapeFormRow(WorkloadShape.SPIKE, "Baseline", spikeBaseline);
        addShapeFormRow(WorkloadShape.SPIKE, "Peak", spikePeak);
        addShapeFormRow(WorkloadShape.SPIKE, "Warm-up hold (s)", spikeWarmup);
        addShapeFormRow(WorkloadShape.SPIKE, "Spike duration (s)", spikeDuration);
        addShapeFormRow(WorkloadShape.SPIKE, "Recovery hold (s)", spikeRecovery);
        addShapeFormRow(WorkloadShape.STRESS, "Start target", stressStart);
        addShapeFormRow(WorkloadShape.STRESS, "Ceiling", stressCeiling);
        addShapeFormRow(WorkloadShape.STRESS, "Step size", stressStep);
        addShapeFormRow(WorkloadShape.STRESS, "Step duration (s)", stressDuration);
        addShapeFormRow(WorkloadShape.CUSTOM, "Stages", customShapePanel());
        JPanel rampDownOptions = new JPanel(new BorderLayout(UiTokens.SPACE_1, 0));
        rampDownOptions.setOpaque(false);
        rampDownOptions.add(rampDownEnabled, BorderLayout.WEST);
        rampDownOptions.add(rampDown, BorderLayout.CENTER);
        rampDownRow = addFormRow("Ramp down (s)", rampDownOptions);
        arrivalsRow = addFormRow("Arrivals", arrivalModeCombo);
        formRows.add(hostsRow);
        addFormRow("Generator vCPUs", generatorVcpus);
        addFormRow("Generator RAM (GB)", generatorRamGb);
        addFormRow("<html>Measured max threads per<br>generator (optional)</html>", maxThreadsPerGenerator);
        addFormRow("<html>Load generators<br>(default 1)</html>", agents);
        addFormRow("Samples/s per vCPU", samplesPerVcpu);
        addFormRow("Property prefix", prefix);
        targetCombo.setRenderer((list, value, index, selected, focus) ->
                new JLabel(targetName(value)));
        shapeCombo.addActionListener(event -> {
            updateShapeRows();
            rebuildFormRows();
            updatePreview();
        });
        updateCustomTableHeader();
        updateShapeRows();
        rebuildFormRows();
        return form;
    }

    private FormRow addShapeFormRow(WorkloadShape shape, String label, JComponent field) {
        FormRow row = new FormRow(new JLabel(label), field);
        formRows.add(row);
        shapeRows.computeIfAbsent(shape, ignored -> new ArrayList<>()).add(row);
        return row;
    }

    private JPanel customShapePanel() {
        JPanel panel = new JPanel(new BorderLayout(UiTokens.SPACE_1, UiTokens.SPACE_1));
        panel.setOpaque(false);
        customStagesScroll = new JScrollPane(customStagesTable);
        panel.add(customStagesScroll, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiTokens.SPACE_1, 0));
        buttons.setOpaque(false);
        JButton add = new JButton("Add stage");
        JButton remove = new JButton("Remove");
        add.addActionListener(event -> {
            if (customStageModel.getRowCount() < MAX_CUSTOM_ROWS) {
                customStageModel.addRow(new Object[]{"200", "10", "60"});
                updateCustomTableHeight();
                updatePreview();
            }
        });
        remove.addActionListener(event -> {
            int row = customStagesTable.getSelectedRow();
            if (row >= 0) {
                customStageModel.removeRow(row);
                updateCustomTableHeight();
                updatePreview();
            }
        });
        buttons.add(add);
        buttons.add(remove);
        panel.add(buttons, BorderLayout.SOUTH);
        updateCustomTableHeight();
        return panel;
    }

    private void updateCustomTableHeader() {
        WorkloadInputs.TargetType type = (WorkloadInputs.TargetType) targetCombo.getSelectedItem();
        customStageModel.setColumnIdentifiers(new Object[]{customTargetHeader(type), "Ramp (s)", "Hold (s)"});
        sizeCustomTable();
    }

    private void sizeCustomTable() {
        String targetHeader = customStageModel.getColumnName(0);
        customStagesTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        int targetWidth = customStagesTable.getFontMetrics(customStagesTable.getFont())
                .stringWidth(targetHeader) + UiTokens.SPACE_3 * 2;
        customStagesTable.getColumnModel().getColumn(0).setPreferredWidth(Math.max(150, targetWidth));
        customStagesTable.getColumnModel().getColumn(1).setPreferredWidth(90);
        updateCustomTableHeight();
    }

    static String customTargetHeader(WorkloadInputs.TargetType type) {
        String unit = switch (type) {
            case REQUEST_RATE -> "samples/s";
            case MESSAGE_RATE -> "messages/s";
            case ITERATION_RATE -> "iterations/s";
            case USERS -> "users";
        };
        return "Target (" + unit + ")";
    }

    private void updateCustomTableHeight() {
        if (customStagesScroll == null) {
            return;
        }
        int headerHeight = customStagesTable.getTableHeader().getPreferredSize().height;
        int rowHeight = customStagesTable.getRowHeight();
        int height = headerHeight + rowHeight * (customStageModel.getRowCount() + 1);
        customStagesScroll.setPreferredSize(new Dimension(360, height));
        customStagesScroll.revalidate();
        form.revalidate();
    }

    private void updateShapeRows() {
        WorkloadShape shape = (WorkloadShape) shapeCombo.getSelectedItem();
        boolean openModel = shape != null && shape.usesOpenModelThreadGroup();
        refreshAskAiButton();
        targetRowVisibility(openModel);
        for (Map.Entry<WorkloadShape, List<FormRow>> entry : shapeRows.entrySet()) {
            boolean visible = isShapeRowVisible(entry.getKey(), shape);
            for (FormRow row : entry.getValue()) {
                row.label().setVisible(visible);
                row.field().setVisible(visible);
            }
        }
        if (rampDownRow != null) {
            rampDownRow.label().setVisible(openModel);
            rampDownRow.field().setVisible(openModel);
        }
        rampDown.setEnabled(rampDownEnabled.isSelected());
        if (arrivalsRow != null) {
            arrivalsRow.label().setVisible(openModel);
            arrivalsRow.field().setVisible(openModel);
        }
        if (rampRow != null) {
            rampRow.label().setVisible(!openModel);
            rampRow.field().setVisible(!openModel);
            holdRow.label().setVisible(!openModel);
            holdRow.field().setVisible(!openModel);
        }
    }

    static boolean isShapeRowVisible(WorkloadShape rowShape, WorkloadShape selectedShape) {
        return rowShape == selectedShape;
    }

    static boolean askAiEnabledForShape(WorkloadShape shape) {
        return shape == WorkloadShape.LOAD || shape == WorkloadShape.ENDURANCE;
    }

    static boolean askAiButtonEnabled(WorkloadShape shape, boolean aiRunning) {
        return askAiEnabledForShape(shape) && !aiRunning;
    }

    static String askAiTooltipForShape(WorkloadShape shape) {
        return shape != null && shape.usesOpenModelThreadGroup() ? OPEN_MODEL_ASK_AI_TOOLTIP : null;
    }

    private void refreshAskAiButton() {
        WorkloadShape shape = (WorkloadShape) shapeCombo.getSelectedItem();
        askAiButton.setEnabled(askAiButtonEnabled(shape, aiRequestRunning));
        askAiButton.setToolTipText(aiRequestRunning ? ASK_AI_RUNNING_TOOLTIP : askAiTooltipForShape(shape));
    }

    static boolean customRowCountWithinLimit(int rowCount) {
        return rowCount >= 1 && rowCount <= MAX_CUSTOM_ROWS;
    }

    static boolean stopEditingAndRecompute(JTable table, Runnable recompute) {
        if (table.isEditing() && (table.getCellEditor() == null || !table.getCellEditor().stopCellEditing())) {
            return false;
        }
        recompute.run();
        return true;
    }

    private void targetRowVisibility(boolean hidden) {
        if (targetRow != null) {
            targetRow.label().setVisible(!hidden);
            targetRow.field().setVisible(!hidden);
        }
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
        aiRequestId = invalidateAiRequest(aiRequestId, aiWorker);
        aiWorker = null;
        aiRequestRunning = false;
        refreshAskAiButton();
        runButton.setEnabled(true);
        runButton.setText("Run");
        aiPanelVisible = false;
        aiPanel.setVisible(false);
        rebuildFormRows();
    }

    static long invalidateAiRequest(long requestId, SwingWorker<?, ?> worker) {
        long nextRequestId = requestId + 1;
        if (worker != null) {
            worker.cancel(false);
        }
        return nextRequestId;
    }

    static boolean shouldHandleAiResult(long requestId, long currentRequestId,
                                        boolean completed, boolean cancelled) {
        return requestId == currentRequestId && !completed && !cancelled;
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
        aiRequestRunning = true;
        refreshAskAiButton();
        String prompt;
        try {
            prompt = WorkloadAiAssistant.buildPrompt(aiGoal.getText(), currentFormValues(),
                    LoadCommandHandler.planSummary((JMeterTreeNode) groupCombo.getSelectedItem()));
        } catch (RuntimeException e) {
            runButton.setEnabled(true);
            runButton.setText("Run");
            aiRequestRunning = false;
            refreshAskAiButton();
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
                if (!shouldHandleAiResult(requestId, aiRequestId, completed, isCancelled())) {
                    return;
                }
                aiWorker = null;
                aiRequestRunning = false;
                refreshAskAiButton();
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
        refreshAskAiButton();
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

    private FormRow addFormRow(String label, JComponent field) {
        FormRow row = new FormRow(new JLabel(label), field);
        formRows.add(row);
        return row;
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
                    || (row == messagesPerSampleRow && !messagesPerSampleRowVisible)
                    || !row.label().isVisible() || !row.field().isVisible()) {
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
        cliArea.setFont(cliFont(UIManager.getFont("TextArea.font")));
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

    private static Font cliFont(Font textAreaFont) {
        Font base = textAreaFont == null ? new JTextArea().getFont() : textAreaFont;
        return new Font(Font.MONOSPACED, Font.PLAIN, base.getSize());
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
        WorkloadShapeConfig config = spec.shapeConfig();
        shapeCombo.setSelectedItem(config.shape());
        setDouble(stepStart, config.startTarget());
        setDouble(stepEnd, config.endTarget());
        setDouble(stepSize, config.stepTarget());
        setLong(stepDuration, config.stepDurationSeconds());
        setLong(stepRamp, config.rampSeconds());
        setDouble(spikeBaseline, config.baselineTarget());
        setDouble(spikePeak, config.peakTarget());
        setLong(spikeWarmup, config.warmupSeconds());
        setLong(spikeDuration, config.spikeSeconds());
        setLong(spikeRecovery, config.recoverySeconds());
        setDouble(stressStart, config.startTarget());
        setDouble(stressCeiling, config.endTarget());
        setDouble(stressStep, config.stepTarget());
        setLong(stressDuration, config.stepDurationSeconds());
        rampDownEnabled.setSelected(config.rampDownSeconds() != null);
        setLong(rampDown, config.rampDownSeconds());
        arrivalModeCombo.setSelectedItem(config.arrivalMode());
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
                generatorRamGb, samplesPerVcpu, stepStart, stepEnd, stepSize, stepDuration, stepRamp,
                spikeBaseline, spikePeak, spikeWarmup, spikeDuration, spikeRecovery, stressStart,
                stressCeiling, stressStep, stressDuration, rampDown)) {
            field.getDocument().addDocumentListener(listener);
        }
        customStageModel.addTableModelListener(event -> updatePreview());
        rampDownEnabled.addActionListener(event -> {
            rampDown.setEnabled(rampDownEnabled.isSelected());
            updatePreview();
        });
        arrivalModeCombo.addActionListener(event -> updatePreview());
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
            updateCustomTableHeader();
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
        long stepDurationSeconds = parseLong(stepDuration.getText()) == null
                ? 300 : parseLong(stepDuration.getText());
        stepRamp.setPlaceholder("Default: " + Math.min(10, stepDurationSeconds / 5));
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
        String shapeError = shapeInputError();
        if (shapeError == null && shapeCombo.getSelectedItem() == WorkloadShape.CUSTOM) {
            shapeError = customConversionInputError(targetType, messagesPerSample.getText(),
                    response.getText(), think.getText());
        }
        WorkloadShapeConfig shapeConfig = null;
        if (shapeError == null) {
            try {
                shapeConfig = shapeConfigForForm(targetType, samplers);
            } catch (IllegalArgumentException | ArithmeticException e) {
                shapeError = customConversionFailureField(targetType);
            }
        }
        if (shapeError == null) {
            evaluation = WorkloadFormState.evaluate(targetType,
                    target.getText(), rateCap, response.getText(), think.getText(), ramp.getText(),
                    (String) rampUnit.getSelectedItem(), hold.getText(), (String) holdUnit.getSelectedItem(),
                    agents.getText(), samplers, prefix.getText(), preparation.planFileName(), context, shapeConfig);
        } else {
            evaluation = new WorkloadFormState.Evaluation(null, shapeError);
        }
        String error = samplers == 0 ? LoadCommandHandler.NO_REQUESTS : evaluation.error();
        errorLabel.setText(error == null || error.isBlank() ? " " : error);
        applyButton.setEnabled(canApply(samplers, evaluation));
        WorkloadPlan plan = evaluation.plan();
        chart.setPlan(plan);
        if (plan != null) {
            resultValues[0].setText(Integer.toString(plan.threads()));
            resultValues[1].setText(Integer.toString(plan.samplersPerIteration()));
            resultValues[2].setText(samplesPerSecondText(plan));
            resultValues[3].setText(throughputSamplesPerMinute(plan));
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
            setNotesText(infoArea, infoNotes(plan.notes(), preparation.redirectNote()));
        } else {
            for (JLabel value : resultValues) {
                value.setText("-");
            }
            cliArea.setText("");
            setNotesText(warningsArea, "");
            setNotesText(assumptionsArea, "");
            setNotesText(infoArea, infoNotes(List.of(), preparation.redirectNote()));
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

    private String shapeInputError() {
        WorkloadShape shape = (WorkloadShape) shapeCombo.getSelectedItem();
        List<String> errors = new ArrayList<>();
        if (shape.usesOpenModelThreadGroup()) {
            errors.add(rampDownFieldError(rampDownEnabled.isSelected(), rampDown.getText()));
        }
        switch (shape) {
            case STEP -> {
                addShapeFieldError(errors, stepStart.getText(), "Step start target", false);
                addShapeFieldError(errors, stepEnd.getText(), "Step end target", false);
                addShapeFieldError(errors, stepSize.getText(), "Step size", false);
                addShapeFieldError(errors, stepDuration.getText(), "Step duration", true);
                addShapeFieldError(errors, stepRamp.getText(), "Ramp per step", true);
            }
            case SPIKE -> {
                addShapeFieldError(errors, spikeBaseline.getText(), "Spike baseline", false);
                addShapeFieldError(errors, spikePeak.getText(), "Spike peak", false);
                addShapeFieldError(errors, spikeWarmup.getText(), "Warm-up hold", true);
                addShapeFieldError(errors, spikeDuration.getText(), "Spike duration", true);
                addShapeFieldError(errors, spikeRecovery.getText(), "Recovery hold", true);
            }
            case STRESS -> {
                addShapeFieldError(errors, stressStart.getText(), "Stress start target", false);
                addShapeFieldError(errors, stressCeiling.getText(), "Stress ceiling", false);
                addShapeFieldError(errors, stressStep.getText(), "Stress step size", false);
                addShapeFieldError(errors, stressDuration.getText(), "Stress step duration", true);
            }
            case CUSTOM -> {
                if (!customRowCountWithinLimit(customStageModel.getRowCount())) {
                    errors.add("Custom shapes require between 1 and 10 rows.");
                } else {
                    for (int row = 0; row < customStageModel.getRowCount(); row++) {
                        int stage = row + 1;
                        addShapeFieldError(errors, customCellText(row, 0),
                                "Custom stage " + stage + " target", false);
                        addShapeFieldError(errors, customCellText(row, 1),
                                "Custom stage " + stage + " ramp", true);
                        addShapeFieldError(errors, customCellText(row, 2),
                                "Custom stage " + stage + " hold", true);
                    }
                }
            }
            default -> {
            }
        }
        for (String error : errors) {
            if (error != null) {
                return error;
            }
        }
        return null;
    }

    private String customCellText(int row, int column) {
        Object value = customStageModel.getValueAt(row, column);
        return value == null ? "" : value.toString();
    }

    private static void addShapeFieldError(List<String> errors, String value, String label, boolean wholeNumber) {
        errors.add(shapeFieldError(value, label, wholeNumber));
    }

    static String shapeFieldError(String value, String label, boolean wholeNumber) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (wholeNumber) {
            Long parsed = parseLong(value);
            if (parsed == null) {
                return label + " must be a whole number.";
            }
            return parsed < 0 ? label + " cannot be negative." : null;
        }
        Double parsed = parseDouble(value);
        if (parsed == null) {
            return label + " must be a number.";
        }
        return parsed < 0 ? label + " cannot be negative." : null;
    }

    static String rampDownFieldError(boolean enabled, String value) {
        return enabled ? shapeFieldError(value, "Ramp down", true) : null;
    }

    static String customConversionInputError(WorkloadInputs.TargetType targetType, String messagesPerSampleText,
                                             String responseText, String thinkText) {
        if (targetType == WorkloadInputs.TargetType.MESSAGE_RATE && !messagesPerSampleText.isBlank()) {
            Double messagesPerSample = parseDouble(messagesPerSampleText);
            if (messagesPerSample == null) {
                return "Messages per sample must be a number.";
            }
            if (messagesPerSample <= 0) {
                return "Messages per sample must be greater than zero.";
            }
        }
        if (targetType == WorkloadInputs.TargetType.USERS) {
            Long response = optionalLong(responseText, 500L);
            Long thinkTime = optionalLong(thinkText, 0L);
            if (response == null) {
                return "Response time must be a whole number.";
            }
            if (thinkTime == null) {
                return "Think time must be a whole number.";
            }
            if (response + thinkTime <= 0) {
                return "Response time and think time must total more than zero.";
            }
        }
        return null;
    }

    private static Long optionalLong(String text, long fallback) {
        return text == null || text.isBlank() ? fallback : parseLong(text);
    }

    private String customConversionFailureField(WorkloadInputs.TargetType targetType) {
        if (targetType == WorkloadInputs.TargetType.MESSAGE_RATE) {
            return "Messages per sample must be greater than zero.";
        }
        if (targetType == WorkloadInputs.TargetType.USERS) {
            return "Response time and think time must total more than zero.";
        }
        return "Custom stage target could not be converted.";
    }

    static boolean canApply(int samplerCount, WorkloadFormState.Evaluation evaluation) {
        return samplerCount > 0 && evaluation != null && evaluation.valid();
    }

    private WorkloadShapeConfig shapeConfigForForm(WorkloadInputs.TargetType targetType, int samplers) {
        WorkloadShape shape = (WorkloadShape) shapeCombo.getSelectedItem();
        Long configuredRampDown = parseLong(rampDown.getText());
        Long rampDownSeconds = rampDownEnabled.isSelected()
                ? configuredRampDown == null ? 120L : configuredRampDown : null;
        List<WorkloadStage> customStages = new ArrayList<>();
        if (shape == WorkloadShape.CUSTOM) {
            if (!customRowCountWithinLimit(customStageModel.getRowCount())) {
                throw new IllegalArgumentException("Custom shapes require between 1 and 10 rows.");
            }
            long responseMillis = parseLong(response.getText()) == null ? 500 : parseLong(response.getText());
            long thinkMillis = parseLong(think.getText()) == null ? 0 : parseLong(think.getText());
            double messagesPerSampleValue = parseDouble(messagesPerSample.getText()) == null
                    ? 1 : parseDouble(messagesPerSample.getText());
            double previous = 0;
            for (int row = 0; row < customStageModel.getRowCount(); row++) {
                Double targetValue = parseDouble(String.valueOf(customStageModel.getValueAt(row, 0)));
                Long rowRamp = parseLong(String.valueOf(customStageModel.getValueAt(row, 1)));
                Long rowHold = parseLong(String.valueOf(customStageModel.getValueAt(row, 2)));
                if (targetValue == null || rowRamp == null || rowHold == null || rowRamp < 0 || rowHold <= 0) {
                    customStages.clear();
                    break;
                }
                double rate = WorkloadCalculator.toIterationsPerSecond(targetValue, targetType,
                        Math.max(1, samplers), messagesPerSampleValue, responseMillis, thinkMillis);
                if (rowRamp > 0 || Double.compare(previous, rate) != 0) {
                    customStages.add(new WorkloadStage(previous, rate, rowRamp));
                }
                customStages.add(new WorkloadStage(rate, rate, rowHold));
                previous = rate;
            }
        }
        return new WorkloadShapeConfig(shape,
                fieldDouble(shape == WorkloadShape.STRESS ? stressStart : stepStart, 100),
                fieldDouble(shape == WorkloadShape.STRESS ? stressCeiling : stepEnd,
                        shape == WorkloadShape.STRESS ? 1000 : 500),
                fieldDouble(shape == WorkloadShape.STRESS ? stressStep : stepSize, 100),
                fieldLong(shape == WorkloadShape.STRESS ? stressDuration : stepDuration),
                fieldLong(stepRamp), fieldDouble(spikeBaseline, 100), fieldDouble(spikePeak, 500),
                fieldLong(spikeWarmup), fieldLong(spikeDuration), fieldLong(spikeRecovery),
                customStages, rampDownSeconds, (ArrivalMode) arrivalModeCombo.getSelectedItem());
    }

    private static Double fieldDouble(JTextField field, double fallback) {
        Double value = parseDouble(field.getText());
        return value == null ? fallback : value;
    }

    private static Long fieldLong(JTextField field) {
        return parseLong(field.getText());
    }

    private static Double parseDouble(String value) {
        try {
            double parsed = Double.parseDouble(value.trim());
            return Double.isFinite(parsed) ? parsed : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void apply() {
        if (completed) {
            return;
        }
        if (!stopEditingAndRecompute(customStagesTable, this::updatePreview)) {
            errorLabel.setText("Finish editing the Custom stages before applying.");
            return;
        }
        if (evaluation == null || !evaluation.valid()) {
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
        aiRequestId = invalidateAiRequest(aiRequestId, aiWorker);
        aiWorker = null;
        aiRequestRunning = false;
        refreshAskAiButton();
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

    private static void setDouble(JTextField field, Double value) {
        if (value != null) {
            field.setText(Double.toString(value));
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

    static String throughputSamplesPerMinute(WorkloadPlan plan) {
        if (plan.shapeConfig().shape().usesOpenModelThreadGroup()) {
            return "Not used (Open Model arrivals)";
        }
        return plan.rpm() == null ? "No timer" : WorkloadCalculatorFormat.format(plan.rpm());
    }

    static String infoNotes(List<String> planNotes, String redirectNote) {
        List<String> notes = new java.util.ArrayList<>();
        if (redirectNote != null && !redirectNote.isBlank()) {
            notes.add(redirectNote);
        }
        if (planNotes != null) {
            notes.addAll(planNotes);
        }
        return String.join("\n", notes);
    }
}
