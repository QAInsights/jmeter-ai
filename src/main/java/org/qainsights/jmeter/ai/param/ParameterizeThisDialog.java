package org.qainsights.jmeter.ai.param;

import org.apache.jmeter.config.CSVDataSet;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.services.FileServer;
import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.telemetry.Telemetry;
import org.qainsights.jmeter.ai.telemetry.TelemetryFeature;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class ParameterizeThisDialog extends JDialog {
    private final List<ParameterScanner.Candidate> candidates;
    private final DefaultTableModel rows;
    private final JTable table;
    private final JTextField filename;
    private final JTextField delimiter = new JTextField(",", 3);
    private final JCheckBox recycle = new JCheckBox("Recycle on EOF", true);
    private final JCheckBox stop = new JCheckBox("Stop thread on EOF", false);
    private final JComboBox<String> sharing = new JComboBox<>(new String[] {"All threads", "Current thread group", "Current thread"});
    private final JLabel error = new JLabel(" ");
    private final GuiPackage gui;
    private final JMeterTreeNode scope;

    private ParameterizeThisDialog(GuiPackage gui, JMeterTreeNode scope) {
        super(gui.getMainFrame(), "Parameterize this", true);
        this.gui = gui;
        this.scope = scope;
        Telemetry.record(TelemetryFeature.PARAM_OPENED);
        candidates = ParameterScanner.scan(scope);
        rows = new DefaultTableModel(new String[] {"Use", "Field path", "Current value", "Variable name", "Occurrences"}, 0) {
            @Override public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : column == 4 ? Integer.class : String.class; }
            @Override public boolean isCellEditable(int row, int column) { return column == 0 || column == 3; }
        };
        for (ParameterScanner.Candidate c : candidates) { rows.addRow(new Object[] {true, c.path, c.value, c.variable, c.occurrences}); }
        table = new JTable(rows);
        table.setRowHeight(26);
        table.setPreferredScrollableViewportSize(new Dimension(900, 300));
        String group = ParameterPlacement.threadGroupOf(scope).getName().replaceAll("[^A-Za-z0-9_-]", "_");
        filename = new JTextField("data/" + (group.isEmpty() ? "users" : group) + ".csv", 36);
        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));
        JLabel title = new JLabel("Select hard-coded values to replace with CSV variables. No AI call.");
        title.setForeground(ThemeColors.secondaryText());
        content.add(title, BorderLayout.NORTH);
        content.add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel form = new JPanel(new GridLayout(0, 2, 8, 8));
        form.add(new JLabel("CSV file (relative to the saved .jmx directory)")); form.add(filename);
        form.add(new JLabel("Delimiter (one character)")); form.add(delimiter);
        form.add(recycle); form.add(stop);
        form.add(new JLabel("Sharing mode")); form.add(sharing);
        if (gui.getTestPlanFile() == null || gui.getTestPlanFile().isBlank()) {
            Path absolute = Path.of(FileServer.getFileServer().getBaseDir()).resolve(filename.getText()).toAbsolutePath();
            filename.setText(absolute.toString());
        }
        JLabel note = new JLabel("Unsaved plans require an absolute CSV path. CSV files are not reverted by Ctrl+Z.");
        note.setForeground(ThemeColors.secondaryText());
        JButton apply = new JButton("Apply");
        apply.setEnabled(!candidates.isEmpty());
        apply.addActionListener(e -> apply());
        JButton cancel = new JButton("Cancel"); cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT)); buttons.add(cancel); buttons.add(apply);
        error.setForeground(ThemeColors.error());
        JPanel bottom = new JPanel(new BorderLayout(0, 8));
        bottom.add(form, BorderLayout.NORTH);
        JPanel status = new JPanel(new GridLayout(0, 1)); status.add(note); status.add(error); status.add(buttons);
        bottom.add(status, BorderLayout.SOUTH);
        content.add(bottom, BorderLayout.SOUTH);
        setContentPane(content); pack(); setLocationRelativeTo(gui.getMainFrame());
    }

    static void show(GuiPackage gui, JMeterTreeNode scope) { new ParameterizeThisDialog(gui, scope).setVisible(true); }

    private void apply() {
        try {
            if (table.isEditing()) { table.getCellEditor().stopCellEditing(); }
            String file = filename.getText().trim();
            if (file.isEmpty()) { throw new IllegalArgumentException("Choose a CSV file path."); }
            if (delimiter.getText().length() != 1 || "\r\n\"".contains(delimiter.getText())) {
                throw new IllegalArgumentException("Use one delimiter character, not a quote or newline.");
            }
            Path path = Path.of(file);
            String plan = gui.getTestPlanFile();
            if ((plan == null || plan.isBlank()) && !path.isAbsolute()) {
                throw new IllegalArgumentException("Save the plan first or choose an absolute CSV path.");
            }
            Path base = plan == null || plan.isBlank() ? Path.of(FileServer.getFileServer().getBaseDir())
                    : Path.of(plan).toAbsolutePath().getParent();
            path = base.resolve(path).toAbsolutePath().normalize();
            CSVDataSet reuse = ParameterPlacement.reusable(ParameterPlacement.threadGroupOf(scope), base, file);
            List<String> oldNames = reuse == null ? List.of() : reuse.getVariableNames().isBlank()
                    ? ParameterCsv.header(path, delimiter.getText().charAt(0))
                    : Arrays.asList(reuse.getVariableNames().split(",", -1));
            if (reuse != null && (!delimiter.getText().equals(reuse.getDelimiter())
                    || (!reuse.getFileEncoding().isBlank() && !"UTF-8".equalsIgnoreCase(reuse.getFileEncoding())))) {
                throw new IllegalArgumentException("Existing CSV config must use the same delimiter and UTF-8 encoding (or the default encoding).");
            }
            List<ParameterScanner.Candidate> chosen = new ArrayList<>();
            List<String> names = new ArrayList<>();
            List<String> values = new ArrayList<>();
            Set<String> used = new HashSet<>(oldNames);
            for (int i = 0; i < candidates.size(); i++) {
                if (!Boolean.TRUE.equals(rows.getValueAt(i, 0))) { continue; }
                ParameterScanner.Candidate c = candidates.get(i);
                String name = String.valueOf(rows.getValueAt(i, 3)).trim();
                if (!name.matches("[A-Za-z_][A-Za-z0-9_]*") || !used.add(name)) {
                    throw new IllegalArgumentException("Variable names must be valid, non-empty and unique, including existing CSV columns.");
                }
                c.variable = name; chosen.add(c); names.add(name); values.add(c.value);
            }
            if (chosen.isEmpty()) { throw new IllegalArgumentException("Select at least one value."); }
            if (Files.exists(path) && JOptionPane.showConfirmDialog(this,
                    (reuse == null ? "Replace existing CSV file?\n" : "Append columns to existing CSV file?\n") + path,
                    "Confirm CSV change", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) { return; }
            ParameterCsv.write(path, delimiter.getText().charAt(0), names, values, oldNames,
                    reuse == null || reuse.getVariableNames().isBlank() || reuse.isIgnoreFirstLine());
            gui.updateCurrentNode();
            int changed = ParameterPlacement.apply(gui.getTreeModel(), scope, reuse, file, delimiter.getText(),
                    recycle.isSelected(), stop.isSelected(), new String[] {"shareMode.all", "shareMode.group", "shareMode.thread"}[sharing.getSelectedIndex()], chosen, oldNames);
            gui.refreshCurrentGui();
            Telemetry.record(TelemetryFeature.PARAM_APPLIED);
            dispose();
            JOptionPane.showMessageDialog(gui.getMainFrame(), "Variables: " + String.join(", ", names)
                    + "\nReplaced properties: " + changed + "\nCSV file: " + path,
                    "Parameterize this", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception exception) {
            error.setText(exception.getMessage());
        }
    }
}
