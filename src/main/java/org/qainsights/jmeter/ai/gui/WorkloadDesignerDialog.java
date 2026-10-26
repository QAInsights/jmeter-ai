package org.qainsights.jmeter.ai.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
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
import javax.swing.KeyStroke;
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
    private final JPanel form = new JPanel();
    private final JPanel capRow;
    private final JComboBox<JMeterTreeNode> groupCombo = new JComboBox<>();
    private final JComboBox<WorkloadInputs.TargetType> targetCombo =
            new JComboBox<>(WorkloadInputs.TargetType.values());
    private final JTextField target = new JTextField();
    private final JTextField cap = new JTextField();
    private final JTextField response = new JTextField();
    private final JTextField think = new JTextField();
    private final JTextField ramp = new JTextField();
    private final JComboBox<String> rampUnit = new JComboBox<>(new String[]{"s", "min"});
    private final JTextField hold = new JTextField();
    private final JComboBox<String> holdUnit = new JComboBox<>(new String[]{"s", "min", "h"});
    private final JTextField agents = new JTextField();
    private final JTextField prefix = new JTextField();
    private final LoadShapeChart chart = new LoadShapeChart();
    private final JLabel errorLabel = new JLabel(" ");
    private final JLabel[] resultValues = new JLabel[6];
    private final JTextArea warningsArea = notesArea(2, ThemeColors.warning());
    private final JTextArea assumptionsArea = notesArea(2, ThemeColors.info());
    private final JTextArea parsedArea = notesArea(2, ThemeColors.secondaryText());
    private JPanel notes;
    private final JTextArea cliArea = new JTextArea(4, 24);
    private final JButton applyButton = new JButton("Apply to Thread Group");
    private final JButton copyButton = new JButton("Copy");
    private boolean prefixEdited;
    private boolean completed;
    private WorkloadFormState.Evaluation evaluation;

    public WorkloadDesignerDialog(LoadCommandHandler.Preparation preparation,
                                  Consumer<Completion> onFinish) {
        super(mainFrame(), "Workload Designer", ModalityType.APPLICATION_MODAL);
        this.preparation = preparation;
        this.onFinish = onFinish == null ? ignored -> { } : onFinish;
        capRow = formRow(new JLabel("<html>Cap at requests<br>per second</html>"), cap);
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
        setSize(920, 700);
        setLocationRelativeTo(getOwner());
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
        JLabel caption = new JLabel("<html>Little's Law: threads ≈ requests per second × (response time + think time), "
                + "sized with 1.5× headroom.<br>Values are written as ${__P()} properties so CI can override them.</html>");
        caption.setFont(UiTokens.caption(caption.getFont()));
        caption.setForeground(ThemeColors.secondaryText());
        header.add(title, BorderLayout.NORTH);
        header.add(caption, BorderLayout.CENTER);
        root.add(header, BorderLayout.NORTH);
        JPanel columns = new JPanel(new GridLayout(1, 2, UiTokens.SPACE_4, 0));
        columns.setOpaque(false);
        columns.add(buildForm());
        columns.add(buildPreview());
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

    private JPanel buildForm() {
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setOpaque(false);
        groupCombo.setRenderer((list, value, index, selected, focus) ->
                new JLabel(value == null ? "" : value.getName()));
        for (JMeterTreeNode node : preparation.threadGroups()) {
            groupCombo.addItem(node);
        }
        groupCombo.setSelectedItem(preparation.selectedGroup());
        addFormRow("Thread Group", groupCombo);
        addFormRow("Target type", targetCombo);
        addFormRow("Target value", target);
        addFormRow("<html>Average response time<br>(ms, default 500)</html>", response);
        addFormRow("<html>Think time per request<br>(ms, default 0)</html>", think);
        addFormRow("<html>Ramp-up<br>(default 10% of hold)</html>", withUnit(ramp, rampUnit));
        addFormRow("<html>Hold<br>(default 10 min)</html>", withUnit(hold, holdUnit));
        addFormRow("Load generators (default 1)", agents);
        addFormRow("Property prefix", prefix);
        targetCombo.setRenderer((list, value, index, selected, focus) ->
                new JLabel(targetName(value)));
        return form;
    }

    private void addFormRow(String label, JComponent field) {
        form.add(formRow(new JLabel(label), field));
        form.add(Box.createVerticalStrut(UiTokens.SPACE_1));
    }

    private static JPanel formRow(JComponent label, JComponent field) {
        JPanel row = new JPanel(new GridLayout(1, 2, UiTokens.SPACE_2, 0));
        row.setOpaque(false);
        row.add(label);
        row.add(field);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    private JPanel buildPreview() {
        JPanel preview = new JPanel(new BorderLayout(UiTokens.SPACE_2, UiTokens.SPACE_2));
        preview.setOpaque(false);
        preview.add(chart, BorderLayout.NORTH);
        JPanel lower = new JPanel(new BorderLayout(UiTokens.SPACE_2, UiTokens.SPACE_2));
        lower.setOpaque(false);
        JPanel grid = new JPanel(new GridLayout(6, 2, UiTokens.SPACE_1, UiTokens.SPACE_1));
        grid.setOpaque(false);
        String[] labels = {"Threads", "Requests / iteration", "Requests / second",
                "Throughput samples / min", "Total duration", "Per agent"};
        for (int index = 0; index < labels.length; index++) {
            grid.add(new JLabel(labels[index]));
            resultValues[index] = new JLabel("-");
            grid.add(resultValues[index]);
        }
        lower.add(grid, BorderLayout.NORTH);
        notes = new JPanel();
        notes.setLayout(new BoxLayout(notes, BoxLayout.Y_AXIS));
        notes.setOpaque(false);
        JScrollPane notesScroll = new JScrollPane(notes);
        notesScroll.setBorder(BorderFactory.createTitledBorder("Notes"));
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
        area.setMaximumSize(new Dimension(Integer.MAX_VALUE, area.getPreferredSize().height));
        return area;
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
        DocumentListener listener = new DocumentListener() {
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
                if (prefix.hasFocus()) {
                    prefixEdited = true;
                }
                updatePreview();
            }
        };
        for (JTextField field : List.of(target, cap, response, think, ramp, hold, agents, prefix)) {
            field.getDocument().addDocumentListener(listener);
        }
        targetCombo.addActionListener(event -> updatePreview());
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
        updateCapVisibility();
        JMeterTreeNode node = (JMeterTreeNode) groupCombo.getSelectedItem();
        int samplers = WorkloadApplier.countEnabledSamplers(node);
        evaluation = WorkloadFormState.evaluate((WorkloadInputs.TargetType) targetCombo.getSelectedItem(),
                target.getText(), cap.getText(), response.getText(), think.getText(), ramp.getText(),
                (String) rampUnit.getSelectedItem(), hold.getText(), (String) holdUnit.getSelectedItem(),
                agents.getText(), samplers, prefix.getText(), preparation.planFileName());
        String error = samplers == 0 ? LoadCommandHandler.NO_REQUESTS : evaluation.error();
        errorLabel.setText(error == null || error.isBlank() ? " " : error);
        applyButton.setEnabled(samplers > 0 && evaluation.valid());
        WorkloadPlan plan = evaluation.plan();
        chart.setPlan(plan);
        if (plan != null) {
            resultValues[0].setText(Integer.toString(plan.threads()));
            resultValues[1].setText(Integer.toString(plan.samplersPerIteration()));
            resultValues[2].setText(plan.targetRequestsPerSecond() == null
                    ? "Expected " + WorkloadCalculatorFormat.format(plan.expectedRequestsPerSecond())
                    : WorkloadCalculatorFormat.format(plan.targetRequestsPerSecond()));
            resultValues[3].setText(plan.rpm() == null ? "No timer" : WorkloadCalculatorFormat.format(plan.rpm()));
            resultValues[4].setText(duration(plan.durationSeconds()));
            resultValues[5].setText(plan.agents() > 1
                    ? plan.perAgentThreads() + " threads, " + WorkloadCalculatorFormat.format(plan.perAgentRpm()) + " rpm"
                    : "Single agent");
            cliArea.setText(plan.cliCommand());
            warningsArea.setText(String.join("\n", plan.warnings()));
            assumptionsArea.setText(String.join("\n", plan.assumptions()));
        } else {
            for (JLabel value : resultValues) {
                value.setText("-");
            }
            cliArea.setText("");
            warningsArea.setText("");
            assumptionsArea.setText("");
        }
        warningsArea.setVisible(!warningsArea.getText().isBlank());
        assumptionsArea.setVisible(!assumptionsArea.getText().isBlank());
        parsedArea.setText(parsedNotes(preparation.spec()));
        parsedArea.setVisible(!parsedArea.getText().isBlank());
        notes.removeAll();
        for (JTextArea area : List.of(warningsArea, assumptionsArea, parsedArea)) {
            if (area.isVisible()) {
                area.setAlignmentX(Component.LEFT_ALIGNMENT);
                notes.add(area);
            }
        }
        notes.revalidate();
        notes.repaint();
    }

    private void updateCapVisibility() {
        boolean visible = targetCombo.getSelectedItem() == WorkloadInputs.TargetType.USERS;
        boolean currentlyVisible = capRow.getParent() == form;
        if (visible == currentlyVisible) {
            return;
        }
        if (visible) {
            form.add(capRow, 6);
            form.add(Box.createVerticalStrut(UiTokens.SPACE_1), 7);
        } else {
            form.remove(capRow);
            if (form.getComponentCount() > 7) {
                form.remove(6);
            }
        }
        form.revalidate();
        form.repaint();
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
            case REQUEST_RATE -> "Requests per second";
            case ITERATION_RATE -> "Iterations per second";
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

    private static void setDuration(JTextField field, JComboBox<String> unit, Long seconds) {
        if (seconds == null) {
            return;
        }
        for (String candidate : List.of("h", "min", "s")) {
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

    public record Completion(WorkloadPlan plan, WorkloadApplier.ApplyResult result, String error, boolean cancelled) {
    }

    private static final class WorkloadCalculatorFormat {
        private static String format(Double value) {
            return value == null ? "-" : java.math.BigDecimal.valueOf(value)
                    .stripTrailingZeros().toPlainString();
        }
    }
}
