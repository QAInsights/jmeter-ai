package org.qainsights.jmeter.ai.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;

import org.qainsights.jmeter.ai.agent.FailureTriage;
import org.qainsights.jmeter.ai.agent.TriageNotice;
import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.gui.theme.UiTokens;

/**
 * Expandable transcript card for one Jev failure triage: header + summary line, an
 * advisory caption naming the chat model that still owns the diagnosis, and (when
 * expanded) the failures grouped by Jev-assigned root-cause category.
 */
class JevTriageCard extends JPanel {

    private final TriageNotice notice;
    private final JLabel headerLabel = new JLabel();
    private final JLabel summaryLabel = new JLabel();
    private final JTextArea advisoryText = new JTextArea();
    private final JTextArea detailsArea = new JTextArea();
    private boolean expanded;

    JevTriageCard(TriageNotice notice, String modelId) {
        super(new BorderLayout());
        this.notice = notice == null
                ? new TriageNotice(0, 0, "", List.of())
                : notice;
        setOpaque(true);
        setAlignmentX(Component.LEFT_ALIGNMENT);
        configureLabels();
        configureDetails();
        add(content(), BorderLayout.CENTER);
        summaryLabel.setText(summary());
        advisoryText.setText(advisory(modelId));
        setExpanded(false);
        applyTheme();
    }

    private void configureLabels() {
        headerLabel.setFont(UiTokens.label(headerLabel.getFont()));
        headerLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        headerLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        headerLabel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                setExpanded(!expanded);
            }
        });
        summaryLabel.setFont(UiTokens.body(summaryLabel.getFont()));
        summaryLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        configureWrap(advisoryText, UiTokens.caption(advisoryText.getFont()));
    }

    private void configureDetails() {
        configureWrap(detailsArea, new Font(Font.MONOSPACED, Font.PLAIN, 11));
        detailsArea.setText(details());
        detailsArea.setVisible(false);
    }

    private static void configureWrap(JTextArea area, Font font) {
        area.setEditable(false);
        area.setOpaque(false);
        area.setBorder(BorderFactory.createEmptyBorder());
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setAlignmentX(Component.LEFT_ALIGNMENT);
        area.setFont(font);
    }

    private JPanel content() {
        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.add(headerLabel);
        content.add(summaryLabel);
        content.add(advisoryText);
        content.add(detailsArea);
        return content;
    }

    void setExpanded(boolean expanded) {
        this.expanded = expanded;
        detailsArea.setVisible(expanded);
        headerLabel.setText((expanded ? "▾ " : "▸ ") + "Jev Failure Triage");
        revalidate();
    }

    boolean isExpanded() {
        return expanded;
    }

    String getHeaderText() {
        return headerLabel.getText();
    }

    String getSummaryText() {
        return summaryLabel.getText();
    }

    String getAdvisoryText() {
        return advisoryText.getText();
    }

    String getDetailsText() {
        return detailsArea.getText();
    }

    void applyTheme() {
        setBackground(ThemeColors.accentSoft());
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, ThemeColors.accent()),
                BorderFactory.createEmptyBorder(UiTokens.SPACE_3, UiTokens.SPACE_3,
                        UiTokens.SPACE_3, UiTokens.SPACE_3)));
        headerLabel.setForeground(ThemeColors.accent());
        summaryLabel.setForeground(ThemeColors.foreground());
        advisoryText.setForeground(ThemeColors.secondaryText());
        detailsArea.setForeground(ThemeColors.secondaryText());
        repaint();
    }

    private String summary() {
        int total = notice.totalFailures();
        int rows = notice.rows().size();
        int classified = notice.classifiedFailures();
        StringBuilder text = new StringBuilder()
                .append(total).append(total == 1 ? " failure" : " failures");
        if (classified > 0) {
            text.append(" · ").append(classified).append(" classified");
        }
        if (!notice.dominantCategory().isEmpty()) {
            text.append(" · dominant: ").append(FailureTriage.displayName(notice.dominantCategory()));
        }
        if (total > rows) {
            text.append(" · ").append(total - rows).append(" more not triaged");
        }
        return text.toString();
    }

    private String advisory(String modelId) {
        return "Advisory only — " + modelName(modelId) + " still writes the diagnosis.";
    }

    /** Provider-first name for mid-sentence use, matching {@link JevRouteCard}. */
    private static String modelName(String modelId) {
        String[] parts = ModelDisplay.parse(modelId);
        return parts[1].isEmpty() ? parts[0] : parts[1] + " " + parts[0];
    }

    private String details() {
        Map<String, List<TriageNotice.Row>> byCategory = new LinkedHashMap<>();
        for (TriageNotice.Row row : notice.rows()) {
            byCategory.computeIfAbsent(row.category(), k -> new java.util.ArrayList<>()).add(row);
        }
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, List<TriageNotice.Row>> entry : byCategory.entrySet()) {
            if (text.length() > 0) {
                text.append("\n");
            }
            text.append(FailureTriage.displayName(entry.getKey()))
                    .append(" (").append(entry.getValue().size()).append("):");
            for (TriageNotice.Row row : entry.getValue()) {
                text.append("\n- ").append(row.label());
                if (row.confidence() > 0) {
                    text.append(" (").append(Math.round(row.confidence() * 100)).append("%)");
                }
            }
        }
        return text.toString();
    }
}
