package org.qainsights.jmeter.ai.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;

import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.gui.theme.UiTokens;
import org.qainsights.jmeter.ai.load.WorkloadPreflight;
import org.qainsights.jmeter.ai.load.WorkloadPreflight.Finding;

final class WorkloadPreflightPanel extends JPanel {
    private final QuietButton checkButton = new QuietButton("Check plan", QuietButton.Kind.OUTLINED).compact();
    private final QuietButton selectButton = new QuietButton("Select in plan", QuietButton.Kind.OUTLINED).compact();
    private final JTextArea status = textArea(2);
    private final JTextArea detail = textArea(5);
    private final JList<Finding> findings = new JList<>();
    private final JPanel results = new JPanel(new BorderLayout(0, UiTokens.SPACE_2));
    private final Supplier<WorkloadPreflight.Inspection> prepare;
    private SwingWorker<List<Finding>, Void> worker;
    private long requestId;
    private boolean available;
    private boolean checked;

    WorkloadPreflightPanel(Supplier<WorkloadPreflight.Inspection> prepare, Consumer<Finding> select) {
        super(new BorderLayout(0, UiTokens.SPACE_2));
        this.prepare = prepare;
        setOpaque(false);
        JPanel header = new JPanel(new BorderLayout(UiTokens.SPACE_2, 0));
        header.setOpaque(false);
        status.setText("Check enabled elements and inherited settings. No test run or AI call.");
        header.add(status, BorderLayout.CENTER);
        header.add(checkButton, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);
        findings.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        findings.setVisibleRowCount(4);
        findings.setFixedCellWidth(0);
        findings.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                           boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                if (!selected && value instanceof Finding finding) {
                    setForeground(finding.severity() == WorkloadPreflight.Severity.WARNING
                            ? ThemeColors.warning() : ThemeColors.secondaryText());
                }
                setBorder(BorderFactory.createEmptyBorder(UiTokens.SPACE_1, UiTokens.SPACE_2,
                        UiTokens.SPACE_1, UiTokens.SPACE_2));
                return this;
            }
        });
        findings.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                Finding finding = findings.getSelectedValue();
                detail.setText(finding == null ? "" : finding.elementPath() + "\n\n" + finding.detail());
                detail.setCaretPosition(0);
                selectButton.setEnabled(finding != null && checked);
            }
        });
        JScrollPane listScroll = new JScrollPane(findings);
        listScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        results.setOpaque(false);
        results.add(listScroll, BorderLayout.NORTH);
        JScrollPane detailScroll = new JScrollPane(detail);
        detailScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        detailScroll.setBorder(null);
        detailScroll.setOpaque(false);
        detailScroll.getViewport().setOpaque(false);
        results.add(detailScroll, BorderLayout.CENTER);
        JPanel actions = new JPanel(new BorderLayout());
        actions.setOpaque(false);
        JTextArea navigationNote = textArea(2);
        navigationNote.setText("Select in plan closes the designer without applying workload changes.");
        actions.add(navigationNote, BorderLayout.CENTER);
        actions.add(selectButton, BorderLayout.EAST);
        results.add(actions, BorderLayout.SOUTH);
        results.setVisible(false);
        add(results, BorderLayout.CENTER);
        selectButton.setEnabled(false);
        selectButton.addActionListener(event -> {
            Finding finding = findings.getSelectedValue();
            if (checked && finding != null) {
                select.accept(finding);
            }
        });
        checkButton.addActionListener(event -> check());
        refreshTheme();
    }

    void setAvailable(boolean available) {
        this.available = available;
        checkButton.setEnabled(available && worker == null);
        checkButton.setToolTipText(available ? "Read-only checks for the selected Thread Group"
                : "Enter a valid workload target first");
    }

    void invalidateResults() {
        requestId++;
        if (worker != null) {
            worker.cancel(true);
            worker = null;
        }
        if (checked) {
            status.setText("Workload changed. Check plan again to refresh the findings.");
        }
        checked = false;
        results.setVisible(false);
        selectButton.setEnabled(false);
        checkButton.setText("Check plan");
        checkButton.setEnabled(available);
        revalidate();
        repaint();
    }

    private void check() {
        if (!available || worker != null) {
            return;
        }
        WorkloadPreflight.Inspection inspection;
        try {
            inspection = prepare.get();
        } catch (RuntimeException e) {
            status.setText("Could not prepare the checks. Reopen the designer and try again.");
            return;
        }
        long id = ++requestId;
        checked = true;
        results.setVisible(false);
        selectButton.setEnabled(false);
        status.setText("Checking local file metadata and plan settings. No test is running.");
        checkButton.setText("Checking...");
        checkButton.setEnabled(false);
        worker = new SwingWorker<>() {
            @Override
            protected List<Finding> doInBackground() {
                return inspection.run();
            }

            @Override
            protected void done() {
                if (id != requestId) {
                    return;
                }
                worker = null;
                checkButton.setText("Check again");
                checkButton.setEnabled(available);
                try {
                    List<Finding> result = get();
                    findings.setListData(result.toArray(Finding[]::new));
                    long warnings = result.stream()
                            .filter(finding -> finding.severity() == WorkloadPreflight.Severity.WARNING).count();
                    status.setText(result.isEmpty()
                            ? "No findings in supported checks. Achieved load and generator health are not verified."
                            : warnings + " warning(s), " + (result.size() - warnings)
                                    + " not verified. Review before execution.");
                    results.setVisible(!result.isEmpty());
                    if (!result.isEmpty()) {
                        findings.setSelectedIndex(0);
                    }
                } catch (CancellationException e) {
                    status.setText("Check cancelled. Check plan again.");
                } catch (Exception e) {
                    status.setText("Check could not finish. No readiness verdict is available. Try again.");
                }
                revalidate();
                repaint();
            }
        };
        worker.execute();
    }

    void refreshTheme() {
        for (JTextArea area : List.of(status, detail)) {
            area.setFont(new javax.swing.JLabel().getFont());
            area.setForeground(ThemeColors.secondaryText());
        }
        findings.setBackground(ThemeColors.elevatedSurface());
        findings.setForeground(ThemeColors.foreground());
        findings.setFixedCellHeight(findings.getFontMetrics(findings.getFont()).getHeight() + UiTokens.SPACE_2);
    }

    private static JTextArea textArea(int rows) {
        JTextArea area = new JTextArea(rows, 16);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setForeground(ThemeColors.secondaryText());
        area.setMinimumSize(new Dimension(0, 0));
        area.setBorder(null);
        ((javax.swing.text.DefaultCaret) area.getCaret())
                .setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE);
        return area;
    }
}
