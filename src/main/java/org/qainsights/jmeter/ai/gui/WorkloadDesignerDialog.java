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
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
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
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.gui.theme.UiTokens;
import org.qainsights.jmeter.ai.load.LoadCommandHandler;
import org.qainsights.jmeter.ai.load.LoadShapeChart;
import org.qainsights.jmeter.ai.load.WorkloadApplier;
import org.qainsights.jmeter.ai.load.WorkloadFormState;
import org.qainsights.jmeter.ai.load.WorkloadInputs;
import org.qainsights.jmeter.ai.load.WorkloadPlan;
import org.qainsights.jmeter.ai.load.WorkloadSpec;

/** Modal, deterministic workload editor with a live load-shape preview. */
public final class WorkloadDesignerDialog extends JDialog {
    private final LoadCommandHandler.Preparation preparation;
    private final Consumer<Completion> onFinish;
    private final FormPanel form = new FormPanel();
    private final List<FormRow> formRows = new ArrayList<>();
    private final FormRow capRow;
    private final FormRow messagesPerSampleRow;
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
    private final JTextField maxThreadsPerGenerator = new JTextField("1000");
    private final JTextField messagesPerSample = new JTextField("1");
    private final JTextField prefix = new JTextField();
    private final LoadShapeChart chart = new LoadShapeChart();
    private final JLabel errorLabel = new JLabel(" ");
    private final JLabel[] resultValues = new JLabel[9];
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
    private boolean prefixEdited;
    private boolean capRowVisible;
    private boolean messagesPerSampleRowVisible;
    private boolean completed;
    private boolean updatingPreview;
    private boolean stackedColumns;
    private WorkloadFormState.Evaluation evaluation;

    public WorkloadDesignerDialog(LoadCommandHandler.Preparation preparation,
                                  Consumer<Completion> onFinish) {
        super(mainFrame(), "Workload Designer", ModalityType.APPLICATION_MODAL);
        this.preparation = preparation;
        this.onFinish = onFinish == null ? ignored -> { } : onFinish;
        capRow = new FormRow(new JLabel("<html>Cap at samples<br>per second</html>"), cap);
        messagesPerSampleRow = new FormRow(new JLabel("Messages per sample"), messagesPerSample);
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
        addFormRow("Generator hosts", generatorHosts);
        addFormRow("Load generators (default 1)", agents);
        addFormRow("Max threads per generator", maxThreadsPerGenerator);
        addFormRow("Property prefix", prefix);
        targetCombo.setRenderer((list, value, index, selected, focus) ->
                new JLabel(targetName(value)));
        rebuildFormRows();
        return form;
    }

    private void addFormRow(String label, JComponent field) {
        formRows.add(new FormRow(new JLabel(label), field));
    }

    private void rebuildFormRows() {
        form.removeAll();
        int gridy = 0;
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
        JPanel grid = new JPanel(new GridLayout(9, 2, UiTokens.SPACE_1, UiTokens.SPACE_1));
        grid.setOpaque(false);
        String[] labels = {"Threads", "Samples / iteration", "Samples / second",
                "Throughput samples / min", "Total duration", "Per agent",
                "Sampler mix", "Generators needed", "Heap per generator"};
        for (int index = 0; index < labels.length; index++) {
            grid.add(new JLabel(labels[index]));
            resultValues[index] = new JLabel("-");
            grid.add(resultValues[index]);
        }
        lower.add(grid, BorderLayout.NORTH);
        notes = new ScrollablePanel();
        notes.setLayout(new BoxLayout(notes, BoxLayout.Y_AXIS));
        notes.setOpaque(false);
        notes.setAlignmentY(Component.TOP_ALIGNMENT);
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
                generatorHosts, maxThreadsPerGenerator, messagesPerSample, prefix)) {
            field.getDocument().addDocumentListener(listener);
        }
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
                LoadCommandHandler.enabledSamplerClasses(node), LoadCommandHandler.jdbcPools(node));
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
            String heapSize = plan.heapPerGeneratorMb() % 1024 == 0
                    ? plan.heapPerGeneratorMb() / 1024 + "g" : plan.heapPerGeneratorMb() + "m";
            resultValues[8].setText("HEAP=\"-Xms" + heapSize + " -Xmx" + heapSize + "\"");
            cliArea.setText(plan.cliCommand());
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
        for (JTextArea area : List.of(warningsArea, assumptionsArea, infoArea, parsedArea)) {
            if (area.isVisible()) {
                area.setAlignmentX(Component.LEFT_ALIGNMENT);
                notes.add(area);
            }
        }
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
            completed = true;
            onFinish.accept(new Completion(evaluation.plan(), result, "", false));
            dispose();
        } catch (Exception e) {
            completed = true;
            onFinish.accept(new Completion(null, null,
                    e.getMessage() == null ? "Could not apply the workload." : e.getMessage(), false));
            dispose();
        }
    }

    private void cancel() {
        if (!completed) {
            completed = true;
            onFinish.accept(new Completion(null, null, "", true));
        }
        dispose();
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

    public record Completion(WorkloadPlan plan, WorkloadApplier.ApplyResult result, String error, boolean cancelled) {
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
