package org.qainsights.jmeter.ai.correlation;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class CorrelationReviewDialog extends JDialog {

    private static final Logger log = LoggerFactory.getLogger(CorrelationReviewDialog.class);

    private List<CorrelationCandidate> candidates = new ArrayList<>();
    private CandidateTableModel tableModel;
    private JTable table;
    private JLabel statusLabel;
    private JLabel detailLabel;
    private JLabel timerLabel;
    private JButton correlateBtn;
    private JButton loadJtlBtn;
    private JButton saveJtlBtn;
    private JButton selectAllBtn;
    private JButton deselectAllBtn;
    private JButton applyBtn;
    private JProgressBar progressBar;
    private javax.swing.Timer elapsedTimer;
    private long startTime;
    private boolean timeoutWarned;
    private CorrelationEngine lastEngine;

    public CorrelationReviewDialog(Frame owner) {
        super(owner, "Correlation Studio", false);
        setLayout(new BorderLayout());
        setSize(1000, 550);
        setLocationRelativeTo(owner);

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        correlateBtn = new JButton("Run & Correlate");
        loadJtlBtn = new JButton("Load JTL");
        saveJtlBtn = new JButton("Save JTL");
        saveJtlBtn.setVisible(false);
        progressBar = new JProgressBar();
        progressBar.setIndeterminate(false);
        progressBar.setVisible(false);
        progressBar.setPreferredSize(new Dimension(150, 22));
        timerLabel = new JLabel("");
        timerLabel.setVisible(false);
        timerLabel.setPreferredSize(new Dimension(60, 22));

        correlateBtn.addActionListener(e -> runCorrelation());
        loadJtlBtn.addActionListener(e -> loadJtl());
        saveJtlBtn.addActionListener(e -> saveJtl());

        top.add(correlateBtn);
        top.add(loadJtlBtn);
        top.add(saveJtlBtn);
        top.add(progressBar);
        top.add(timerLabel);
        add(top, BorderLayout.NORTH);

        tableModel = new CandidateTableModel();
        table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setMaxWidth(40);
        table.getColumnModel().getColumn(1).setPreferredWidth(200);
        table.getColumnModel().getColumn(2).setPreferredWidth(100);
        table.getColumnModel().getColumn(3).setPreferredWidth(60);
        table.getColumnModel().getColumn(4).setPreferredWidth(300);
        table.getColumnModel().getColumn(5).setPreferredWidth(150);
        table.getColumnModel().getColumn(6).setPreferredWidth(60);
        table.setRowHeight(24);

        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            updateDetail();
        });

        JPopupMenu rowMenu = new JPopupMenu();
        JMenuItem approveItem = new JMenuItem("Approve");
        JMenuItem rejectItem = new JMenuItem("Reject");
        JMenuItem goToItem = new JMenuItem("Go to");
        approveItem.addActionListener(e -> setRowStatus(CorrelationCandidate.Status.APPROVED));
        rejectItem.addActionListener(e -> setRowStatus(CorrelationCandidate.Status.PENDING));
        goToItem.addActionListener(e -> goToSampler());
        rowMenu.add(approveItem);
        rowMenu.add(rejectItem);
        rowMenu.add(goToItem);
        table.setComponentPopupMenu(rowMenu);

        add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusLabel = new JLabel(" Click 'Run & Correlate' to replay the test plan, or 'Load JTL' to use an existing results file.");
        detailLabel = new JLabel("");
        detailLabel.setForeground(new Color(0, 100, 0));
        statusPanel.add(statusLabel, BorderLayout.NORTH);
        statusPanel.add(detailLabel, BorderLayout.SOUTH);
        bottom.add(statusPanel, BorderLayout.WEST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        selectAllBtn = new JButton("Select All");
        deselectAllBtn = new JButton("Deselect All");
        applyBtn = new JButton("Apply Selected");

        selectAllBtn.addActionListener(e -> {
            setAllApproved(true);
        });
        deselectAllBtn.addActionListener(e -> {
            setAllApproved(false);
        });
        applyBtn.addActionListener(e -> applyCorrelation());

        buttons.add(selectAllBtn);
        buttons.add(deselectAllBtn);
        buttons.add(applyBtn);
        bottom.add(buttons, BorderLayout.EAST);
        add(bottom, BorderLayout.SOUTH);
    }

    private void runCorrelation() {
        startTimer();
        new SwingWorker<List<CorrelationCandidate>, Void>() {
            @Override
            protected List<CorrelationCandidate> doInBackground() throws Exception {
                lastEngine = new CorrelationEngine();
                return lastEngine.runAndCorrelate();
            }

            @Override
            protected void done() {
                stopTimer();
                try {
                    List<CorrelationCandidate> result = get();
                    updateResults(result);
                    if (!result.isEmpty()) saveJtlBtn.setVisible(true);
                } catch (Exception ex) {
                    log.error("Correlation failed", ex);
                    JOptionPane.showMessageDialog(CorrelationReviewDialog.this,
                            "Correlation failed: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void loadJtl() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("JTL Files (*.jtl)", "jtl"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File file = chooser.getSelectedFile();
        startTimer();
        new SwingWorker<List<CorrelationCandidate>, Void>() {
            @Override
            protected List<CorrelationCandidate> doInBackground() throws Exception {
                lastEngine = new CorrelationEngine();
                return lastEngine.correlateFromJtl(file.toPath());
            }

            @Override
            protected void done() {
                stopTimer();
                try {
                    updateResults(get());
                } catch (Exception ex) {
                    log.error("JTL correlation failed", ex);
                    JOptionPane.showMessageDialog(CorrelationReviewDialog.this,
                            "Failed to process JTL: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void saveJtl() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("JTL Files (*.jtl)", "jtl"));
        chooser.setSelectedFile(new File("correlation-results.jtl"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        JOptionPane.showMessageDialog(this,
                "To save the JTL, run your test plan in JMeter with a 'View Results Tree'\n" +
                        "listener configured to save XML format, or use command-line:\n" +
                        "jmeter -n -t test.jmx -l results.jtl",
                "Save JTL", JOptionPane.INFORMATION_MESSAGE);
    }

    private void updateResults(List<CorrelationCandidate> newCandidates) {
        this.candidates = newCandidates;
        tableModel.setData(newCandidates);
        statusLabel.setText(newCandidates.isEmpty()
                ? " No correlation candidates found. Try adjusting patterns in jmeter-ai-sample.properties."
                : " " + newCandidates.size() + " candidates found. Review and approve, then click 'Apply Approved'.");
    }

    private void startTimer() {
        setRunning(true);
        startTime = System.currentTimeMillis();
        timeoutWarned = false;
        timerLabel.setVisible(true);
        timerLabel.setForeground(Color.BLACK);
        if (elapsedTimer != null) elapsedTimer.stop();
        elapsedTimer = new javax.swing.Timer(500, e -> {
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            timerLabel.setText(String.format("%d:%02d", elapsed / 60, elapsed % 60));
            if (elapsed >= 120 && !timeoutWarned) {
                timeoutWarned = true;
                timerLabel.setForeground(Color.RED);
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(CorrelationReviewDialog.this,
                                "Execution is taking longer than expected (2+ minutes).\n\n" +
                                        "Tip: Cancel and instead run your test plan directly in JMeter,\n" +
                                        "save results as XML JTL, then use 'Load JTL' to correlate.",
                                "Still Running", JOptionPane.WARNING_MESSAGE));
            }
        });
        elapsedTimer.start();
    }

    private void stopTimer() {
        setRunning(false);
        if (elapsedTimer != null) elapsedTimer.stop();
        timerLabel.setVisible(false);
    }

    private void setRunning(boolean running) {
        correlateBtn.setEnabled(!running);
        loadJtlBtn.setEnabled(!running);
        saveJtlBtn.setEnabled(!running);
        progressBar.setIndeterminate(running);
        progressBar.setVisible(running);
    }

    private void setAllApproved(boolean approved) {
        CorrelationCandidate.Status st = approved ? CorrelationCandidate.Status.APPROVED : CorrelationCandidate.Status.PENDING;
        for (CorrelationCandidate c : candidates) c.setStatus(st);
        tableModel.fireTableDataChanged();
    }

    private void setRowStatus(CorrelationCandidate.Status status) {
        int row = table.getSelectedRow();
        if (row >= 0) {
            candidates.get(row).setStatus(status);
            tableModel.fireTableRowsUpdated(row, row);
        }
    }

    private void updateDetail() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= candidates.size()) {
            detailLabel.setText("");
            return;
        }
        CorrelationCandidate c = candidates.get(row);
        List<String> targets = c.getTargetSamplerNames();
        if (targets.isEmpty()) {
            detailLabel.setText("  ✗ Not used in any subsequent sampler — will be skipped");
            detailLabel.setForeground(Color.RED);
        } else {
            detailLabel.setText("  ✔ Replaces in: " + String.join(", ", targets));
            detailLabel.setForeground(new Color(0, 100, 0));
        }
    }

    private void goToSampler() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= candidates.size()) {
            return;
        }
        
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null) {
            log.warn("GuiPackage not available");
            return;
        }
        
        CorrelationCandidate candidate = candidates.get(row);
        String samplerName = candidate.getSourceSamplerName();
        
        JMeterTreeNode root = (JMeterTreeNode) guiPackage.getTreeModel().getRoot();
        JMeterTreeNode targetNode = findNode(root, samplerName);
        
        if (targetNode != null) {
            JTree jTree = guiPackage.getTreeListener().getJTree();
            TreePath path = new TreePath(targetNode.getPath());
            
            if (targetNode.getParent() instanceof JMeterTreeNode) {
                jTree.expandPath(new TreePath(((JMeterTreeNode) targetNode.getParent()).getPath()));
            }
            
            jTree.setSelectionPath(path);
            jTree.scrollPathToVisible(path);
            
            guiPackage.getMainFrame().toFront();
            guiPackage.getMainFrame().requestFocus();
        } else {
            log.warn("Sampler '{}' not found in test plan tree", samplerName);
            JOptionPane.showMessageDialog(this,
                    "Sampler '" + samplerName + "' not found in the test plan.",
                    "Navigation Failed", JOptionPane.WARNING_MESSAGE);
        }
    }

    private JMeterTreeNode findNode(JMeterTreeNode node, String name) {
        if (node.getName().equals(name)) return node;
        if (name.contains("/") && node.getName().equals(name.substring(name.lastIndexOf('/') + 1))) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            TreeNode child = node.getChildAt(i);
            if (child instanceof JMeterTreeNode) {
                JMeterTreeNode found = findNode((JMeterTreeNode) child, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void applyCorrelation() {
        CorrelationInjector injector = new CorrelationInjector();
        int count = injector.apply(candidates);
        JOptionPane.showMessageDialog(this, "Applied " + count + " correlation extractors to the test plan.", "Correlation Applied", JOptionPane.INFORMATION_MESSAGE);
    }

    private static class CandidateTableModel extends AbstractTableModel {
        private final String[] cols = {"", "Sampler", "Variable", "Type", "Expression", "Source", "Used In"};
        private List<CorrelationCandidate> data = new ArrayList<>();

        void setData(List<CorrelationCandidate> d) {
            this.data = d;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return data.size();
        }

        @Override
        public int getColumnCount() {
            return cols.length;
        }

        @Override
        public String getColumnName(int c) {
            return cols[c];
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return c == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return c == 0;
        }

        @Override
        public void setValueAt(Object v, int r, int c) {
            if (c == 0 && v instanceof Boolean) {
                data.get(r).setStatus((Boolean) v ? CorrelationCandidate.Status.APPROVED : CorrelationCandidate.Status.PENDING);
                fireTableCellUpdated(r, c);
            }
        }

        @Override
        public Object getValueAt(int r, int c) {
            CorrelationCandidate cc = data.get(r);
            switch (c) {
                case 0:
                    return cc.getStatus() == CorrelationCandidate.Status.APPROVED;
                case 1:
                    return cc.getSourceSamplerName();
                case 2:
                    return cc.getVariableName();
                case 3:
                    return cc.getExtractorType();
                case 4:
                    return cc.getExtractionPattern();
                case 5:
                    return cc.getSourceLocation();
                case 6:
                    return cc.getUsageCount();
                default:
                    return "";
            }
        }
    }
}
