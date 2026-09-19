package org.qainsights.jmeter.ai.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;

import org.qainsights.jmeter.ai.agent.AgentRequestRouter;
import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.gui.theme.UiTokens;

class JevRouteCard extends JPanel {

    private final AgentRequestRouter.Notice notice;
    private final JLabel headerLabel = new JLabel();
    private final JLabel summaryLabel = new JLabel();
    private final JTextArea responsibilityText = new JTextArea();
    private final JTextArea detailsArea = new JTextArea();
    private boolean expanded;

    JevRouteCard(AgentRequestRouter.Notice notice, String modelId) {
        super(new BorderLayout());
        this.notice = notice == null
                ? new AgentRequestRouter.Notice(AgentRequestRouter.Decision.unavailable(), java.util.List.of(), 0)
                : notice;
        setOpaque(true);
        setAlignmentX(Component.LEFT_ALIGNMENT);
        configureLabels();
        configureDetails(modelId);
        add(content(), BorderLayout.CENTER);
        summaryLabel.setText(summary());
        responsibilityText.setText(responsibility(modelId));
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
        responsibilityText.setEditable(false);
        responsibilityText.setOpaque(false);
        responsibilityText.setBorder(BorderFactory.createEmptyBorder());
        responsibilityText.setLineWrap(true);
        responsibilityText.setWrapStyleWord(true);
        responsibilityText.setAlignmentX(Component.LEFT_ALIGNMENT);
        responsibilityText.setFont(UiTokens.caption(responsibilityText.getFont()));
    }

    private void configureDetails(String modelId) {
        detailsArea.setEditable(false);
        detailsArea.setOpaque(false);
        detailsArea.setBorder(BorderFactory.createEmptyBorder());
        detailsArea.setLineWrap(true);
        detailsArea.setWrapStyleWord(true);
        detailsArea.setAlignmentX(Component.LEFT_ALIGNMENT);
        detailsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        detailsArea.setText(details(modelId));
        detailsArea.setVisible(false);
    }

    private JPanel content() {
        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.add(headerLabel);
        content.add(summaryLabel);
        content.add(responsibilityText);
        content.add(detailsArea);
        return content;
    }

    void setExpanded(boolean expanded) {
        this.expanded = expanded;
        detailsArea.setVisible(expanded);
        String title = notice.isExpansion() ? "Jev Expanded Tools" : "Jev Smart Route";
        headerLabel.setText((expanded ? "▾ " : "▸ ") + title);
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

    String getResponsibilityText() {
        return responsibilityText.getText();
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
        responsibilityText.setForeground(ThemeColors.secondaryText());
        detailsArea.setForeground(ThemeColors.secondaryText());
        repaint();
    }

    private String summary() {
        AgentRequestRouter.Decision decision = notice.decision();
        int confidence = (int) Math.round(decision.confidence() * 100);
        if (notice.isExpansion()) {
            return switch (decision.outcome()) {
                case FOCUSED -> decision.route().displayName() + " added · " + confidence + "% confidence";
                case ALL_TOOLS -> "All Agent Mode tools enabled · " + confidence + "% confidence";
                case UNAVAILABLE -> "Jev unavailable · all Agent Mode tools enabled";
            };
        }
        return switch (decision.outcome()) {
            case FOCUSED -> decision.route().displayName() + " · " + confidence + "% confidence";
            case ALL_TOOLS -> decision.route() == AgentRequestRouter.Route.COMPLEX_OR_UNCLEAR
                    ? "Complex request · using all Agent Mode tools · " + confidence + "% confidence"
                    : "Jev was uncertain · using all Agent Mode tools · " + confidence + "% confidence";
            case UNAVAILABLE -> "Jev unavailable · using standard Agent Mode";
        };
    }

    private String responsibility(String modelId) {
        String model = modelName(modelId);
        int selected = notice.toolNames().size();
        int total = notice.totalToolCount();
        if (notice.isExpansion()) {
            return selected + " of " + total + " tools available to " + model;
        }
        if (notice.decision().isFocused()) {
            return selected + " focused tools of " + total + " available to " + model;
        }
        return total + " tools available to " + model;
    }

    private String details(String modelId) {
        String model = modelName(modelId);
        StringBuilder text = new StringBuilder(detailsSummary(model));
        java.util.List<String> names = notice.isExpansion()
                ? notice.addedToolNames() : notice.toolNames();
        if (!names.isEmpty()) {
            text.append(notice.isExpansion() ? "\n\nAdded tools:" : "\n\nTools:");
            for (String tool : names) {
                text.append("\n- ").append(tool.toLowerCase(Locale.ROOT));
            }
        }
        return text.toString();
    }

    /** Provider-first name for mid-sentence use: {@code "meta:x" -> "Meta x"}. */
    private static String modelName(String modelId) {
        String[] parts = ModelDisplay.parse(modelId);
        return parts[1].isEmpty() ? parts[0] : parts[1] + " " + parts[0];
    }

    private String detailsSummary(String model) {
        if (notice.isExpansion()) {
            return switch (notice.decision().outcome()) {
                case FOCUSED -> "Jev added the " + notice.decision().route().displayName()
                        + " tools; " + model + " still reasons and performs the work.";
                case ALL_TOOLS -> "Jev enabled all Agent Mode tools for " + model + ".";
                case UNAVAILABLE -> "Jev was unavailable; all Agent Mode tools were enabled for "
                        + model + ".";
            };
        }
        return switch (notice.decision().outcome()) {
            case FOCUSED -> "Jev selected the tool family; " + model + " still reasons and performs the work.";
            case ALL_TOOLS -> "Jev provided all Agent Mode tools to " + model + " for this request.";
            case UNAVAILABLE -> "Jev was unavailable; standard Agent Mode tools were provided to " + model + ".";
        };
    }
}
