package org.qainsights.jmeter.ai.fix;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.apache.jmeter.testelement.TestElement;
import org.qainsights.jmeter.ai.utils.SafeText;

/** Writes a retained Markdown summary of a Make it pass run. */
public final class FixReport {
    private static final int RETAINED_REPORTS = 20;

    public File write(FixResult result) throws IOException {
        Path directory = Path.of(System.getProperty("user.home"), ".jmeter-ai", "fix-reports");
        Files.createDirectories(directory);
        Path path = directory.resolve("fix-" + Instant.now().toEpochMilli() + ".md");
        Files.writeString(path, markdown(result), StandardCharsets.UTF_8);
        try (var reports = Files.list(directory)) {
            List<Path> oldReports = reports.filter(file -> file.getFileName().toString().matches("fix-\\d+\\.md"))
                    .sorted(Comparator.comparingLong(FixReport::timestamp).reversed())
                    .skip(RETAINED_REPORTS).toList();
            for (Path old : oldReports) {
                Files.deleteIfExists(old);
            }
        }
        return path.toFile();
    }

    public static String markdown(FixResult result) {
        StringBuilder text = new StringBuilder("# Make it pass report\n\n")
                .append("## Before\n\n")
                .append(result.beforeFailed()).append(" of ").append(result.beforeTotal())
                .append(" requests failed before repair.\n");
        if (result.beforeFailures().isEmpty()) {
            text.append("\nNo failing requests were recorded.\n\n");
        } else {
            text.append('\n');
            result.beforeFailures().forEach(failure -> text.append("- ").append(failure.getLabel())
                    .append(" (").append(failure.getResponseCode()).append("): ")
                    .append(failure.getMessage()).append('\n'));
            text.append('\n');
        }
        text
                .append("## What changed\n\n");
        PlanDiff diff = result.planDiff();
        if (diff == null || (diff.added().isEmpty() && diff.removed().isEmpty() && diff.modified().isEmpty())) {
            text.append("No plan changes recorded.\n\n");
        } else {
            appendChanges(text, "Added", diff.added(), ChangeKind.ADDED);
            appendChanges(text, "Removed", diff.removed(), ChangeKind.REMOVED);
            appendChanges(text, "Modified", diff.modified(), ChangeKind.MODIFIED);
        }
        text.append("## Why\n\n");
        for (int i = 0; i < result.roundSummaries().size(); i++) {
            text.append("- ").append(result.roundSummaries().get(i));
            if (i < result.roundDiffs().size()) {
                PlanDiff round = result.roundDiffs().get(i);
                text.append(" ").append(round.added().size() + round.modified().size())
                        .append(" elements changed.");
            }
            text.append('\n');
        }
        if (result.roundSummaries().isEmpty()) {
            text.append("No repair rounds ran.\n");
        }
        text.append("\n## Assertions added\n\n");
        appendAssertions(text, diff);
        result.skippedPageChecks().forEach(label -> text.append("- ").append(label).append('\n'));
        text.append("\n## Listeners added\n\n");
        if (diff != null) {
            diff.added().stream().filter(PlanDiff.ElementChange::isResultListener)
                    .forEach(change -> text.append("- ").append(change.path()).append('\n'));
        }
        text.append("\n## Needs you\n\n");
        List<String> needsUser = new ArrayList<>();
        result.agentSummaries().forEach(summary -> {
            if (summary != null) {
                summary.lines().filter(line -> line.startsWith("NEEDS USER:"))
                        .map(SafeText::redactSensitiveValues)
                        .map(line -> line.substring(0, Math.min(line.length(), 2048)))
                        .forEach(needsUser::add);
            }
        });
        needsUser.addAll(result.deterministicHints());
        if (needsUser.isEmpty()) {
            text.append("No user input was requested.\n");
        } else {
            needsUser.forEach(line -> text.append("- ").append(line).append('\n'));
        }
        text.append("\n## Multi-user check\n\n");
        if (result.concurrencyChecked()) {
            text.append("2 users × 2 loops: ").append(result.concurrencyBeforeFailed()).append(" of ")
                    .append(result.concurrencyBeforeTotal()).append(" failed → ")
                    .append(result.concurrencyAfterFailed()).append(" of ")
                    .append(result.concurrencyAfterTotal()).append(" failed.\n");
        } else {
            text.append("Not run.\n");
        }
        return text.toString();
    }

    private static void appendChanges(StringBuilder text, String heading, List<PlanDiff.ElementChange> changes,
                                      ChangeKind kind) {
        text.append("### ").append(heading).append('\n');
        if (changes.isEmpty()) {
            text.append("None.\n");
        } else {
            changes.forEach(change -> {
                text.append("- ").append(change.path()).append(" (").append(change.className()).append(")\n");
                java.util.Set<String> properties = new java.util.TreeSet<>(change.beforeProperties().keySet());
                properties.addAll(change.afterProperties().keySet());
                for (String property : properties) {
                    String oldValue = change.beforeProperties().get(property);
                    String newValue = change.afterProperties().get(property);
                    if (kind != ChangeKind.REMOVED && isClassProperty(property)) {
                        continue;
                    }
                    if (kind == ChangeKind.ADDED && (newValue == null || newValue.isEmpty())) {
                        continue;
                    }
                    if (!Objects.equals(oldValue, newValue)) {
                        text.append("  - `").append(property).append("`: `").append(oldValue == null ? "" : oldValue)
                                .append("` → `").append(newValue == null ? "" : newValue).append("`\n");
                    }
                }
            });
        }
        text.append('\n');
    }

    private static boolean isClassProperty(String property) {
        return TestElement.GUI_CLASS.equals(property) || TestElement.TEST_CLASS.equals(property);
    }

    private enum ChangeKind {
        ADDED, REMOVED, MODIFIED
    }

    private static void appendAssertions(StringBuilder text, PlanDiff diff) {
        if (diff != null) {
            diff.added().stream().filter(PlanDiff.ElementChange::isResponseAssertion)
                    .forEach(change -> text.append("- ").append(change.path()).append('\n'));
        }
    }

    private static long timestamp(Path file) {
        String name = file.getFileName().toString();
        try {
            return Long.parseLong(name.substring(4, name.length() - 3));
        } catch (RuntimeException ignored) {
            return 0;
        }
    }
}
