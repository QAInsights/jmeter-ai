package org.qainsights.jmeter.ai.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;

import org.qainsights.jmeter.ai.agent.jmeter.TestRunSummary.Failure;
import org.qainsights.jmeter.ai.fix.FixResult;
import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.gui.theme.UiTokens;

/** Transcript result card for a Make it pass run. */
final class FixResultCard extends JPanel {
    private final JPanel actions = new JPanel();

    FixResultCard(FixResult result, Runnable undo, Runnable saveAs, Runnable keepAction) {
        super(new BorderLayout());
        setOpaque(true);
        setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        JLabel title = new JLabel(title(result.status()));
        title.setFont(UiTokens.label(title.getFont()));
        title.setForeground(color(result.status()));
        content.add(title);
        if (result.beforeTotal() > 0) {
            content.add(label(result.beforeFailed() + " of " + result.beforeTotal()
                    + " requests failing → " + result.afterFailed() + " of " + result.afterTotal()));
        }
        if (!result.reason().isBlank()) {
            content.add(label(result.reason()));
        }
        for (String summary : result.agentSummaries()) {
            if (summary == null || summary.isBlank()) {
                continue;
            }
            String cleaned = summary.trim();
            content.add(label(cleaned.length() > 500 ? cleaned.substring(0, 500) : cleaned));
        }
        for (String needsUser : needsUserLines(result.agentSummaries())) {
            content.add(label(needsUser));
        }
        List<Failure> failures = result.remainingFailures();
        for (int i = 0; i < Math.min(5, failures.size()); i++) {
            Failure failure = failures.get(i);
            content.add(label("• " + failure.getLabel() + " (" + failure.getResponseCode() + "): "
                    + failure.getMessage()));
        }
        add(content, BorderLayout.CENTER);
        actions.setOpaque(false);
        JButton keep = new JButton("Keep");
        keep.addActionListener(event -> {
            keepAction.run();
            actions.setVisible(false);
        });
        JButton undoButton = new JButton("Undo all");
        undoButton.addActionListener(event -> {
            undo.run();
            actions.setVisible(false);
        });
        actions.add(keep);
        if (result.hasUndoableChanges()) {
            JButton save = new JButton("Save as");
            save.addActionListener(event -> saveAs.run());
            actions.add(undoButton);
            actions.add(save);
        }
        add(actions, BorderLayout.SOUTH);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, ThemeColors.accent()),
                BorderFactory.createEmptyBorder(UiTokens.SPACE_3, UiTokens.SPACE_3,
                        UiTokens.SPACE_3, UiTokens.SPACE_3)));
        setBackground(ThemeColors.accentSoft());
    }

    private static JLabel label(String text) {
        JLabel label = new JLabel("<html>" + escape(text).replace("\n", "<br>") + "</html>");
        label.setForeground(ThemeColors.foreground());
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static List<String> needsUserLines(List<String> summaries) {
        List<String> result = new ArrayList<>();
        for (String summary : summaries) {
            for (String line : summary.split("\\R")) {
                if (line.startsWith("NEEDS USER:")) {
                    result.add(line);
                }
            }
        }
        return result;
    }

    private static String title(FixResult.Status status) {
        return switch (status) {
            case ALREADY_PASSING -> "Already passing";
            case FIXED -> "Plan passes";
            case PARTLY_FIXED -> "Partly fixed";
            case NOT_FIXED -> "Couldn't fix it";
            case CANCELLED -> "Make it pass cancelled";
            case NOTHING_RAN -> "No requests ran";
        };
    }

    private static java.awt.Color color(FixResult.Status status) {
        return switch (status) {
            case ALREADY_PASSING, FIXED -> ThemeColors.success();
            case PARTLY_FIXED -> ThemeColors.warning();
            case NOT_FIXED, CANCELLED, NOTHING_RAN -> ThemeColors.error();
        };
    }
}
