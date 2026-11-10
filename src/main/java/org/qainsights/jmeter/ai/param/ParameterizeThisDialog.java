package org.qainsights.jmeter.ai.param;

import org.apache.jmeter.config.CSVDataSet;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.services.FileServer;
import org.qainsights.jmeter.ai.gui.ParameterDialogControls;
import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.gui.theme.UiTokens;
import org.qainsights.jmeter.ai.telemetry.Telemetry;
import org.qainsights.jmeter.ai.telemetry.TelemetryFeature;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import java.util.function.Function;
import org.qainsights.jmeter.ai.agent.jmeter.EdtExecutor;
import javax.swing.table.DefaultTableModel;

final class ParameterizeThisDialog extends JDialog {
    private final List<ParameterScanner.Candidate> candidates;
    private final DefaultTableModel rows;
    private final JTable table;
    private final JTextField filename;
    private final JTextField delimiter = new JTextField(",", 3);
    private final JCheckBox recycle = new JCheckBox("Recycle on EOF", true);
    private final JCheckBox stop = new JCheckBox("Stop thread on EOF", false);
    private final JComboBox<String> sharing =
            new JComboBox<>(new String[] {"All threads", "Current thread group", "Current thread"});
    private final JLabel error = new JLabel(" ");
    private final GuiPackage gui;
    private final JMeterTreeNode scope;
    private final Function<String, String> aiCall;
    private final JCheckBox askAi = new JCheckBox("Ask AI for sample rows");
    private final JSpinner sampleCount = new JSpinner(new SpinnerNumberModel(10, 1, 500, 1));
    private final JButton applyButton = ParameterDialogControls.button("Apply variables", true);
    private final JButton cancelButton = ParameterDialogControls.button("Cancel", false);
    private ParameterSampleRequest sampleRequest;
    private volatile boolean closed;
    private boolean committing;

    ParameterizeThisDialog(GuiPackage gui, JMeterTreeNode scope) {
        this(gui, scope, ParameterDialogControls.sampleRowsAiCall());
    }

    ParameterizeThisDialog(GuiPackage gui, JMeterTreeNode scope, Function<String, String> aiCall) {
        super(gui.getMainFrame(), "Parameterize this", true);
        this.gui = gui;
        this.scope = scope;
        this.aiCall = aiCall;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        Telemetry.record(TelemetryFeature.PARAM_OPENED);
        candidates = ParameterScanner.scan(scope);
        rows =
                new DefaultTableModel(
                        new String[] {
                            "Use", "Field path", "Current value", "Variable name", "Occurrences"
                        },
                        0) {
                    @Override
                    public Class<?> getColumnClass(int column) {
                        return column == 0
                                ? Boolean.class
                                : column == 4 ? Integer.class : String.class;
                    }

                    @Override
                    public boolean isCellEditable(int row, int column) {
                        return column == 0 || column == 3;
                    }
                };
        for (ParameterScanner.Candidate c : candidates) {
            rows.addRow(new Object[] {true, c.path, c.value, c.variable, c.occurrences});
        }
        table = new JTable(rows);
        styleTable(table);
        Rectangle screen = getGraphicsConfiguration().getBounds();
        table.setPreferredScrollableViewportSize(
                new Dimension(Math.min(860, screen.width - 100), Math.min(240, screen.height / 4)));
        String group =
                ParameterPlacement.threadGroupOf(scope).getName().replaceAll("[^A-Za-z0-9_-]", "_");
        filename = new JTextField("data/" + (group.isEmpty() ? "users" : group) + ".csv", 36);
        if (gui.getTestPlanFile() == null || gui.getTestPlanFile().isBlank()) {
            filename.setText(Path.of(FileServer.getFileServer().getBaseDir())
                    .resolve(filename.getText()).toAbsolutePath().toString());
        }
        JButton apply = applyButton;
        apply.setEnabled(!candidates.isEmpty());
        apply.addActionListener(e -> apply());
        JButton cancel = cancelButton;
        cancel.addActionListener(e -> dispose());
        JPanel content = content(table, candidates.isEmpty(), filename, delimiter, sharing,
                recycle, stop, error, apply, cancel, sampleOptions());
        setContentPane(content);
        getRootPane().setDefaultButton(apply);
        pack();
        java.awt.Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(getGraphicsConfiguration());
        setSize(Math.min(getPreferredSize().width, screen.width - insets.left - insets.right - 32),
                Math.min(getPreferredSize().height, screen.height - insets.top - insets.bottom - 32));
        setLocationRelativeTo(gui.getMainFrame());
        setLocation(Math.max(screen.x + insets.left + 16, getX()),
                Math.max(screen.y + insets.top + 16, getY()));
    }

