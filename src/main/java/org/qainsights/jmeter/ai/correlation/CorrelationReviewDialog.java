package org.qainsights.jmeter.ai.correlation;

import org.apache.jmeter.gui.GuiPackage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.WindowConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public class CorrelationReviewDialog extends JDialog {
    private static final Logger log = LoggerFactory.getLogger(CorrelationReviewDialog.class);
    private final CandidateTableModel tableModel;
    private final CorrelationExtractorInjector injector;
    private final CorrelationCsvExporter csvExporter;

    public CorrelationReviewDialog(Frame owner, CorrelationAnalysisResult result) {
        super(owner, "Feather Wand Correlation Review", true);
        Objects.requireNonNull(result, "result");
        this.tableModel = new CandidateTableModel(result.getCandidates());
        this.injector = new CorrelationExtractorInjector();
        this.csvExporter = new CorrelationCsvExporter();
        init(result);
    }

    private void init(CorrelationAnalysisResult result) {
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));
        JPanel summaryPanel = new JPanel(new BorderLayout());
        summaryPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        summaryPanel.add(new JLabel("Samples parsed: " + result.getSamples().size() + " | Candidates found: " + result.getCandidates().size()), BorderLayout.WEST);
        add(summaryPanel, BorderLayout.NORTH);

        JTable table = new JTable(tableModel);
        table.setAutoCreateRowSorter(true);
        table.setFillsViewportHeight(true);
        configureColumns(table);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(createActionPanel(), BorderLayout.SOUTH);
        setSize(1100, 600);
        setLocationRelativeTo(getOwner());
    }

    private JPanel createActionPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton approveAllButton = new JButton("Approve All");
        JButton rejectAllButton = new JButton("Reject All");
        JButton exportButton = new JButton("Export as CSV");
        JButton applyButton = new JButton("Apply Approved");
        JButton closeButton = new JButton("Close");

        approveAllButton.addActionListener(event -> tableModel.setAllStatuses(CandidateStatus.APPROVED));
        rejectAllButton.addActionListener(event -> tableModel.setAllStatuses(CandidateStatus.REJECTED));
        exportButton.addActionListener(event -> exportCsv());
        applyButton.addActionListener(event -> applyApproved());
        closeButton.addActionListener(event -> dispose());

        panel.add(approveAllButton);
        panel.add(rejectAllButton);
        panel.add(exportButton);
        panel.add(applyButton);
        panel.add(closeButton);
        return panel;
    }

    private void configureColumns(JTable table) {
        int[] widths = {180, 140, 100, 280, 180, 220, 90};
        for (int index = 0; index < widths.length; index++) {
            TableColumn column = table.getColumnModel().getColumn(index);
            column.setPreferredWidth(widths[index]);
        }
        table.getColumnModel().getColumn(2).setCellEditor(new javax.swing.DefaultCellEditor(new javax.swing.JComboBox<>(ExtractorType.values())));
        table.getColumnModel().getColumn(6).setCellEditor(new javax.swing.DefaultCellEditor(new javax.swing.JComboBox<>(CandidateStatus.values())));
        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(SwingConstants.CENTER);
        table.getColumnModel().getColumn(2).setCellRenderer(centerRenderer);
        table.getColumnModel().getColumn(6).setCellRenderer(centerRenderer);
    }

    private void applyApproved() {
        try {
            int count = injector.applyApproved(tableModel.getCandidates());
            JOptionPane.showMessageDialog(this, "Applied " + count + " extractor(s) to the active test plan.", "Correlation", JOptionPane.INFORMATION_MESSAGE);
            GuiPackage guiPackage = GuiPackage.getInstance();
            if (guiPackage != null && guiPackage.getMainFrame() != null) {
                guiPackage.getMainFrame().repaint();
            }
        } catch (Exception e) {
            log.error("Failed to apply correlation extractors", e);
            JOptionPane.showMessageDialog(this, e.getMessage(), "Correlation Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void exportCsv() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export Correlation Candidates");
        chooser.setSelectedFile(new java.io.File("correlation-candidates.csv"));
        int result = chooser.showSaveDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path path = chooser.getSelectedFile().toPath();
        try {
            csvExporter.export(tableModel.getCandidates(), path);
            JOptionPane.showMessageDialog(this, "Exported candidates to " + path, "Correlation", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
            log.error("Failed to export correlation candidates", e);
            JOptionPane.showMessageDialog(this, e.getMessage(), "Export Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static final class CandidateTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Sampler", "Variable Name", "Extractor Type", "Expression", "Source Location", "Request Location", "Status"};
        private final List<CorrelationCandidate> candidates;

        private CandidateTableModel(List<CorrelationCandidate> candidates) {
            this.candidates = Objects.requireNonNull(candidates, "candidates");
        }

        private List<CorrelationCandidate> getCandidates() {
            return candidates;
        }

        @Override
        public int getRowCount() {
            return candidates.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            if (columnIndex == 2) {
                return ExtractorType.class;
            }
            if (columnIndex == 6) {
                return CandidateStatus.class;
            }
            return String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 1 || columnIndex == 2 || columnIndex == 3 || columnIndex == 6;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            CorrelationCandidate candidate = candidates.get(rowIndex);
            ExtractorSuggestion suggestion = candidate.getSuggestion();
            switch (columnIndex) {
                case 0:
                    return candidate.getSourceResponse().getLabel();
                case 1:
                    return suggestion.getVariableName();
                case 2:
                    return suggestion.getExtractorType();
                case 3:
                    return suggestion.getExpression();
                case 4:
                    return candidate.getResponseLocation();
                case 5:
                    return candidate.getRequestLocation();
                case 6:
                    return candidate.getStatus();
                default:
                    return "";
            }
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            CorrelationCandidate candidate = candidates.get(rowIndex);
            ExtractorSuggestion suggestion = candidate.getSuggestion();
            if (columnIndex == 1) {
                suggestion.setVariableName(String.valueOf(value));
            } else if (columnIndex == 2 && value instanceof ExtractorType) {
                suggestion.setExtractorType((ExtractorType) value);
            } else if (columnIndex == 3) {
                suggestion.setExpression(String.valueOf(value));
            } else if (columnIndex == 6 && value instanceof CandidateStatus) {
                candidate.setStatus((CandidateStatus) value);
            }
            fireTableRowsUpdated(rowIndex, rowIndex);
        }

        private void setAllStatuses(CandidateStatus status) {
            for (CorrelationCandidate candidate : candidates) {
                candidate.setStatus(status);
            }
            fireTableDataChanged();
        }
    }
}
