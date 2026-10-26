package org.qainsights.jmeter.ai.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

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

    FixResultCard(FixResult result, BooleanSupplier undo, Runnable saveAs, Runnable keepAction) {
        this(result, undo, saveAs, keepAction, ignored -> { });
    }

    FixResultCard(FixResult result, BooleanSupplier undo, Runnable saveAs, Runnable keepAction,
                  Consumer<String> reportFallback) {
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
            content.add(label("Before: " + result.beforeFailed() + " of " + result.beforeTotal()
                    + " requests failing → after: " + result.afterFailed() + " of " + result.afterTotal()));
        }
        if (result.planDiff() != null) {
            org.qainsights.jmeter.ai.fix.PlanDiff diff = result.planDiff();
            content.add(label("What changed: " + diff.added().size() + " added, "
                    + diff.removed().size() + " removed, " + diff.modified().size() + " modified"));
            diff.added().forEach(change -> content.add(label("Added: " + change.path())));
            diff.removed().forEach(change -> content.add(label("Removed: " + change.path())));
            diff.modified().forEach(change -> {
                java.util.Set<String> properties = new java.util.TreeSet<>(change.beforeProperties().keySet());
                properties.addAll(change.afterProperties().keySet());
                for (String property : properties) {
                    String oldValue = change.beforeProperties().get(property);
                    String newValue = change.afterProperties().get(property);
                    if (!java.util.Objects.equals(oldValue, newValue)) {
                        content.add(label(change.path() + " " + property + ": "
                                + (oldValue == null ? "" : oldValue) + " → "
                                + (newValue == null ? "" : newValue)));
                    }
                }
            });
            long assertions = diff.added().stream()
                    .filter(org.qainsights.jmeter.ai.fix.PlanDiff.ElementChange::isResponseAssertion)
                    .count();
            long listeners = diff.added().stream()
                    .filter(org.qainsights.jmeter.ai.fix.PlanDiff.ElementChange::isResultListener)
                    .count();
            content.add(label("Assertions added: " + assertions));
            content.add(label("Listeners added: " + listeners));
        }
        result.skippedPageChecks().forEach(line -> content.add(label(line)));
        for (String round : result.roundSummaries()) {
            content.add(label(round));
        }
        if (!result.reason().isBlank()) {
            content.add(label(result.reason()));
        }
        if (result.concurrencyChecked()) {
            if (!result.concurrencyReason().isBlank()) {
                content.add(label("Inconclusive: " + result.concurrencyReason()));
            } else {
                content.add(label("Multi-user check: " + result.concurrencyBeforeFailed() + " of "
                        + result.concurrencyBeforeTotal() + " requests failed → "
                        + result.concurrencyAfterFailed() + " of " + result.concurrencyAfterTotal() + " failed"));
            }
        }
        if (!result.beforeFailures().isEmpty()) {
            content.add(label("Before failures:"));
            for (int i = 0; i < Math.min(5, result.beforeFailures().size()); i++) {
                Failure failure = result.beforeFailures().get(i);
                content.add(label(failure.getLabel() + " (" + failure.getResponseCode() + "): "
                        + failure.getMessage()));
            }
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
        result.deterministicHints().forEach(hint -> content.add(label("Needs you: " + hint)));
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
            if (undo.getAsBoolean()) {
                actions.setVisible(false);
            }
        });
        actions.add(keep);
        if (result.reportFile() != null) {
            JButton report = new JButton("Open report");
            report.addActionListener(event -> {
                FixReportDialog dialog = new FixReportDialog(result.reportFile(), reportFallback);
                dialog.setVisible(true);
            });
            actions.add(report);
        }
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