    static JPanel content(JTable table, boolean empty, JTextField filename, JTextField delimiter,
            JComboBox<String> sharing, JCheckBox recycle, JCheckBox stop, JLabel error,
            JButton apply, JButton cancel) {
        return content(table, empty, filename, delimiter, sharing, recycle, stop, error,
                apply, cancel, null);
    }

    static JPanel content(JTable table, boolean empty, JTextField filename, JTextField delimiter,
            JComboBox<String> sharing, JCheckBox recycle, JCheckBox stop, JLabel error,
            JButton apply, JButton cancel, JPanel sampleOptions) {
        JPanel content = new JPanel(new BorderLayout(0, UiTokens.SPACE_4));
        content.setBackground(ThemeColors.canvas());
        content.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        JLabel title = new JLabel("Parameterize this");
        title.setFont(UiTokens.title(title.getFont()));
        JPanel header =
                column(
                        title,
                        helper("Turn hard-coded values into CSV variables."));
        content.add(header, BorderLayout.NORTH);
        JScrollPane preview = new JScrollPane(table);
        preview.setBorder(BorderFactory.createLineBorder(ThemeColors.separator()));
        preview.getViewport().setBackground(ThemeColors.surface());
        content.add(empty ? emptyState() : preview, BorderLayout.CENTER);
        JPanel form =
                column(
                        field(
                                "CSV file",
                                filename,
                                "<html>Relative to the saved .jmx directory.<br>Unsaved plans need an absolute path.</html>"));
        JPanel options = new JPanel(new GridLayout(1, 2, 16, 0));
        options.setOpaque(false);
        options.setAlignmentX(LEFT_ALIGNMENT);
        options.add(field("Delimiter", delimiter, "One character, usually a comma."));
        options.add(field("Sharing mode", sharing, "Reused CSV settings stay unchanged."));
        form.add(options);
        JPanel eof = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        eof.setOpaque(false);
        eof.setAlignmentX(LEFT_ALIGNMENT);
        recycle.setOpaque(false);
        stop.setOpaque(false);
        recycle.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 20));
        eof.add(recycle);
        eof.add(stop);
        form.add(eof);
        if (sampleOptions != null) {
            form.add(sampleOptions);
        }
        JLabel note = helper("Ctrl+Z in Feather Wand chat restores the plan, not the CSV file.");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(cancel);
        buttons.add(apply);
        error.setForeground(ThemeColors.error());
        JPanel bottom = new JPanel(new BorderLayout(0, 8));
        bottom.setOpaque(false);
        bottom.add(form, BorderLayout.NORTH);
        JPanel status = column(note, error, buttons);
        bottom.add(status, BorderLayout.SOUTH);
        content.add(bottom, BorderLayout.SOUTH);
        return content;
    }

    static String sampleTooltip(boolean configured) {
        return configured ? "Only column names and locally inferred types are sent."
                : "Configure a supported AI provider in Feather Wand to generate sample rows.";
    }

    private JPanel sampleOptions() {
        askAi.setOpaque(false);
        askAi.setEnabled(aiCall != null && !candidates.isEmpty());
        askAi.setToolTipText(sampleTooltip(aiCall != null));
        sampleCount.setEnabled(false);
        askAi.addActionListener(event -> sampleCount.setEnabled(askAi.isSelected()));
        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        options.setOpaque(false);
        options.add(askAi);
        options.add(new JLabel("Rows"));
        options.add(ParameterDialogControls.field(sampleCount));
        return column(options, helper("<html>Optional AI: column names and types only. No current values or plan details.<br>"
                + "Reused CSV columns keep the first existing row's values in new sample rows.</html>"));
    }

    private void setBusy(boolean busy) {
        for (JComponent control : List.of(table, filename, delimiter, sharing, recycle, stop)) {
            control.setEnabled(!busy);
        }
        applyButton.setEnabled(!busy && !candidates.isEmpty());
        askAi.setEnabled(!busy && aiCall != null && !candidates.isEmpty());
        sampleCount.setEnabled(!busy && askAi.isSelected());
    }

    @Override
    public void dispose() {
        if (committing) {
            return;
        }
        closed = true;
        if (sampleRequest != null) {
            sampleRequest.cancel();
        }
        super.dispose();
    }

    static void styleTable(JTable table) {
        table.setRowHeight(Math.max(32, table.getFontMetrics(table.getFont()).getHeight() + 14));
        table.setBackground(ThemeColors.surface());
        table.setForeground(javax.swing.UIManager.getColor("Label.foreground"));
        table.setSelectionBackground(ThemeColors.selectedBackground());
        table.setSelectionForeground(table.getForeground());
        table.setGridColor(ThemeColors.separator());
        table.setShowVerticalLines(false);
        table.setIntercellSpacing(new Dimension(12, 1));
        table.getTableHeader().setFont(UiTokens.label(table.getFont()));
        table.getTableHeader().setBackground(ThemeColors.subtleSurface());
        table.getTableHeader()
                .setForeground(ThemeColors.secondaryText(ThemeColors.subtleSurface()));
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(42);
        table.getColumnModel().getColumn(1).setPreferredWidth(250);
        table.getColumnModel().getColumn(2).setPreferredWidth(200);
        table.getColumnModel().getColumn(3).setPreferredWidth(150);
        table.getColumnModel().getColumn(4).setPreferredWidth(100);
    }

    static JPanel emptyState() {
        JLabel title = new JLabel("No hard-coded values found");
        title.setFont(UiTokens.heading(title.getFont()));
        JPanel panel =
                column(
                        title,
                        helper("Try a scope with HTTP arguments, JSON values or custom headers."),
                        helper(
                                "Values shorter than four characters and existing variables are"
                                        + " left alone."));
        panel.setBorder(BorderFactory.createEmptyBorder(24, 16, 24, 16));
        return panel;
    }

    private static JLabel helper(String text) {
        JLabel label = new JLabel(text);
        label.setFont(UiTokens.caption(label.getFont()));
        label.setForeground(ThemeColors.secondaryText(ThemeColors.canvas()));
        return label;
    }

    private static JPanel field(String label, JComponent control, String hint) {
        JLabel heading = new JLabel(label);
        heading.setFont(UiTokens.label(heading.getFont()));
        JPanel panel = column(heading, ParameterDialogControls.field(control), helper(hint));
        panel.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));
        return panel;
    }

    private static JPanel column(JComponent... components) {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        for (JComponent component : components) {
            component.setAlignmentX(LEFT_ALIGNMENT);
            panel.add(component);
        }
        return panel;
    }

    static void show(GuiPackage gui, JMeterTreeNode scope) {
        new ParameterizeThisDialog(gui, scope).setVisible(true);
    }

    private void apply() {
        try {
            if (table.isEditing()) {
                table.getCellEditor().stopCellEditing();
            }
            String file = filename.getText().trim();
            if (file.isEmpty()) {
                throw new IllegalArgumentException("Choose a CSV file path.");
            }
            if (delimiter.getText().length() != 1 || "\r\n\"".contains(delimiter.getText())) {
                throw new IllegalArgumentException(
                        "Use one delimiter character, not a quote or newline.");
            }
            Path path = Path.of(file);
            String plan = gui.getTestPlanFile();
            if ((plan == null || plan.isBlank()) && !path.isAbsolute()) {
                throw new IllegalArgumentException(
                        "Save the plan first or choose an absolute CSV path.");
            }
            Path base =
                    plan == null || plan.isBlank()
                            ? Path.of(FileServer.getFileServer().getBaseDir())
                            : Path.of(plan).toAbsolutePath().getParent();
            path = base.resolve(path).toAbsolutePath().normalize();
            ParameterPlacement.validateTarget(path, plan);
            CSVDataSet reuse =
                    ParameterPlacement.reusable(
                            ParameterPlacement.threadGroupOf(scope), base, file);
            List<String> oldNames =
                    reuse == null
                            ? List.of()
                            : reuse.getPropertyAsString("variableNames").isBlank()
                                    ? ParameterCsv.header(path, delimiter.getText().charAt(0),
                                            reuse.getPropertyAsBoolean("quotedData"))
                                    : Arrays.asList(reuse.getPropertyAsString("variableNames").split(",", -1));
            if (reuse != null
                    && (!delimiter.getText().equals(reuse.getPropertyAsString("delimiter", ","))
                            || !ParameterPlacement.utf8Compatible(reuse))) {
                throw new IllegalArgumentException(
                        "Existing CSV config must use the same delimiter and UTF-8 encoding."
                                + " A blank encoding is allowed only when the platform default is UTF-8.");
            }
            List<ParameterScanner.Candidate> chosen = new ArrayList<>();
            List<String> names = new ArrayList<>();
            List<String> values = new ArrayList<>();
            Set<String> used = ParameterNames.used(scope);
            used.addAll(oldNames);
            for (int i = 0; i < candidates.size(); i++) {
                if (!Boolean.TRUE.equals(rows.getValueAt(i, 0))) {
                    continue;
                }
                ParameterScanner.Candidate c = candidates.get(i);
                String name = String.valueOf(rows.getValueAt(i, 3)).trim();
                if (!ParameterNames.valid(name, used)) {
                    throw new IllegalArgumentException(
                            "Variable names must be valid, non-empty and unique, including existing"
                                    + " CSV columns.");
                }
                c.variable = name;
                chosen.add(c);
                names.add(name);
                values.add(c.value);
            }
            if (chosen.isEmpty()) {
                throw new IllegalArgumentException("Select at least one value.");
            }
            if (Files.exists(path)
                    && JOptionPane.showConfirmDialog(
                                    this,
                                    (reuse == null
                                                    ? "Replace existing CSV file?\n"
                                                    : "Append columns to existing CSV file?\n")
                                            + path,
                                    "Confirm CSV change",
                                    JOptionPane.YES_NO_OPTION)
                            != JOptionPane.YES_OPTION) {
                return;
            }
            gui.updateCurrentNode();
            Path target = path;
            if (askAi.isSelected() && aiCall != null) {
                List<ParameterSampleRows.Column> columns = new ArrayList<>();
                for (int i = 0; i < names.size(); i++) {
                    columns.add(ParameterSampleRows.column(names.get(i), values.get(i)));
                }
                int count = (Integer) sampleCount.getValue();
                String prompt = ParameterSampleRows.prompt(columns, count);
                setBusy(true);
                error.setText("Generating sample rows. Cancel leaves the plan and CSV unchanged.");
                Telemetry.record(TelemetryFeature.PARAM_AI_USED);
                sampleRequest = new ParameterSampleRequest(aiCall, prompt, count, List.copyOf(columns), 60_000,
                        samples -> commit(target, reuse, file, chosen, names, values, oldNames, samples),
                        message -> {
                            setBusy(false);
                            error.setText(message);
                        });
                sampleRequest.start();
            } else {
                commit(target, reuse, file, chosen, names, values, oldNames, List.of());
            }
        } catch (Exception exception) {
            error.setText(exception.getMessage());
        }
    }

    private void commit(Path target, CSVDataSet reuse, String file,
            List<ParameterScanner.Candidate> chosen, List<String> names, List<String> values,
            List<String> oldNames, List<List<String>> samples) {
        setBusy(true);
        committing = true;
        cancelButton.setEnabled(false);
        error.setText("Applying variables...");
        String separator = delimiter.getText();
        boolean recycleValue = recycle.isSelected();
        boolean stopValue = stop.isSelected();
        String shareMode = new String[] {"shareMode.all", "shareMode.group", "shareMode.thread"}
                [sharing.getSelectedIndex()];
        boolean header = reuse == null || reuse.getPropertyAsString("variableNames").isBlank()
                || reuse.getPropertyAsBoolean("ignoreFirstLine");
        boolean quotedData = reuse == null || reuse.getPropertyAsBoolean("quotedData");
        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() throws Exception {
                return ParameterTransaction.apply(
                            target,
                            () -> {
                                ParameterCsv.write(
                                        target,
                                        separator.charAt(0),
                                        names,
                                        values,
                                        oldNames,
                                        header, quotedData, samples);
                                return 0;
                            },
                            () -> {
                                java.util.concurrent.atomic.AtomicInteger changed =
                                        new java.util.concurrent.atomic.AtomicInteger();
                                java.util.concurrent.atomic.AtomicReference<Exception> failure =
                                        new java.util.concurrent.atomic.AtomicReference<>();
                                EdtExecutor.swing().run(() -> {
                                    try {
                                        changed.set(ParameterPlacement.apply(
                                                gui.getTreeModel(),
                                                scope,
                                                reuse,
                                                file,
                                                separator,
                                                recycleValue,
                                                stopValue,
                                                shareMode,
                                                chosen,
                                                oldNames));
                                    } catch (Exception e) {
                                        failure.set(e);
                                    }
                                });
                                if (failure.get() != null) {
                                    throw failure.get();
                                }
                                return changed.get();
                            });
            }

            @Override
            protected void done() {
                committing = false;
                cancelButton.setEnabled(true);
                try {
                    int changed = get();
                    Telemetry.record(TelemetryFeature.PARAM_APPLIED);
                    ParameterDialogControls.parameterizationApplied();
                    dispose();
                    JOptionPane.showMessageDialog(
                            gui.getMainFrame(),
                            "Variables: " + String.join(", ", names)
                                    + "\nReplaced occurrences: " + changed
                                    + "\nCSV file: " + target
                                    + "\nAI sample rows: " + samples.size()
                                    + "\nCtrl+Z in Feather Wand chat restores the plan, not the CSV file."
                                    + (reuse == null ? "" : "\nExisting quoting, encoding, delimiter, EOF and sharing settings were preserved. The CSV header is skipped."),
                            "Parameterize this",
                            JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception exception) {
                    setBusy(false);
                    error.setText("Could not apply variables. " + (exception.getCause() == null
                            ? exception.getMessage() : exception.getCause().getMessage()));
                }
            }
        }.execute();
    }
}
