package org.qainsights.jmeter.ai.gui;

import java.awt.BorderLayout;
import java.awt.Color;
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
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Point;
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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.IdentityHashMap;
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
import javax.swing.DefaultCellEditor;
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
import javax.swing.Icon;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.DefaultCaret;

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
import org.qainsights.jmeter.ai.load.WorkloadField;
import org.qainsights.jmeter.ai.load.InvalidWorkloadValueException;
import org.qainsights.jmeter.ai.load.WorkloadLimits;
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
    private static final double WIDE_FORM_COLUMN_WEIGHT = 0.44;
    private static final double WIDE_PREVIEW_COLUMN_WEIGHT = 0.56;

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
    private final List<SectionCard> formCards = new ArrayList<>();
    private final Map<SectionCard, List<FormRow>> rowsByCard = new LinkedHashMap<>();
    private final Map<JTextField, FieldShell> fieldShells = new IdentityHashMap<>();
    private final Map<SectionCard, Integer> cardRowColumns = new IdentityHashMap<>();
    private final FormRow capRow;
    private final FormRow messagesPerSampleRow;
    private final FormRow hostsRow;
    private FormRow targetRow;
    private FormRow rampRow;
    private FormRow holdRow;
    private FormRow rampDownRow;
    private FormRow arrivalsRow;
    private final Map<WorkloadShape, List<FormRow>> shapeRows = new LinkedHashMap<>();
    private final FieldComboBox<JMeterTreeNode> groupCombo = new FieldComboBox<>(JMeterTreeNode::getName);
    private final JComboBox<WorkloadShape> shapeCombo = new JComboBox<>(WorkloadShape.values());
    private final FieldComboBox<WorkloadInputs.TargetType> targetCombo =
            new FieldComboBox<>(WorkloadInputs.TargetType.values(), WorkloadDesignerDialog::targetName);
    private final int initialTargetFontSize = targetCombo.getFont().getSize();
    private final PlaceholderTextField target = new PlaceholderTextField();
    private final PlaceholderTextField cap = new PlaceholderTextField();
    private final PlaceholderTextField response = new PlaceholderTextField();
    private final PlaceholderTextField think = new PlaceholderTextField();
    private final PlaceholderTextField ramp = new PlaceholderTextField();
    private final JComboBox<String> rampUnit = new JComboBox<>(new String[]{"s", "min", "h"});
    private final PlaceholderTextField hold = new PlaceholderTextField();
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
    private final FieldComboBox<ArrivalMode> arrivalModeCombo =
            new FieldComboBox<>(ArrivalMode.values(), ArrivalMode::toString);
    private final DefaultTableModel customStageModel = new DefaultTableModel(
            new Object[][]{{"100", "10", "60"}}, new Object[]{"Target (samples/s)", "Ramp (s)", "Hold (s)"}) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return true;
        }
    };
    private final JTable customStagesTable = new JTable(customStageModel);
    private JScrollPane customStagesScroll;
    private FieldShell customStagesShell;
    private final PlaceholderTextField agents = new PlaceholderTextField();
    private final PlaceholderTextField generatorHosts = new PlaceholderTextField();
    private final PlaceholderTextField maxThreadsPerGenerator = new PlaceholderTextField();
    private final PlaceholderTextField generatorVcpus = new PlaceholderTextField();
    private final PlaceholderTextField generatorRamGb = new PlaceholderTextField();
    private final PlaceholderTextField samplesPerVcpu = new PlaceholderTextField();
    private final JTextField messagesPerSample = new JTextField("1");
    private final PlaceholderTextField prefix = new PlaceholderTextField();
    private final LoadShapeChart chart = new LoadShapeChart();
    private final JLabel errorLabel = new JLabel(" ");
    private final JLabel[] resultValues = new JLabel[10];
    private final JLabel[] resultLabels = new JLabel[10];
    private final JTextArea warningsArea = notesArea(2, ThemeColors.warning());
    private final JTextArea assumptionsArea = notesArea(2, ThemeColors.info());
    private final JTextArea infoArea = notesArea(2, ThemeColors.info());
    private final JTextArea parsedArea = notesArea(2, ThemeColors.secondaryText());
    private final Callout warningsCallout = new Callout(warningsArea, Callout.Tone.WARNING);
    private final Callout assumptionsCallout = new Callout(assumptionsArea, Callout.Tone.INFO);
    private final Callout infoCallout = new Callout(infoArea, Callout.Tone.INFO);
    private final Callout parsedCallout = new Callout(parsedArea, Callout.Tone.SECONDARY);
    private JPanel notes;
    private JPanel columns;
    private JPanel preview;
    private WideColumnWrapper formWideColumn;
    private WideColumnWrapper previewWideColumn;
    private JScrollPane bodyScroll;
    private final JTextArea cliArea = new JTextArea(4, 24);
    private final QuietButton applyButton =
            new QuietButton("Apply to Thread Group", QuietButton.Kind.PRIMARY);
    private final QuietButton copyButton =
            new QuietButton("Copy", QuietButton.Kind.OUTLINED).compact();
    private final JComboBox<CommandShell> shellCombo = new JComboBox<>(CommandShell.values());
    private final QuietButton askAiButton = new QuietButton("Ask AI", QuietButton.Kind.TONAL);
    private final QuietButton copySuggestionButton =
            new QuietButton("Copy", QuietButton.Kind.OUTLINED).compact();
    private final JLabel hostsSourceLabel = createHostsSourceLabel();
    private JLabel titleLabel;
    private JLabel captionLabel;
    private JLabel threadLegendLabel;
    private JLabel rateLegendLabel;
    private final JPanel aiPanel = new JPanel(new BorderLayout(UiTokens.SPACE_2, UiTokens.SPACE_1));
    private final SectionCard aiCard = new SectionCard("Ask AI");
    private final SectionCard targetCard = new SectionCard("Target");
    private final SectionCard shapeCard = new SectionCard("Shape settings");
    private final SectionCard timingCard = new SectionCard("Timing", "Blank fields use the defaults shown");
    private final SectionCard generatorsCard = new SectionCard("Load generators");
    private final SectionCard advancedCard = new SectionCard("Advanced");
    private final SectionCard loadShapeCard = new SectionCard("Load shape");
    private final SectionCard planDetailsCard = new SectionCard("Plan details");
    private final SectionCard cliCard = new SectionCard("Command line");
    private final PlaceholderTextArea aiGoal = new PlaceholderTextArea(3, 30);
    private final JLabel aiErrorLabel = new JLabel(" ");
    private final JPanel aiSuggestionPanel = new JPanel();
    private final JTextArea aiSuggestionText = notesArea(3, ThemeColors.info());
    private final Callout aiSuggestionCallout = new Callout(aiSuggestionPanel, Callout.Tone.INFO);
    private final Function<String, String> aiCall;
    private final List<SectionCard> previewCards = new ArrayList<>();
    private final List<Component> formCardGaps = new ArrayList<>();
    private final List<Component> previewCardGaps = new ArrayList<>();
    private final JPanel columnsBottomFiller = new JPanel();
    private final List<FieldShell> fieldShellList = new ArrayList<>();
    private final List<Callout> callouts = new ArrayList<>();
    private final KpiTile[] kpiTiles = new KpiTile[4];
    private JPanel kpiGrid;
    private JPanel detailsGrid;
    private CodeBlockPanel commandCodeBlock;
    private SegmentedControl<WorkloadShape> shapeControl;
    private SegmentedControl<CommandShell> shellControl;
    private SegmentedControl<String> rampUnitControl;
    private SegmentedControl<String> holdUnitControl;
    private JLabel targetSuffixLabel;
    private FieldShell targetValueShell;
    private FieldShell rampDownShell;
    private JButton advancedChevron;
    private boolean advancedExpanded;
    private boolean refreshingFormRows;
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
            Object oldLookAndFeel = event.getOldValue();
            Object newLookAndFeel = event.getNewValue();
            boolean updateComponentTree = oldLookAndFeel == null || newLookAndFeel == null
                    || oldLookAndFeel.getClass() != newLookAndFeel.getClass();
            SwingUtilities.invokeLater(() -> refreshLookAndFeel(updateComponentTree));
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
        target.setPlaceholder("100");
        target.setColumns(6);
        maxThreadsPerGenerator.setToolTipText(
                "From a calibration run on one generator at under 80% CPU");
        response.setPlaceholder("500");
        think.setPlaceholder("0");
        ramp.setPlaceholder("10% of hold");
        hold.setPlaceholder("600");
        agents.setPlaceholder("1");
        generatorVcpus.setPlaceholder("4");
        generatorRamGb.setPlaceholder("8");
        generatorHosts.setPlaceholder("gen1, gen2 (blank runs locally)");
        maxThreadsPerGenerator.setPlaceholder("From a calibration run");
        samplesPerVcpu.setPlaceholder("250");
        stepStart.setPlaceholder("100");
        stepEnd.setPlaceholder("500");
        stepSize.setPlaceholder("100");
        stepDuration.setPlaceholder("300");
        stepRamp.setPlaceholder("10");
        spikeBaseline.setPlaceholder("100");
        spikePeak.setPlaceholder("500");
        spikeWarmup.setPlaceholder("60");
        spikeDuration.setPlaceholder("60");
        spikeRecovery.setPlaceholder("60");
        stressStart.setPlaceholder("100");
        stressCeiling.setPlaceholder("1000");
        stressStep.setPlaceholder("100");
        stressDuration.setPlaceholder("120");
        rampDown.setPlaceholder("120");
        hostsSourceLabel.setForeground(ThemeColors.secondaryText());
        hostsSourceLabel.setFont(UiTokens.caption(hostsSourceLabel.getFont()));
        aiPanel.setOpaque(false);
        aiSuggestionPanel.setOpaque(false);
        capRow = new FormRow(formLabel("Cap at samples/s"),
                createFieldShell(cap, "samples/s"), targetCard, false);
        messagesPerSampleRow = new FormRow(formLabel("Messages per sample"),
                createFieldShell(messagesPerSample, (JComponent) null), targetCard, false);
        hostsRow = new FormRow(formLabel("Generator hosts"),
                createFieldShell(generatorHosts, (JComponent) null), generatorsCard, true);
        installInputFilters();
        buildAiPanel();
        setContentPane(buildContent());
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                cancel();
            }

            @Override
            public void windowOpened(WindowEvent event) {
                SwingUtilities.invokeLater(() -> {
                    bodyScroll.getViewport().setViewPosition(new Point(0, 0));
                    target.requestFocusInWindow();
                });
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
            }
        });
        prefill(preparation.spec());
        installListeners();
        updatePreview();
        UIManager.addPropertyChangeListener(lookAndFeelListener);
    }

    private JPanel buildContent() {
        JPanel root = new JPanel(new BorderLayout(0, UiTokens.SPACE_3));
        root.setBorder(BorderFactory.createEmptyBorder(UiTokens.SPACE_4, UiTokens.SPACE_6,
                UiTokens.SPACE_3, UiTokens.SPACE_6));
        root.setOpaque(true);
        root.setBackground(ThemeColors.canvas());
        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        north.setOpaque(false);
        JPanel header = new JPanel(new BorderLayout(UiTokens.SPACE_3, 0));
        header.setOpaque(false);
        JLabel headerIcon = new JLabel(WorkloadDesignerMenuItem.getLoadIcon(32));
        headerIcon.setPreferredSize(new Dimension(40, 40));
        header.add(headerIcon, BorderLayout.WEST);
        JPanel headerText = new JPanel(new BorderLayout(0, UiTokens.SPACE_1));
        headerText.setOpaque(false);
        titleLabel = new JLabel("Workload Designer");
        titleLabel.setFont(UiTokens.title(titleLabel.getFont()));
        titleLabel.setForeground(ThemeColors.foreground());
        captionLabel = new JLabel("<html>Threads, pacing and generators from a target load. "
                + "Values become ${__P()} properties, so CI can override them.</html>");
        captionLabel.setFont(UiTokens.caption(captionLabel.getFont()));
        captionLabel.setForeground(ThemeColors.secondaryText());
        captionLabel.setToolTipText("Little's Law: threads ≈ samples per second × "
                + "(response time + think time), sized with 1.5× headroom.");
        headerText.add(titleLabel, BorderLayout.NORTH);
        headerText.add(captionLabel, BorderLayout.CENTER);
        header.add(headerText, BorderLayout.CENTER);
        north.add(header);
        north.add(javax.swing.Box.createVerticalStrut(UiTokens.SPACE_3));
        shapeControl = new SegmentedControl<>(shapeCombo, WorkloadShape::toString,
                WorkloadDesignerDialog::shapeIcon);
        north.add(shapeControl);
        root.add(north, BorderLayout.NORTH);
        columns = new ScrollablePanel();
        columns.setLayout(new GridBagLayout());
        columns.setOpaque(false);
        columnsBottomFiller.setOpaque(false);
        buildForm();
        preview = buildPreview();
        formWideColumn = new WideColumnWrapper(form);
        previewWideColumn = new WideColumnWrapper(preview);
        rebuildCardStack(form, formCards, UiTokens.SPACE_3, formCardGaps);
        rebuildCardStack(preview, previewCards, UiTokens.SPACE_3, previewCardGaps);
        columns.add(formWideColumn, wideColumnConstraints(0, UiTokens.SPACE_4));
        columns.add(previewWideColumn, wideColumnConstraints(1, 0));
        columns.add(columnsBottomFiller, wideColumnFillerConstraints());
        bodyScroll = new JScrollPane(columns);
        bodyScroll.setBorder(null);
        bodyScroll.setOpaque(false);
        bodyScroll.setBackground(ThemeColors.canvas());
        bodyScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        bodyScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        bodyScroll.getVerticalScrollBar().setUnitIncrement(16);
        bodyScroll.getViewport().setBackground(ThemeColors.canvas());
        bodyScroll.getViewport().setOpaque(true);
        bodyScroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                updateColumnsLayout();
                updateKpiLayout();
            }
        });
        bodyScroll.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                SwingUtilities.invokeLater(WorkloadDesignerDialog.this::updateColumnsLayout);
            }
        });
        root.add(bodyScroll, BorderLayout.CENTER);
        JPanel footer = new FooterPanel(new BorderLayout());
        footer.setBorder(BorderFactory.createEmptyBorder(UiTokens.SPACE_3, 0, 0, 0));
        footer.setOpaque(false);
        errorLabel.setForeground(ThemeColors.error());
        errorLabel.setVisible(false);
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
        QuietButton cancel = new QuietButton("Cancel", QuietButton.Kind.GHOST);
        cancel.addActionListener(event -> cancel());
        applyButton.addActionListener(event -> apply());
        buttons.add(cancel);
        buttons.add(applyButton);
        footer.add(buttons, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);
        return root;
    }

    private void installInputFilters() {
        for (JTextField field : List.of(target, cap, ramp, hold, stepStart, stepEnd, stepSize,
                spikeBaseline, spikePeak, stressStart, stressCeiling, stressStep)) {
            NumericDocumentFilter.install(field, NumericDocumentFilter.Mode.DECIMAL);
        }
        for (JTextField field : List.of(response, think, stepDuration, stepRamp, spikeWarmup,
                spikeDuration, spikeRecovery, stressDuration, rampDown, agents,
                maxThreadsPerGenerator, generatorVcpus, generatorRamGb, samplesPerVcpu,
                messagesPerSample)) {
            NumericDocumentFilter.install(field, NumericDocumentFilter.Mode.WHOLE);
        }
        TextLengthDocumentFilter.install(prefix, 64);
        TextLengthDocumentFilter.install(aiGoal, 1_000);
        customStagesTable.getColumnModel().getColumn(0)
                .setCellEditor(new DefaultCellEditor(numericEditor(NumericDocumentFilter.Mode.DECIMAL)));
        for (int column = 1; column < 3; column++) {
            customStagesTable.getColumnModel().getColumn(column)
                    .setCellEditor(new DefaultCellEditor(numericEditor(NumericDocumentFilter.Mode.WHOLE)));
        }
    }

    private static JTextField numericEditor(NumericDocumentFilter.Mode mode) {
        JTextField field = new JTextField();
        NumericDocumentFilter.install(field, mode);
        return field;
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
        refreshLookAndFeel(true);
    }

    private void refreshLookAndFeel(boolean updateComponentTree) {
        if (updateComponentTree) {
            SwingUtilities.updateComponentTreeUI(this);
        }
        installZoomBindings();
        for (JTextArea area : List.of(warningsArea, assumptionsArea, infoArea, parsedArea,
                aiSuggestionText, cliArea)) {
            styleReadOnlyArea(area);
        }
        Font labelFont = UIManager.getFont("Label.font");
        if (labelFont == null) {
            labelFont = new JLabel().getFont();
        }
        Font textAreaFont = UIManager.getFont("TextArea.font");
        if (textAreaFont == null) {
            textAreaFont = new JTextArea().getFont();
        }
        titleLabel.setFont(UiTokens.title(labelFont));
        titleLabel.setForeground(ThemeColors.foreground());
        captionLabel.setFont(UiTokens.caption(labelFont));
        hostsSourceLabel.setFont(UiTokens.caption(labelFont));
        hostsSourceLabel.setForeground(ThemeColors.secondaryText(ThemeColors.elevatedSurface()));
        aiGoal.setFont(new JTextField().getFont());
        JTextField inputStyle = new JTextField();
        aiGoal.setForeground(inputStyle.getForeground());
        aiGoal.setBackground(inputStyle.getBackground());
        aiGoal.setCaretColor(inputStyle.getCaretColor());
        aiGoal.setSelectionColor(inputStyle.getSelectionColor());
        aiGoal.setOpaque(inputStyle.isOpaque());
        if (threadLegendLabel != null) {
            threadLegendLabel.setFont(UiTokens.caption(labelFont));
            rateLegendLabel.setFont(UiTokens.caption(labelFont));
            threadLegendLabel.setForeground(ThemeColors.secondaryText());
            rateLegendLabel.setForeground(ThemeColors.secondaryText());
        }
        for (FormRow row : formRows) {
            row.label().setFont(UiTokens.label(labelFont));
            row.label().setForeground(ThemeColors.secondaryText(ThemeColors.elevatedSurface()));
        }
        for (JLabel label : resultLabels) {
            if (label != null) {
                label.setFont(UiTokens.body(labelFont));
                label.setForeground(ThemeColors.secondaryText(ThemeColors.elevatedSurface()));
            }
        }
        for (SectionCard card : formCards) {
            card.refreshFonts();
        }
        aiCard.refreshFonts();
        for (SectionCard card : previewCards) {
            card.refreshFonts();
        }
        for (FieldShell shell : fieldShellList) {
            shell.refreshTheme();
        }
        for (Callout callout : callouts) {
            callout.refreshTheme();
        }
        if (shapeControl != null) {
            shapeControl.refreshTheme();
        }
        if (rampUnitControl != null) {
            rampUnitControl.refreshTheme();
            holdUnitControl.refreshTheme();
        }
        if (shellControl != null) {
            shellControl.refreshTheme();
        }
        if (targetSuffixLabel != null) {
            targetSuffixLabel.setFont(UiTokens.label(labelFont));
            targetSuffixLabel.setForeground(ThemeColors.secondaryText(ThemeColors.elevatedSurface()));
        }
        for (KpiTile tile : kpiTiles) {
            if (tile != null) {
                tile.refreshFonts();
            }
        }
        for (JTextArea area : List.of(warningsArea, assumptionsArea, infoArea, parsedArea, aiSuggestionText)) {
            area.setFont(textAreaFont);
        }
        for (JTextArea area : List.of(warningsArea, assumptionsArea, infoArea, parsedArea)) {
            updateNotesRows(area);
        }
        cliArea.setFont(cliFont(textAreaFont));
        if (commandCodeBlock != null) {
            commandCodeBlock.updateTextInsets();
            updateCommandRows(cliArea);
        }
        cliArea.setForeground(ThemeColors.foreground());
        if (resultValues[9] != null) {
            resultValues[9].setFont(cliFont(textAreaFont));
        }
        chart.setFont(labelFont);
        customStagesTable.setRowHeight(customStagesTable.getFontMetrics(customStagesTable.getFont()).getHeight() + 10);
        customStagesTable.getTableHeader().setFont(UiTokens.label(labelFont));
        customStagesTable.setBackground(ThemeColors.elevatedSurface());
        customStagesTable.setForeground(ThemeColors.foreground());
        customStagesTable.getTableHeader().setBackground(ThemeColors.elevatedSurface());
        customStagesTable.getTableHeader().setForeground(ThemeColors.secondaryText());
        sizeCustomTable();
        updateCustomTableHeight();
        refreshStatusTheme();
        rebuildFormRows();
        updateColumnsLayout();
        chart.repaint();
        revalidate();
        repaint();
    }

    private void refreshStatusTheme() {
        if (errorLabel.getIcon() instanceof StatusDotIcon statusIcon) {
            errorLabel.setForeground(statusIcon.ready ? ThemeColors.success() : ThemeColors.error());
        }
        aiErrorLabel.setForeground(ThemeColors.error());
    }

    private void updateColumnsLayout() {
        if (columns == null || preview == null) {
            return;
        }
        boolean stack = getWidth() < 900;
        int expectedComponents = 3;
        boolean layoutMismatch = stack
                ? !(columns.getLayout() instanceof BoxLayout)
                : !(columns.getLayout() instanceof GridBagLayout);
        if (stackedColumns != stack || columns.getComponentCount() != expectedComponents || layoutMismatch) {
            stackedColumns = stack;
            columns.setLayout(stack
                    ? new BoxLayout(columns, BoxLayout.Y_AXIS)
                    : new GridBagLayout());
            columns.removeAll();
            if (stack) {
                rebuildCardStack(form, formCards, UiTokens.SPACE_3, formCardGaps);
                rebuildCardStack(preview, previewCards, UiTokens.SPACE_3, previewCardGaps);
                addStackedColumns(columns, form, preview);
            } else {
                rebuildCardStack(form, formCards, UiTokens.SPACE_3, formCardGaps);
                rebuildCardStack(preview, previewCards, UiTokens.SPACE_3, previewCardGaps);
                formWideColumn.setContent(form);
                previewWideColumn.setContent(preview);
                columns.add(formWideColumn, wideColumnConstraints(0, UiTokens.SPACE_4));
                columns.add(previewWideColumn, wideColumnConstraints(1, 0));
                columns.add(columnsBottomFiller, wideColumnFillerConstraints());
            }
            columns.revalidate();
            columns.repaint();
        }
    }

    static GridBagConstraints wideColumnConstraints(int column, int rightInset) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = column;
        constraints.gridy = 0;
        constraints.weightx = column == 0 ? WIDE_FORM_COLUMN_WEIGHT : WIDE_PREVIEW_COLUMN_WEIGHT;
        constraints.weighty = 0;
        constraints.anchor = GridBagConstraints.NORTHWEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(0, 0, 0, rightInset);
        return constraints;
    }

    static GridBagConstraints wideColumnFillerConstraints() {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 1;
        constraints.gridwidth = 2;
        constraints.weightx = 0;
        constraints.weighty = 1;
        constraints.fill = GridBagConstraints.BOTH;
        return constraints;
    }

    static void addStackedColumns(JPanel columns, JPanel form, JPanel preview) {
        form.setAlignmentX(Component.LEFT_ALIGNMENT);
        preview.setAlignmentX(Component.LEFT_ALIGNMENT);
        columns.add(form);
        columns.add(javax.swing.Box.createVerticalStrut(UiTokens.SPACE_4));
        columns.add(preview);
    }

    static void rebuildCardStack(JPanel column, List<SectionCard> cards, int gap,
                                 List<Component> gaps) {
        column.removeAll();
        gaps.clear();
        SectionCard previous = null;
        for (SectionCard card : cards) {
            if (previous != null && gap > 0) {
                Component spacer = javax.swing.Box.createVerticalStrut(gap);
                spacer.setVisible(previous.isVisible() && card.isVisible());
                gaps.add(spacer);
                column.add(spacer);
            }
            card.setAlignmentX(Component.LEFT_ALIGNMENT);
            column.add(card);
            previous = card;
        }
        updateCardGaps(cards, gaps);
    }

    private static void updateCardGaps(List<SectionCard> cards, List<Component> gaps) {
        if (gaps.size() != Math.max(0, cards.size() - 1)) {
            return;
        }
        SectionCard previousVisible = null;
        for (int index = 0; index < gaps.size(); index++) {
            SectionCard previous = cards.get(index);
            SectionCard current = cards.get(index + 1);
            if (previous.isVisible()) {
                previousVisible = previous;
            }
            gaps.get(index).setVisible(previousVisible != null && current.isVisible());
            if (current.isVisible()) {
                previousVisible = current;
            }
        }
    }

    private JPanel buildForm() {
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setOpaque(false);
        aiCard.body().add(aiPanel, BorderLayout.CENTER);
        aiCard.setVisible(false);
        registerFormCard(aiCard);

        registerFormCard(targetCard);
        for (JMeterTreeNode node : preparation.threadGroups()) {
            groupCombo.addItem(node);
        }
        groupCombo.setSelectedItem(preparation.selectedGroup());
        targetSuffixLabel = formLabel("samples/s");
        addFormRow(targetCard, "Thread Group", comboFieldShell(groupCombo), true);
        addFormRow(targetCard, "Target type", comboFieldShell(targetCombo));
        targetRow = addFormRow(targetCard, "Target value",
                targetValueShell = createFieldShell(target, targetSuffixLabel));
        rowsByCard.get(targetCard).add(capRow);
        rowsByCard.get(targetCard).add(messagesPerSampleRow);
        formRows.add(capRow);
        formRows.add(messagesPerSampleRow);

        registerFormCard(shapeCard);
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
        rampDownShell = createFieldShell(rampDown, (JComponent) null);
        rampDownOptions.add(rampDownShell, BorderLayout.CENTER);
        rampDownRow = addFormRow(shapeCard, "Ramp down (s)", rampDownOptions);
        arrivalsRow = addFormRow(shapeCard, "Arrivals", comboFieldShell(arrivalModeCombo));

        registerFormCard(timingCard);
        addFormRow(timingCard, "Average response time", fieldShell(response, "ms"));
        addFormRow(timingCard, "Think time per sample", fieldShell(think, "ms"));
        rampUnitControl = new SegmentedControl<>(rampUnit, Function.identity(),
                FlowLayout.RIGHT, UiTokens.SPACE_2, 2, UiTokens.CONTROL_HEIGHT - UiTokens.SPACE_2);
        holdUnitControl = new SegmentedControl<>(holdUnit, Function.identity(),
                FlowLayout.RIGHT, UiTokens.SPACE_2, 2, UiTokens.CONTROL_HEIGHT - UiTokens.SPACE_2);
        rampRow = addFormRow(timingCard, "Ramp-up", fieldShell(ramp, rampUnitControl));
        holdRow = addFormRow(timingCard, "Hold", fieldShell(hold, holdUnitControl));

        generatorsCard.setHeaderTrailing(hostsSourceLabel);
        registerFormCard(generatorsCard);
        rowsByCard.get(generatorsCard).add(hostsRow);
        formRows.add(hostsRow);
        addFormRow(generatorsCard, "vCPUs per generator", fieldShell(generatorVcpus));
        addFormRow(generatorsCard, "RAM per generator", fieldShell(generatorRamGb, "GB"));
        addFormRow(generatorsCard, "Measured max threads (optional)",
                fieldShell(maxThreadsPerGenerator), true);
        addFormRow(generatorsCard, "Generators", fieldShell(agents));

        registerFormCard(advancedCard);
        addFormRow(advancedCard, "Samples/s per vCPU", fieldShell(samplesPerVcpu, "samples/s"));
        addFormRow(advancedCard, "Property prefix", fieldShell(prefix));
        advancedChevron = new JButton();
        advancedChevron.setIcon(ChevronIcons.down(14));
        advancedChevron.setFocusable(false);
        advancedChevron.setContentAreaFilled(false);
        advancedChevron.setBorder(BorderFactory.createEmptyBorder(0, UiTokens.SPACE_1, 0, 0));
        advancedChevron.setToolTipText("Show advanced settings");
        advancedChevron.addActionListener(event -> toggleAdvanced());
        advancedCard.setHeaderTrailing(advancedChevron);
        advancedCard.titleLabel().setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        advancedCard.titleLabel().addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                toggleAdvanced();
            }
        });
        advancedCard.body().setVisible(false);
        shapeCombo.addActionListener(event -> {
            updateShapeRows();
            rebuildFormRows();
            updatePreview();
        });
        updateCustomTableHeader();
        updateShapeRows();
        updateTargetSuffix();
        rebuildFormRows();
        return form;
    }

    private void registerFormCard(SectionCard card) {
        formCards.add(card);
        rowsByCard.put(card, new ArrayList<>());
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.body().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                int columns = formColumns(card, card.body().getWidth());
                if (!Integer.valueOf(columns).equals(cardRowColumns.get(card))) {
                    rebuildFormRows();
                }
            }
        });
    }

    private FormRow addFormRow(SectionCard card, String label, JComponent field) {
        return addFormRow(card, label, field, false);
    }

    private FormRow addFormRow(SectionCard card, String label, JComponent field, boolean fullWidth) {
        JComponent value = field instanceof JTextField textField
                ? createFieldShell(textField, (JComponent) null) : field;
        FormRow row = new FormRow(formLabel(label), value, card, fullWidth);
        rowsByCard.get(card).add(row);
        formRows.add(row);
        return row;
    }

    private FormRow addShapeFormRow(WorkloadShape shape, String label, JComponent field) {
        FormRow row = addFormRow(shapeCard, label, field,
                field instanceof JPanel && shape == WorkloadShape.CUSTOM);
        shapeRows.computeIfAbsent(shape, ignored -> new ArrayList<>()).add(row);
        return row;
    }

    private JLabel formLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(UiTokens.label(UIManager.getFont("Label.font")));
        label.setForeground(ThemeColors.secondaryText(ThemeColors.elevatedSurface()));
        return label;
    }

    private JLabel detailLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(UiTokens.body(UIManager.getFont("Label.font")));
        label.setForeground(ThemeColors.secondaryText(ThemeColors.elevatedSurface()));
        return label;
    }

    private FieldShell createFieldShell(JTextField field, String suffix) {
        return createFieldShell(field, suffix == null ? null : suffixLabel(suffix));
    }

    private FieldShell createFieldShell(JTextField field, JComponent trailing) {
        FieldShell shell = new FieldShell(field, trailing);
        fieldShells.put(field, shell);
        fieldShellList.add(shell);
        return shell;
    }

    private FieldShell comboFieldShell(JComboBox<?> combo) {
        FieldShell shell = new FieldShell(combo);
        fieldShellList.add(shell);
        return shell;
    }

    private FieldShell fieldShell(JTextField field) {
        return createFieldShell(field, (JComponent) null);
    }

    private FieldShell fieldShell(JTextField field, String suffix) {
        return createFieldShell(field, suffix);
    }

    private FieldShell fieldShell(JTextField field, SegmentedControl<String> units) {
        FieldShell shell = createFieldShell(field, (JComponent) units);
        return shell;
    }

    private JLabel suffixLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(UiTokens.caption(UIManager.getFont("Label.font")));
        label.setForeground(ThemeColors.secondaryText());
        return label;
    }

    private JPanel customShapePanel() {
        JPanel panel = new JPanel(new BorderLayout(UiTokens.SPACE_1, UiTokens.SPACE_1));
        panel.setOpaque(false);
        customStagesTable.setShowGrid(false);
        customStagesTable.setIntercellSpacing(new Dimension(0, 0));
        customStagesTable.setRowHeight(customStagesTable.getFontMetrics(customStagesTable.getFont()).getHeight() + 10);
        customStagesTable.setFillsViewportHeight(true);
        customStagesTable.setBackground(ThemeColors.elevatedSurface());
        customStagesTable.setForeground(ThemeColors.foreground());
        customStagesTable.getTableHeader().setFont(UiTokens.label(UIManager.getFont("Label.font")));
        customStagesTable.getTableHeader().setBackground(ThemeColors.elevatedSurface());
        customStagesTable.getTableHeader().setForeground(ThemeColors.secondaryText());
        customStagesScroll = new PreferredHeightScrollPane(customStagesTable);
        customStagesScroll.setBorder(null);
        customStagesScroll.setOpaque(false);
        customStagesScroll.getViewport().setOpaque(false);
        customStagesScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        customStagesShell = new FieldShell(customStagesScroll, (JComponent) null, 180);
        fieldShellList.add(customStagesShell);
        panel.add(customStagesShell, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiTokens.SPACE_1, 0));
        buttons.setOpaque(false);
        QuietButton add = new QuietButton("Add stage", QuietButton.Kind.OUTLINED).compact();
        QuietButton remove = new QuietButton("Remove", QuietButton.Kind.OUTLINED).compact();
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
        customStagesScroll.setPreferredSize(new Dimension(customStagesScroll.getMinimumSize().width, height));
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
        if (rampDownShell != null) {
            rampDownShell.setEnabled(rampDownEnabled.isSelected());
        }
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
        shapeCard.setVisible(openModel);
        shapeCard.setTitle(shape == null ? "Shape settings" : shape + " settings");
        if (advancedChevron != null) {
            advancedChevron.setIcon(advancedExpanded ? ChevronIcons.up(14) : ChevronIcons.down(14));
            advancedChevron.setToolTipText(advancedExpanded ? "Hide advanced settings" : "Show advanced settings");
        }
    }

    private void toggleAdvanced() {
        advancedExpanded = !advancedExpanded;
        rebuildFormRows();
    }

    private void updateTargetSuffix() {
        if (targetSuffixLabel == null) {
            return;
        }
        WorkloadInputs.TargetType type = (WorkloadInputs.TargetType) targetCombo.getSelectedItem();
        String suffix = switch (type) {
            case REQUEST_RATE -> "samples/s";
            case ITERATION_RATE -> "iterations/s";
            case MESSAGE_RATE -> "messages/s";
            case USERS -> "users";
        };
        targetSuffixLabel.setText(suffix);
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
        aiGoal.setBorder(BorderFactory.createEmptyBorder(UiTokens.SPACE_2, UiTokens.SPACE_3,
                UiTokens.SPACE_2, UiTokens.SPACE_3));
        aiGoal.setOpaque(inputStyle.isOpaque());
        aiGoal.setPlaceholder(
                "e.g. Black Friday peak, 2x last year's 120k orders/hour, checkout in 1 of 10 visits");
        styleReadOnlyArea(aiSuggestionText);
        JPanel content = new JPanel(new BorderLayout(UiTokens.SPACE_1, 0));
        content.setOpaque(false);
        JLabel goalLabel = formLabel("Describe the goal");
        content.add(goalLabel, BorderLayout.NORTH);
        JScrollPane goalScroll = new JScrollPane(aiGoal);
        goalScroll.setBorder(null);
        goalScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        goalScroll.setOpaque(false);
        goalScroll.getViewport().setOpaque(false);
        FieldShell goalShell = new FieldShell(goalScroll, (JComponent) null, 88);
        content.add(goalShell, BorderLayout.CENTER);
        fieldShellList.add(goalShell);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiTokens.SPACE_1, 0));
        actions.setOpaque(false);
        QuietButton run = new QuietButton("Run", QuietButton.Kind.PRIMARY);
        QuietButton close = new QuietButton("Close", QuietButton.Kind.GHOST);
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
        suggestionScroll.setOpaque(false);
        suggestionScroll.getViewport().setOpaque(false);
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
        target.setText(NumericDocumentFilter.format(
                suggestion.targetValue(), NumericDocumentFilter.Mode.DECIMAL));
        if (suggestion.rateCap() != null) {
            cap.setText(NumericDocumentFilter.format(
                    suggestion.rateCap(), NumericDocumentFilter.Mode.DECIMAL));
        }
        setSuggestedLong(response, suggestion.responseMs());
        setSuggestedLong(think, suggestion.thinkMs());
        setSuggestedDuration(ramp, rampUnit, suggestion.rampSeconds());
        setSuggestedDuration(hold, holdUnit, suggestion.holdSeconds());
        if (suggestion.samplesPerVcpu() != null) {
            samplesPerVcpu.setText(NumericDocumentFilter.format(
                    suggestion.samplesPerVcpu(), NumericDocumentFilter.Mode.WHOLE));
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
            field.setText(NumericDocumentFilter.format(
                    Math.round(value), NumericDocumentFilter.Mode.WHOLE));
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

    private void rebuildFormRows() {
        if (refreshingFormRows) {
            return;
        }
        refreshingFormRows = true;
        try {
            aiCard.setVisible(aiPanelVisible);
            for (SectionCard card : formCards) {
                clearFormCardBody(card, aiCard);
                if (card == aiCard) {
                    continue;
                }
                JPanel body = card.body();
                int columns = formColumns(card, body.getWidth());
                cardRowColumns.put(card, columns);
                populateFormCardBody(card, rowsByCard.get(card), columns, advancedCard,
                        advancedExpanded, capRow, capRowVisible,
                        messagesPerSampleRow, messagesPerSampleRowVisible);
                card.body().setVisible(card != advancedCard || advancedExpanded);
                body.revalidate();
                body.repaint();
            }
            updateCardGaps(formCards, formCardGaps);
            form.revalidate();
            form.repaint();
            if (columns != null) {
                columns.revalidate();
            }
        } finally {
            refreshingFormRows = false;
        }
    }

    static void clearFormCardBody(SectionCard card, SectionCard aiCard) {
        if (card != aiCard) {
            card.body().removeAll();
        }
    }

    static void populateFormCardBody(SectionCard card, List<FormRow> rows, int columns,
                                     SectionCard advancedCard, boolean advancedExpanded,
                                     FormRow capRow, boolean capRowVisible,
                                     FormRow messagesPerSampleRow, boolean messagesPerSampleRowVisible) {
        JPanel body = card.body();
        body.setLayout(new GridBagLayout());
        int rowIndex = 0;
        int columnIndex = 0;
        for (FormRow row : rows) {
            if ((row == capRow && !capRowVisible)
                    || (row == messagesPerSampleRow && !messagesPerSampleRowVisible)
                    || card == advancedCard && !advancedExpanded
                    || !row.label().isVisible() || !row.field().isVisible()) {
                continue;
            }
            JPanel cell = new JPanel(new BorderLayout(0, UiTokens.SPACE_1));
            cell.setOpaque(false);
            cell.add(row.label(), BorderLayout.NORTH);
            cell.add(row.field(), BorderLayout.CENTER);
            GridBagConstraints constraints = new GridBagConstraints();
            constraints.gridx = columnIndex;
            constraints.gridy = rowIndex;
            constraints.gridwidth = row.fullWidth() || columns == 1 ? columns : 1;
            constraints.weightx = 1;
            constraints.anchor = GridBagConstraints.NORTHWEST;
            constraints.fill = GridBagConstraints.HORIZONTAL;
            constraints.insets = new Insets(0, 0, UiTokens.SPACE_3,
                    columns > 1 && constraints.gridwidth == 1 && columnIndex == 0
                            ? UiTokens.SPACE_3 : 0);
            body.add(cell, constraints);
            if (row.fullWidth() || columns == 1 || columnIndex + 1 >= columns) {
                rowIndex++;
                columnIndex = 0;
            } else {
                columnIndex++;
            }
        }
    }

    static int fieldsPerRow(int cardContentWidth) {
        return cardContentWidth < 420 ? 1 : 2;
    }

    static int targetFieldsPerRow(int cardContentWidth, int targetTypeWidth, int targetValueWidth,
                                  boolean fontIncreased) {
        int columns = fieldsPerRow(cardContentWidth);
        return columns == 2 && (fontIncreased
                || cardContentWidth < targetTypeWidth + targetValueWidth + UiTokens.SPACE_3) ? 1 : columns;
    }

    private int formColumns(SectionCard card, int cardContentWidth) {
        int columns = fieldsPerRow(cardContentWidth);
        if (card != targetCard || columns == 1 || targetValueShell == null) {
            return columns;
        }
        int targetTypeWidth = targetCombo.getPreferredSize().width + UiTokens.SPACE_2 * 2;
        boolean fontIncreased = targetCombo.getFont().getSize() > initialTargetFontSize;
        return targetFieldsPerRow(cardContentWidth, targetTypeWidth, targetValueShell.getPreferredSize().width,
                fontIncreased);
    }

    static int kpiColumns(int cardWidth) {
        return cardWidth < 600 ? 2 : 4;
    }

    static String wrapKpiTitle(String text, java.awt.FontMetrics metrics, int width) {
        if (width <= 0 || metrics.stringWidth(text) <= width) {
            return text;
        }
        StringBuilder wrapped = new StringBuilder("<html>");
        int lineWidth = 0;
        int spaceWidth = metrics.charWidth(' ');
        for (String word : text.split(" ")) {
            int wordWidth = metrics.stringWidth(word);
            if (lineWidth > 0 && lineWidth + spaceWidth + wordWidth > width) {
                wrapped.append("<br>");
                lineWidth = 0;
            } else if (lineWidth > 0) {
                wrapped.append(' ');
                lineWidth += spaceWidth;
            }
            wrapped.append(word.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"));
            lineWidth += wordWidth;
        }
        return wrapped.append("</html>").toString();
    }

    private void updateKpiLayout() {
        if (kpiGrid == null) {
            return;
        }
        int cardWidth = loadShapeCard == null ? kpiGrid.getWidth() : loadShapeCard.getWidth();
        int columns = kpiColumns(cardWidth);
        if (kpiGrid.getLayout() instanceof GridLayout layout && layout.getColumns() != columns) {
            kpiGrid.setLayout(new GridLayout(0, columns, UiTokens.SPACE_2, UiTokens.SPACE_2));
            kpiGrid.revalidate();
        }
    }

    private JPanel buildPreview() {
        JPanel previewColumn = new HeightPreservingPanel();
        previewColumn.setLayout(new BoxLayout(previewColumn, BoxLayout.Y_AXIS));
        previewColumn.setOpaque(false);
        previewColumn.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel legend = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiTokens.SPACE_2, 0));
        legend.setOpaque(false);
        threadLegendLabel = new JLabel("Threads", new LegendIcon(false), JLabel.LEADING);
        rateLegendLabel = new JLabel("Samples/s", new LegendIcon(true), JLabel.LEADING);
        for (JLabel label : List.of(threadLegendLabel, rateLegendLabel)) {
            label.setFont(UiTokens.caption(UIManager.getFont("Label.font")));
            label.setForeground(ThemeColors.secondaryText());
        }
        legend.add(threadLegendLabel);
        legend.add(rateLegendLabel);
        loadShapeCard.setHeaderTrailing(legend);
        loadShapeCard.body().setLayout(new BorderLayout(0, UiTokens.SPACE_2));
        chart.setOpaque(false);
        chart.setPreferredSize(new Dimension(420, 210));
        loadShapeCard.body().add(chart, BorderLayout.CENTER);
        kpiGrid = new JPanel(new GridLayout(1, 4, UiTokens.SPACE_2, UiTokens.SPACE_2));
        kpiGrid.setOpaque(false);
        int[] kpiResultIndexes = {0, 2, 4, 7};
        String[] kpiNames = {"Threads", "Samples / second", "Total duration", "Generators needed"};
        for (int index = 0; index < kpiTiles.length; index++) {
            kpiTiles[index] = new KpiTile(kpiNames[index]);
            kpiGrid.add(kpiTiles[index]);
        }
        kpiGrid.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                updateKpiLayout();
            }
        });
        loadShapeCard.body().add(kpiGrid, BorderLayout.SOUTH);
        previewCards.add(loadShapeCard);

        JPanel detailsBody = new JPanel(new BorderLayout(0, UiTokens.SPACE_2));
        detailsBody.setOpaque(false);
        detailsGrid = new JPanel(new GridBagLayout());
        detailsGrid.setOpaque(false);
        String[] labels = {"Threads", "Samples / iteration", "Samples / second",
                "Throughput samples / min", "Total duration", "Per agent",
                "Sampler mix", "Generators needed", "Capacity per generator", "Heap per generator"};
        for (int index = 0; index < labels.length; index++) {
            resultLabels[index] = detailLabel(labels[index]);
            resultValues[index] = new JLabel("-");
        }
        int[] detailIndexes = {1, 3, 5, 6, 8, 9};
        for (int row = 0; row < detailIndexes.length; row++) {
            int index = detailIndexes[row];
            GridBagConstraints labelConstraints = new GridBagConstraints();
            labelConstraints.gridx = 0;
            labelConstraints.gridy = row;
            labelConstraints.anchor = GridBagConstraints.NORTHWEST;
            labelConstraints.insets = new Insets(0, 0, UiTokens.SPACE_1, UiTokens.SPACE_3);
            detailsGrid.add(resultLabels[index], labelConstraints);
            GridBagConstraints valueConstraints = new GridBagConstraints();
            valueConstraints.gridx = 1;
            valueConstraints.gridy = row;
            valueConstraints.weightx = 1;
            valueConstraints.anchor = GridBagConstraints.NORTHWEST;
            valueConstraints.fill = GridBagConstraints.HORIZONTAL;
            valueConstraints.insets = new Insets(0, 0, UiTokens.SPACE_1, 0);
            if (index == 9) {
                resultValues[index].setFont(cliFont(UIManager.getFont("TextArea.font")));
            }
            detailsGrid.add(resultValues[index], valueConstraints);
        }
        detailsBody.add(detailsGrid, BorderLayout.NORTH);
        notes = new JPanel();
        notes.setLayout(new BoxLayout(notes, BoxLayout.Y_AXIS));
        notes.setOpaque(false);
        for (Callout callout : List.of(warningsCallout, assumptionsCallout, infoCallout, parsedCallout,
                aiSuggestionCallout)) {
            callout.setAlignmentX(Component.LEFT_ALIGNMENT);
            callouts.add(callout);
        }
        detailsBody.add(notes, BorderLayout.CENTER);
        planDetailsCard.body().add(detailsBody, BorderLayout.CENTER);
        previewCards.add(planDetailsCard);

        cliArea.setEditable(false);
        cliArea.setLineWrap(true);
        cliArea.setWrapStyleWord(true);
        cliArea.setFont(cliFont(UIManager.getFont("TextArea.font")));
        styleReadOnlyArea(cliArea);
        cliArea.setForeground(ThemeColors.foreground());
        cliArea.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                updateCommandRows(cliArea);
            }
        });
        shellControl = new SegmentedControl<>(shellCombo, CommandShell::displayName);
        JPanel cliPanel = new JPanel(new BorderLayout(0, UiTokens.SPACE_2));
        cliPanel.setOpaque(false);
        cliPanel.add(shellControl, BorderLayout.NORTH);
        commandCodeBlock = new CodeBlockPanel(cliArea, copyButton);
        cliPanel.add(commandCodeBlock, BorderLayout.CENTER);
        copyButton.addActionListener(event -> copyCommand());
        cliCard.body().add(cliPanel, BorderLayout.CENTER);
        previewCards.add(cliCard);
        for (SectionCard card : previewCards) {
            card.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        return previewColumn;
    }

    private static JTextArea notesArea(int rows, java.awt.Color color) {
        JTextArea area = new JTextArea(rows, 20);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(UIManager.getFont("TextArea.font"));
        area.setForeground(color);
        styleReadOnlyArea(area);
        area.setAlignmentX(Component.LEFT_ALIGNMENT);
        area.setAlignmentY(Component.TOP_ALIGNMENT);
        area.setMaximumSize(new Dimension(Integer.MAX_VALUE, area.getPreferredSize().height));
        return area;
    }

    static void styleReadOnlyArea(JTextArea area) {
        ((DefaultCaret) area.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        area.setOpaque(false);
        area.setBackground(new java.awt.Color(0, 0, 0, 0));
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

    static void updateCommandRows(JTextArea cliArea) {
        int availableWidth = cliArea.getWidth() - cliArea.getInsets().left - cliArea.getInsets().right;
        int charactersPerLine = 78;
        if (availableWidth > 0) {
            int characterWidth = Math.max(1, cliArea.getFontMetrics(cliArea.getFont()).charWidth('m'));
            charactersPerLine = Math.max(18, availableWidth / characterWidth);
        }
        int rows = 0;
        for (String line : cliArea.getText().split("\n", -1)) {
            rows += Math.max(1, (line.length() + charactersPerLine - 1) / charactersPerLine);
        }
        int desiredRows = Math.max(3, rows);
        if (cliArea.getRows() != desiredRows) {
            cliArea.setRows(desiredRows);
            cliArea.setMaximumSize(new Dimension(Integer.MAX_VALUE, cliArea.getPreferredSize().height));
        }
    }

    private void prefill(WorkloadSpec spec) {
        WorkloadInputs.TargetType type = WorkloadInputs.TargetType.REQUEST_RATE;
        Double amount = null;
        if (spec.users() != null) {
            type = WorkloadInputs.TargetType.USERS;
            amount = spec.users().doubleValue();
            if (spec.requestsPerSecond() != null) {
                cap.setText(NumericDocumentFilter.format(
                        spec.requestsPerSecond(), NumericDocumentFilter.Mode.DECIMAL));
            }
        } else if (spec.requestsPerSecond() != null) {
            amount = spec.requestsPerSecond();
        } else if (spec.iterationsPerSecond() != null) {
            type = WorkloadInputs.TargetType.ITERATION_RATE;
            amount = spec.iterationsPerSecond();
        } else if (spec.messagesPerSecond() != null) {
            type = WorkloadInputs.TargetType.MESSAGE_RATE;
            amount = spec.messagesPerSecond();
        }
        targetCombo.setSelectedItem(type);
        setDouble(target, amount);
        if (spec.generatorVcpus() != null) {
            generatorVcpus.setText(NumericDocumentFilter.format(
                    spec.generatorVcpus().longValue(), NumericDocumentFilter.Mode.WHOLE));
        }
        if (spec.generatorRamGb() != null) {
            generatorRamGb.setText(NumericDocumentFilter.format(
                    spec.generatorRamGb(), NumericDocumentFilter.Mode.WHOLE));
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
            agents.setText(NumericDocumentFilter.format(
                    spec.agents().longValue(), NumericDocumentFilter.Mode.WHOLE));
        }
        prefix.setText(LoadCommandHandler.prefixFor(preparation.selectedGroup(), preparation.eligibleCount()));
        String defaultPrefix = LoadCommandHandler.prefixFor(
                preparation.selectedGroup(), preparation.eligibleCount());
        advancedExpanded = !samplesPerVcpu.getText().isBlank()
                || !prefix.getText().equals(defaultPrefix);
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
            if (rampDownShell != null) {
                rampDownShell.setEnabled(rampDownEnabled.isSelected());
            }
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
                    generatorsCard.revalidate();
                    generatorsCard.repaint();
                }
            }
        });
        targetCombo.addActionListener(event -> {
            updateCustomTableHeader();
            updateTargetSuffix();
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
        samplesPerVcpu.setPlaceholder(Integer.toString(defaultSamplesPerVcpu));
        samplesPerVcpu.setToolTipText("Default: " + defaultSamplesPerVcpu + " samples/s per vCPU");
        long stepDurationSeconds = parseLong(stepDuration.getText()) == null
                ? 300 : parseLong(stepDuration.getText());
        stepRamp.setPlaceholder(Long.toString(Math.min(10, stepDurationSeconds / 5)));
        String hostText = generatorHosts.getText().trim();
        boolean hostsSet = !hostText.isEmpty();
        setFormFieldEnabled(agents, !hostsSet);
        if (hostsSet) {
            int hostCount = hostText.split(",", -1).length;
            String countText = Integer.toString(hostCount);
            if (!agents.getText().equals(countText)) {
                agents.setText(NumericDocumentFilter.format(
                        hostCount, NumericDocumentFilter.Mode.WHOLE));
            }
        }
        WorkloadFormState.Context context = new WorkloadFormState.Context(hostText,
                maxThreadsPerGenerator.getText(), messagesPerSample.getText(),
                LoadCommandHandler.enabledSamplerClasses(node), LoadCommandHandler.jdbcPools(node),
                generatorVcpus.getText(), generatorRamGb.getText(), samplesPerVcpu.getText(),
                scriptHeavy);
        WorkloadInputs.TargetType targetType = (WorkloadInputs.TargetType) targetCombo.getSelectedItem();
        String rateCap = targetType == WorkloadInputs.TargetType.USERS ? cap.getText() : "";
        updateDerivedShapePlaceholders(targetType);
        evaluation = null;
        ShapeInputError shapeError = shapeInputError();
        if (shapeError == null && shapeCombo.getSelectedItem() == WorkloadShape.CUSTOM) {
            shapeError = customConversionInputErrorWithField(targetType, messagesPerSample.getText(),
                    response.getText(), think.getText());
        }
        WorkloadShapeConfig shapeConfig = null;
        if (shapeError == null) {
            try {
                shapeConfig = shapeConfigForForm(targetType, samplers);
            } catch (InvalidWorkloadValueException e) {
                evaluation = new WorkloadFormState.Evaluation(null, e.getMessage(), e.field());
            } catch (IllegalArgumentException | ArithmeticException e) {
                shapeError = new ShapeInputError(customConversionFailureField(targetType),
                        customConversionFailureMessage(targetType));
            }
        }
        if (shapeError != null) {
            evaluation = new WorkloadFormState.Evaluation(null, shapeError.message(), shapeError.field());
        } else if (evaluation == null) {
            evaluation = WorkloadFormState.evaluate(targetType,
                    target.getText(), rateCap, response.getText(), think.getText(), ramp.getText(),
                    (String) rampUnit.getSelectedItem(), hold.getText(), (String) holdUnit.getSelectedItem(),
                    agents.getText(), samplers, prefix.getText(), preparation.planFileName(), context, shapeConfig);
        }
        String error = samplers == 0 ? LoadCommandHandler.NO_REQUESTS : evaluation.error();
        WorkloadShape selectedShape = (WorkloadShape) shapeCombo.getSelectedItem();
        boolean blankTargetError = evaluation.field() == WorkloadField.TARGET
                && target.getText().isBlank()
                && (selectedShape == null || !selectedShape.usesOpenModelThreadGroup());
        fieldShellList.forEach(shell -> shell.setInvalid(false));
        FieldShell invalidShell = blankTargetError ? null : shellFor(evaluation.field());
        if (invalidShell != null) {
            invalidShell.setInvalid(true);
        }
        applyButton.setEnabled(canApply(samplers, evaluation));
        if ((advancedField(evaluation.field()) || advancedError(error)) && !advancedExpanded) {
            advancedExpanded = true;
            rebuildFormRows();
        }
        showStatus(error, canApply(samplers, evaluation));
        WorkloadPlan plan = evaluation.plan();
        boolean chartError = plan == null && !blankTargetError && error != null && !error.isBlank();
        chart.setPlan(plan, chartError ? error : null, chartError);
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
        updateCommandRows(cliArea);
        warningsArea.setVisible(!warningsArea.getText().isBlank());
        assumptionsArea.setVisible(!assumptionsArea.getText().isBlank());
        infoArea.setVisible(!infoArea.getText().isBlank());
        setNotesText(parsedArea, parsedNotes(preparation.spec()));
        parsedArea.setVisible(!parsedArea.getText().isBlank());
        notes.removeAll();
        List<Callout> noteCallouts = List.of(warningsCallout, assumptionsCallout, infoCallout, parsedCallout);
        for (int index = 0; index < noteCallouts.size(); index++) {
            Callout callout = noteCallouts.get(index);
            JTextArea area = List.of(warningsArea, assumptionsArea, infoArea, parsedArea).get(index);
            callout.setVisible(!area.getText().isBlank());
            if (callout.isVisible()) {
                callout.refreshTheme();
                notes.add(callout);
                notes.add(javax.swing.Box.createVerticalStrut(UiTokens.SPACE_2));
            }
        }
        aiSuggestionPanel.setVisible(latestSuggestion != null);
        if (latestSuggestion != null) {
            aiSuggestionCallout.setVisible(true);
            aiSuggestionCallout.refreshTheme();
            notes.add(aiSuggestionCallout);
        }
        notes.revalidate();
        notes.repaint();
        syncKpiTiles();
        updateKpiLayout();
    }

    private void setFormFieldEnabled(JTextField field, boolean enabled) {
        field.setEnabled(enabled);
        FieldShell shell = fieldShells.get(field);
        if (shell != null) {
            shell.setEnabled(enabled);
        }
    }

    private void showStatus(String error, boolean applicable) {
        if (error != null && !error.isBlank()) {
            errorLabel.setText(error);
            errorLabel.setForeground(ThemeColors.error());
            errorLabel.setIcon(new StatusDotIcon(false));
            errorLabel.setVisible(true);
        } else if (applicable) {
            errorLabel.setText("Ready to apply");
            errorLabel.setForeground(ThemeColors.success());
            errorLabel.setIcon(new StatusDotIcon(true));
            errorLabel.setVisible(true);
        } else {
            errorLabel.setText(" ");
            errorLabel.setIcon(null);
            errorLabel.setVisible(false);
        }
    }

    private static boolean advancedError(String error) {
        if (error == null) {
            return false;
        }
        String normalized = error.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("prefix")
                || normalized.contains("sample") && normalized.contains("vcpu");
    }

    private boolean advancedField(WorkloadField field) {
        return field == WorkloadField.SAMPLES_PER_VCPU || field == WorkloadField.RATE_CAP
                || field == WorkloadField.MESSAGES_PER_SAMPLE || field == WorkloadField.VCPUS
                || field == WorkloadField.RAM || field == WorkloadField.MAX_THREADS_PER_GENERATOR;
    }

    private FieldShell shellFor(WorkloadField field) {
        if (field == null) {
            return null;
        }
        JTextField input = switch (field) {
            case TARGET -> target;
            case PREFIX -> prefix;
            case RATE_CAP -> cap;
            case RESPONSE -> response;
            case THINK -> think;
            case RAMP -> ramp;
            case HOLD -> hold;
            case AGENTS -> agents;
            case GENERATOR_HOSTS -> generatorHosts;
            case MESSAGES_PER_SAMPLE -> messagesPerSample;
            case MAX_THREADS_PER_GENERATOR -> maxThreadsPerGenerator;
            case VCPUS -> generatorVcpus;
            case RAM -> generatorRamGb;
            case SAMPLES_PER_VCPU -> samplesPerVcpu;
            case RAMP_DOWN -> rampDown;
            case SHAPE_START -> shapeCombo.getSelectedItem() == WorkloadShape.STRESS
                    ? stressStart : stepStart;
            case SHAPE_END -> shapeCombo.getSelectedItem() == WorkloadShape.STRESS
                    ? stressCeiling : stepEnd;
            case SHAPE_STEP -> shapeCombo.getSelectedItem() == WorkloadShape.STRESS
                    ? stressStep : stepSize;
            case SHAPE_STEP_DURATION -> shapeCombo.getSelectedItem() == WorkloadShape.STRESS
                    ? stressDuration : stepDuration;
            case SHAPE_STEP_RAMP -> stepRamp;
            case SPIKE_BASELINE -> spikeBaseline;
            case SPIKE_PEAK -> spikePeak;
            case SPIKE_WARMUP -> spikeWarmup;
            case SPIKE_DURATION -> spikeDuration;
            case SPIKE_RECOVERY -> spikeRecovery;
            default -> null;
        };
        if (field == WorkloadField.CUSTOM_STAGES) {
            return customStagesShell;
        }
        return input == null ? null : fieldShells.get(input);
    }

    private void syncKpiTiles() {
        if (kpiTiles[0] == null) {
            return;
        }
        int[] indexes = {0, 2, 4, 7};
        for (int index = 0; index < indexes.length; index++) {
            kpiTiles[index].setValue(resultValues[indexes[index]].getText());
        }
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

    private ShapeInputError shapeInputError() {
        WorkloadShape shape = (WorkloadShape) shapeCombo.getSelectedItem();
        List<ShapeInputError> errors = new ArrayList<>();
        if (shape.usesOpenModelThreadGroup()) {
            addShapeFieldError(errors, rampDownEnabled.isSelected() ? rampDown.getText() : "",
                    "Ramp down", true, WorkloadField.RAMP_DOWN);
        }
        switch (shape) {
            case STEP -> {
                addShapeFieldError(errors, stepStart.getText(), "Step start target", false, WorkloadField.SHAPE_START);
                addShapeFieldError(errors, stepEnd.getText(), "Step end target", false, WorkloadField.SHAPE_END);
                addShapeFieldError(errors, stepSize.getText(), "Step size", false, WorkloadField.SHAPE_STEP);
                addShapeFieldError(errors, stepDuration.getText(), "Step duration", true,
                        WorkloadField.SHAPE_STEP_DURATION);
                addShapeFieldError(errors, stepRamp.getText(), "Ramp per step", true,
                        WorkloadField.SHAPE_STEP_RAMP);
            }
            case SPIKE -> {
                addShapeFieldError(errors, spikeBaseline.getText(), "Spike baseline", false,
                        WorkloadField.SPIKE_BASELINE);
                addShapeFieldError(errors, spikePeak.getText(), "Spike peak", false, WorkloadField.SPIKE_PEAK);
                addShapeFieldError(errors, spikeWarmup.getText(), "Warm-up hold", true, WorkloadField.SPIKE_WARMUP);
                addShapeFieldError(errors, spikeDuration.getText(), "Spike duration", true,
                        WorkloadField.SPIKE_DURATION);
                addShapeFieldError(errors, spikeRecovery.getText(), "Recovery hold", true,
                        WorkloadField.SPIKE_RECOVERY);
            }
            case STRESS -> {
                addShapeFieldError(errors, stressStart.getText(), "Stress start target", false,
                        WorkloadField.SHAPE_START);
                addShapeFieldError(errors, stressCeiling.getText(), "Stress ceiling", false,
                        WorkloadField.SHAPE_END);
                addShapeFieldError(errors, stressStep.getText(), "Stress step size", false,
                        WorkloadField.SHAPE_STEP);
                addShapeFieldError(errors, stressDuration.getText(), "Stress step duration", true,
                        WorkloadField.SHAPE_STEP_DURATION);
            }
            case CUSTOM -> {
                if (!customRowCountWithinLimit(customStageModel.getRowCount())) {
                    errors.add(new ShapeInputError(
                            WorkloadField.CUSTOM_STAGES, "Custom shapes require between 1 and 10 rows."));
                } else {
                    for (int row = 0; row < customStageModel.getRowCount(); row++) {
                        int stage = row + 1;
                        addShapeFieldError(errors, customCellText(row, 0),
                                "Custom stage " + stage + " target", false, WorkloadField.CUSTOM_STAGES);
                        addShapeFieldError(errors, customCellText(row, 1),
                                "Custom stage " + stage + " ramp", true, WorkloadField.CUSTOM_STAGES);
                        addShapeFieldError(errors, customCellText(row, 2),
                                "Custom stage " + stage + " hold", true, WorkloadField.CUSTOM_STAGES);
                    }
                }
            }
            default -> {
            }
        }
        for (ShapeInputError error : errors) {
            if (error != null) {
                return error;
            }
        }
        return null;
    }

    private void updateDerivedShapePlaceholders(WorkloadInputs.TargetType targetType) {
        double stepStartValue = fieldDouble(stepStart, 100);
        double stepSizeValue = fieldDouble(stepSize, 100);
        double stressStartValue = fieldDouble(stressStart, 100);
        double stressStepValue = fieldDouble(stressStep, 100);
        double baselineValue = fieldDouble(spikeBaseline, 100);
        stepEnd.setPlaceholder(NumericDocumentFilter.format(WorkloadShapeConfig.defaultEnd(
                WorkloadShape.STEP, stepStartValue, stepSizeValue, targetType), NumericDocumentFilter.Mode.DECIMAL));
        stressCeiling.setPlaceholder(NumericDocumentFilter.format(WorkloadShapeConfig.defaultEnd(
                WorkloadShape.STRESS, stressStartValue, stressStepValue, targetType),
                NumericDocumentFilter.Mode.DECIMAL));
        spikePeak.setPlaceholder(NumericDocumentFilter.format(WorkloadShapeConfig.defaultEnd(
                WorkloadShape.SPIKE, baselineValue, null, targetType), NumericDocumentFilter.Mode.DECIMAL));
    }

    private String customCellText(int row, int column) {
        Object value = customStageModel.getValueAt(row, column);
        return value == null ? "" : value.toString();
    }

    private static void addShapeFieldError(List<String> errors, String value, String label, boolean wholeNumber) {
        errors.add(shapeFieldError(value, label, wholeNumber));
    }

    private static void addShapeFieldError(List<ShapeInputError> errors, String value, String label,
                                           boolean wholeNumber, WorkloadField field) {
        String message = shapeFieldError(value, label, wholeNumber);
        if (message != null) {
            errors.add(new ShapeInputError(field, message));
        }
    }

    private record ShapeInputError(WorkloadField field, String message) {
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
            return value.trim().equals(".") ? "Enter a number." : label + " must be a number.";
        }
        return parsed < 0 ? label + " cannot be negative." : null;
    }

    static String rampDownFieldError(boolean enabled, String value) {
        return enabled ? shapeFieldError(value, "Ramp down", true) : null;
    }

    static String customConversionInputError(WorkloadInputs.TargetType targetType, String messagesPerSampleText,
                                             String responseText, String thinkText) {
        ShapeInputError error = customConversionInputErrorWithField(
                targetType, messagesPerSampleText, responseText, thinkText);
        return error == null ? null : error.message();
    }

    private static ShapeInputError customConversionInputErrorWithField(
            WorkloadInputs.TargetType targetType, String messagesPerSampleText,
            String responseText, String thinkText) {
        if (targetType == WorkloadInputs.TargetType.MESSAGE_RATE && !messagesPerSampleText.isBlank()) {
            Double messagesPerSample = parseDouble(messagesPerSampleText);
            if (messagesPerSample == null) {
                return new ShapeInputError(WorkloadField.MESSAGES_PER_SAMPLE,
                        "Messages per sample must be a number.");
            }
            if (messagesPerSample <= 0) {
                return new ShapeInputError(WorkloadField.MESSAGES_PER_SAMPLE,
                        "Messages per sample must be at least 1.");
            }
            if (messagesPerSample != Math.floor(messagesPerSample)) {
                return new ShapeInputError(WorkloadField.MESSAGES_PER_SAMPLE,
                        "Messages per sample must be a whole number.");
            }
        }
        if (targetType == WorkloadInputs.TargetType.USERS) {
            Long response = optionalLong(responseText, 500L);
            Long thinkTime = optionalLong(thinkText, 0L);
            if (response == null) {
                return new ShapeInputError(WorkloadField.RESPONSE, "Response time must be a whole number.");
            }
            if (thinkTime == null) {
                return new ShapeInputError(WorkloadField.THINK, "Think time must be a whole number.");
            }
            if (response + thinkTime <= 0) {
                return new ShapeInputError(WorkloadField.RESPONSE,
                        "Response time and think time must total more than zero.");
            }
        }
        return null;
    }

    private static Long optionalLong(String text, long fallback) {
        return text == null || text.isBlank() ? fallback : parseLong(text);
    }

    private WorkloadField customConversionFailureField(WorkloadInputs.TargetType targetType) {
        return targetType == WorkloadInputs.TargetType.MESSAGE_RATE
                ? WorkloadField.MESSAGES_PER_SAMPLE : targetType == WorkloadInputs.TargetType.USERS
                ? WorkloadField.RESPONSE : WorkloadField.CUSTOM_STAGES;
    }

    private String customConversionFailureMessage(WorkloadInputs.TargetType targetType) {
        return targetType == WorkloadInputs.TargetType.MESSAGE_RATE
                ? "Messages per sample must be at least 1." : targetType == WorkloadInputs.TargetType.USERS
                ? "Response time and think time must total more than zero."
                : "Custom stage target could not be converted.";
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
                BigDecimal rawTarget = parseBigDecimal(String.valueOf(customStageModel.getValueAt(row, 0)));
                BigDecimal rawRamp = parseBigDecimal(String.valueOf(customStageModel.getValueAt(row, 1)));
                BigDecimal rawHold = parseBigDecimal(String.valueOf(customStageModel.getValueAt(row, 2)));
                if (rawTarget == null || rawRamp == null || rawHold == null) {
                    customStages.clear();
                    break;
                }
                long targetLimit = WorkloadLimits.maxTarget(targetType);
                String targetUnit = WorkloadLimits.targetUnit(targetType);
                if (rawTarget.compareTo(BigDecimal.valueOf(targetLimit)) > 0) {
                    throw new InvalidWorkloadValueException(WorkloadField.CUSTOM_STAGES,
                            WorkloadLimits.maxMessage("Custom stage " + (row + 1) + " target",
                                    targetLimit, targetUnit));
                }
                if (targetType == WorkloadInputs.TargetType.USERS
                        && rawTarget.stripTrailingZeros().scale() > 0) {
                    throw new InvalidWorkloadValueException(WorkloadField.CUSTOM_STAGES,
                            "Custom stage " + (row + 1) + " target must be a whole number of users.");
                }
                if (rawRamp.compareTo(BigDecimal.valueOf(WorkloadLimits.MAX_DURATION_SECONDS)) > 0) {
                    throw new InvalidWorkloadValueException(WorkloadField.CUSTOM_STAGES,
                            WorkloadLimits.durationLimitMessage("Custom stage " + (row + 1) + " ramp"));
                }
                if (rawHold.compareTo(BigDecimal.valueOf(WorkloadLimits.MAX_DURATION_SECONDS)) > 0) {
                    throw new InvalidWorkloadValueException(WorkloadField.CUSTOM_STAGES,
                            WorkloadLimits.durationLimitMessage("Custom stage " + (row + 1) + " hold"));
                }
                double targetValue = rawTarget.doubleValue();
                long rowRamp;
                long rowHold;
                try {
                    rowRamp = rawRamp.longValueExact();
                    rowHold = rawHold.longValueExact();
                } catch (ArithmeticException e) {
                    customStages.clear();
                    break;
                }
                if (!Double.isFinite(targetValue) || rowRamp < 0 || rowHold <= 0) {
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
        double start = fieldDouble(shape == WorkloadShape.STRESS ? stressStart : stepStart, 100);
        double step = fieldDouble(shape == WorkloadShape.STRESS ? stressStep : stepSize, 100);
        double baseline = fieldDouble(spikeBaseline, 100);
        return new WorkloadShapeConfig(shape, start,
                fieldDouble(shape == WorkloadShape.STRESS ? stressCeiling : stepEnd,
                        WorkloadShapeConfig.defaultEnd(shape, start, step, targetType)),
                step, fieldLong(shape == WorkloadShape.STRESS ? stressDuration : stepDuration),
                fieldLong(stepRamp), baseline, fieldDouble(spikePeak,
                        WorkloadShapeConfig.defaultEnd(WorkloadShape.SPIKE, baseline, null, targetType)),
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

    private static BigDecimal parseBigDecimal(String value) {
        try {
            return new BigDecimal(value.trim());
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
            showStatus("Finish editing the Custom stages before applying.", false);
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
            commandCodeBlock.updateTextInsets();
            javax.swing.Timer timer = new javax.swing.Timer(1500, event -> {
                copyButton.setText("Copy");
                commandCodeBlock.updateTextInsets();
            });
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
            field.setText(NumericDocumentFilter.format(value, NumericDocumentFilter.Mode.WHOLE));
        }
    }

    private static void setDouble(JTextField field, Double value) {
        if (value != null) {
            field.setText(NumericDocumentFilter.format(value, NumericDocumentFilter.Mode.DECIMAL));
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
                field.setText(NumericDocumentFilter.format(
                        seconds / multiplier, NumericDocumentFilter.Mode.WHOLE));
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

    private static Icon shapeIcon(WorkloadShape shape) {
        return new Icon() {
            @Override
            public int getIconWidth() {
                return 16;
            }

            @Override
            public int getIconHeight() {
                return 12;
            }

            @Override
            public void paintIcon(Component component, Graphics graphics, int x, int y) {
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.setColor(component != null && component.isEnabled()
                            ? component.getForeground() : ThemeColors.secondaryText());
                    g.setStroke(new java.awt.BasicStroke(1.5f));
                    java.awt.geom.Path2D path = new java.awt.geom.Path2D.Double();
                    switch (shape) {
                        case LOAD, ENDURANCE -> {
                            path.moveTo(x, y + 11);
                            path.lineTo(x + 4, y + 2);
                            path.lineTo(x + 16, y + 2);
                        }
                        case STEP, STRESS -> {
                            path.moveTo(x, y + 10);
                            path.lineTo(x + 4, y + 10);
                            path.lineTo(x + 4, y + 7);
                            path.lineTo(x + 8, y + 7);
                            path.lineTo(x + 8, y + 4);
                            path.lineTo(x + 12, y + 4);
                            path.lineTo(x + 12, y + 1);
                            path.lineTo(x + 16, y + 1);
                        }
                        case SPIKE -> {
                            path.moveTo(x, y + 8);
                            path.lineTo(x + 5, y + 8);
                            path.lineTo(x + 8, y + 1);
                            path.lineTo(x + 11, y + 8);
                            path.lineTo(x + 16, y + 8);
                        }
                        case CUSTOM -> {
                            path.moveTo(x, y + 9);
                            path.lineTo(x + 4, y + 3);
                            path.lineTo(x + 8, y + 7);
                            path.lineTo(x + 12, y + 1);
                            path.lineTo(x + 16, y + 5);
                        }
                    }
                    g.draw(path);
                } finally {
                    g.dispose();
                }
            }
        };
    }

    record FormRow(JComponent label, JComponent field, SectionCard card, boolean fullWidth) {
    }

    private static final class LegendIcon implements Icon {
        private final boolean dashed;

        private LegendIcon(boolean dashed) {
            this.dashed = dashed;
        }

        @Override
        public int getIconWidth() {
            return 18;
        }

        @Override
        public int getIconHeight() {
            return 12;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setColor(dashed ? ThemeColors.info() : ThemeColors.accent());
                g.setStroke(dashed
                        ? new java.awt.BasicStroke(1.6f, java.awt.BasicStroke.CAP_BUTT,
                        java.awt.BasicStroke.JOIN_BEVEL, 0, new float[]{4, 3}, 0)
                        : new java.awt.BasicStroke(2.2f));
                g.drawLine(x + 1, y + 6, x + 17, y + 6);
            } finally {
                g.dispose();
            }
        }
    }

    private static final class StatusDotIcon implements Icon {
        private final boolean ready;

        private StatusDotIcon(boolean ready) {
            this.ready = ready;
        }

        @Override
        public int getIconWidth() {
            return 10;
        }

        @Override
        public int getIconHeight() {
            return 10;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            graphics.setColor(ready ? ThemeColors.success() : ThemeColors.error());
            graphics.fillOval(x + 2, y + 2, 6, 6);
        }
    }

    private static final class KpiTile extends JPanel {
        private final String label;
        private final JLabel title = new JLabel();
        private final JTextArea value = new JTextArea(1, 8);

        private KpiTile(String label) {
            super(new BorderLayout(0, UiTokens.SPACE_1));
            this.label = label;
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(UiTokens.SPACE_2, UiTokens.SPACE_3,
                    UiTokens.SPACE_2, UiTokens.SPACE_3));
            title.setText(label);
            value.setEditable(false);
            value.setLineWrap(true);
            value.setWrapStyleWord(true);
            value.setFocusable(false);
            value.setBorder(BorderFactory.createEmptyBorder());
            add(value, BorderLayout.CENTER);
            add(title, BorderLayout.SOUTH);
            refreshFonts();
        }

        @Override
        public void doLayout() {
            super.doLayout();
            String wrapped = wrapKpiTitle(label, title.getFontMetrics(title.getFont()), title.getWidth());
            if (!wrapped.equals(title.getText())) {
                title.setText(wrapped);
                revalidate();
            }
        }

        private void setValue(String text) {
            value.setText(text == null ? "-" : text);
        }

        private void refreshFonts() {
            Font labelFont = UIManager.getFont("Label.font");
            title.setFont(UiTokens.caption(labelFont));
            title.setForeground(ThemeColors.secondaryText(ThemeColors.surface()));
            value.setFont(UiTokens.title(labelFont));
            value.setForeground(ThemeColors.foreground());
            value.setEditable(false);
            styleReadOnlyArea(value);
            value.setFocusable(false);
            value.setBorder(BorderFactory.createEmptyBorder());
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                int width = getWidth() - 1;
                int height = getHeight() - 1;
                g.setColor(ThemeColors.surface());
                g.fillRoundRect(0, 0, width, height, UiTokens.RADIUS_SMALL * 2,
                        UiTokens.RADIUS_SMALL * 2);
                g.setColor(ThemeColors.separator());
                g.drawRoundRect(0, 0, width, height, UiTokens.RADIUS_SMALL * 2,
                        UiTokens.RADIUS_SMALL * 2);
            } finally {
                g.dispose();
            }
        }
    }

    static final class CodeBlockPanel extends JPanel {
        private final JComponent content;
        private final JComponent overlay;

        CodeBlockPanel(JComponent content, JComponent overlay) {
            super(null);
            this.content = content;
            this.overlay = overlay;
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(UiTokens.SPACE_1,
                    UiTokens.SPACE_2, UiTokens.SPACE_2, UiTokens.SPACE_2));
            content.setBorder(BorderFactory.createEmptyBorder(UiTokens.SPACE_2, UiTokens.SPACE_3,
                    UiTokens.SPACE_2, UiTokens.SPACE_3));
            updateTextInsets();
            add(content);
            add(overlay);
            setComponentZOrder(overlay, 0);
        }

        void updateTextInsets() {
            int buttonWidth = overlay.getPreferredSize().width;
            Font buttonFont = overlay.getFont();
            if (buttonFont != null) {
                int copiedWidth = overlay.getFontMetrics(buttonFont).stringWidth("Copied");
                Insets buttonInsets = overlay.getInsets();
                buttonWidth = Math.max(buttonWidth,
                        copiedWidth + buttonInsets.left + buttonInsets.right);
            }
            content.setBorder(BorderFactory.createEmptyBorder(UiTokens.SPACE_2, UiTokens.SPACE_3,
                    UiTokens.SPACE_2, buttonWidth + UiTokens.SPACE_2));
            revalidate();
            repaint();
        }

        @Override
        public Dimension getMinimumSize() {
            return PreferredHeightMinimum.preserve(super.getMinimumSize(), getPreferredSize());
        }

        @Override
        public Dimension getPreferredSize() {
            Insets insets = getInsets();
            Dimension contentSize = content.getPreferredSize();
            Dimension overlaySize = overlay.getPreferredSize();
            return new Dimension(insets.left + Math.max(contentSize.width, overlaySize.width) + insets.right,
                    insets.top + Math.max(contentSize.height, overlaySize.height) + insets.bottom);
        }

        @Override
        public void doLayout() {
            Insets insets = getInsets();
            int x = insets.left;
            int y = insets.top;
            int width = Math.max(0, getWidth() - insets.left - insets.right);
            int height = Math.max(0, getHeight() - insets.top - insets.bottom);
            content.setBounds(x, y, width, height);
            Dimension overlaySize = overlay.getPreferredSize();
            overlay.setBounds(x + width - overlaySize.width, y,
                    overlaySize.width, overlaySize.height);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                int width = getWidth() - 1;
                int height = getHeight() - 1;
                g.setColor(ThemeColors.codeBackground());
                g.fillRoundRect(0, 0, width, height, UiTokens.RADIUS_SMALL * 2,
                        UiTokens.RADIUS_SMALL * 2);
                g.setColor(ThemeColors.separator());
                g.drawRoundRect(0, 0, width, height, UiTokens.RADIUS_SMALL * 2,
                        UiTokens.RADIUS_SMALL * 2);
            } finally {
                g.dispose();
            }
        }
    }

    private static final class PreferredHeightScrollPane extends JScrollPane {
        private PreferredHeightScrollPane(JComponent view) {
            super(view);
        }

        @Override
        public Dimension getMinimumSize() {
            return PreferredHeightMinimum.preserve(super.getMinimumSize(), getPreferredSize());
        }
    }

    private static final class FooterPanel extends JPanel {
        private FooterPanel(java.awt.LayoutManager layout) {
            super(layout);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            graphics.setColor(ThemeColors.separator());
            graphics.drawLine(0, 0, getWidth(), 0);
        }
    }

    private static final class WideColumnWrapper extends JPanel {
        private WideColumnWrapper(JPanel content) {
            super(new BorderLayout());
            setOpaque(false);
            setContent(content);
        }

        private void setContent(JPanel content) {
            removeAll();
            add(content, BorderLayout.CENTER);
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension preferred = super.getPreferredSize();
            return new Dimension(0, preferred.height);
        }

        @Override
        public Dimension getMinimumSize() {
            Dimension minimum = super.getMinimumSize();
            return new Dimension(0, Math.max(minimum.height, getPreferredSize().height));
        }
    }

    private static class HeightPreservingPanel extends JPanel {
        private HeightPreservingPanel() {
        }

        private HeightPreservingPanel(java.awt.LayoutManager layout) {
            super(layout);
        }

        @Override
        public Dimension getMinimumSize() {
            Dimension minimum = super.getMinimumSize();
            return PreferredHeightMinimum.preserve(minimum, getPreferredSize());
        }
    }

    private static final class FormPanel extends HeightPreservingPanel implements Scrollable {
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
