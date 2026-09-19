package org.qainsights.jmeter.ai.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.qainsights.jmeter.ai.agent.jmeter.TestRunSummary;
import org.qainsights.jmeter.ai.service.JudgmentProvider;

/**
 * Jev classification of failed samples after a {@code get_test_results} run. Each
 * unique failure signature (label + response code + message, deduplicated and capped)
 * is classified into one root-cause {@link Category} with a single Choice judgment.
 * Verdicts are grouped for the transcript card and summarized into an advisory line
 * appended to the tool result so the chat model diagnoses from a categorized picture.
 * <p>
 * Triage is advisory only: provider failures, missing keys, or low-confidence answers
 * degrade to "unclassified"/no result - the run's own failure data is never altered.
 */
public final class FailureTriage {

    /** Jev root-cause buckets for a failed sample, with display names for the card. */
    public enum Category {
        CONNECTION("Connection error"),
        TIMEOUT("Timeout"),
        HTTP_CLIENT("HTTP client error (4xx)"),
        HTTP_SERVER("HTTP server error (5xx)"),
        AUTH_SESSION("Auth/session issue"),
        ASSERTION("Assertion failure"),
        SCRIPT("Script error"),
        CONFIG("Configuration error"),
        OTHER("Other");

        private final String displayName;

        Category(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    /** Bucket for verdicts whose confidence fell below the threshold or were unusable. */
    public static final String UNCLASSIFIED = "UNCLASSIFIED";

    static final int MAX_MESSAGE_CHARS = 300;

    private static final String INSTRUCTIONS =
            "Classify the single most likely root cause of this failed JMeter sample. "
                    + "Use its sampler label, response code and response message.";
    private static final Map<String, String> CRITERIA = criteria();

    private final JudgmentProvider provider;
    private final double minConfidence;
    private final int maxFailures;

    /**
     * @param provider      the Jev judgment provider; null or non-CHOICE disables triage
     * @param minConfidence verdicts below this confidence become {@link #UNCLASSIFIED}
     * @param maxFailures   cap on unique signatures sent to Jev (extra failures are skipped)
     */
    public FailureTriage(JudgmentProvider provider, double minConfidence, int maxFailures) {
        this.provider = provider;
        this.minConfidence = Math.max(0, Math.min(1, minConfidence));
        this.maxFailures = Math.max(0, maxFailures);
    }

    /**
     * Classifies up to {@link #maxFailures} unique failure signatures, sequentially.
     * Returns null when triage is impossible or produced nothing - callers should then
     * return the untriaged result unchanged.
     */
    public TriageOutcome triage(List<TestRunSummary.Failure> failures, int totalFailures) {
        if (provider == null || failures == null || failures.isEmpty() || maxFailures < 1
                || !provider.capabilities().contains(JudgmentProvider.Capability.CHOICE)) {
            return null;
        }
        List<Row> rows = new ArrayList<>();
        for (TestRunSummary.Failure failure : unique(failures)) {
            rows.add(classify(failure));
            if (rows.size() >= maxFailures) {
                break;
            }
        }
        if (rows.isEmpty()) {
            return null;
        }
        return new TriageOutcome(buildNotice(rows, totalFailures), advisory(rows));
    }

    /** Both outputs of one triage pass: the card payload and the model-facing advisory text. */
    public record TriageOutcome(TriageNotice notice, String advisory) {
    }

    private record Row(String label, String category, double confidence) {
    }

    private List<TestRunSummary.Failure> unique(List<TestRunSummary.Failure> failures) {
        Set<String> seen = new LinkedHashSet<>();
        List<TestRunSummary.Failure> unique = new ArrayList<>();
        for (TestRunSummary.Failure f : failures) {
            if (seen.add(f.getLabel() + "" + f.getResponseCode() + "" + f.getMessage())) {
                unique.add(f);
            }
        }
        return unique;
    }

    private Row classify(TestRunSummary.Failure failure) {
        try {
            JudgmentProvider.ChoiceAnswer answer = provider.choose(
                    Map.of("label", failure.getLabel(),
                            "response_code", failure.getResponseCode(),
                            "message", bound(failure.getMessage())),
                    INSTRUCTIONS, CRITERIA);
            Category category = Category.valueOf(answer.choice());
            return new Row(failure.getLabel(),
                    answer.confidence() >= minConfidence ? category.name() : UNCLASSIFIED,
                    answer.confidence());
        } catch (RuntimeException e) {
            return new Row(failure.getLabel(), UNCLASSIFIED, 0.0);
        }
    }

    private TriageNotice buildNotice(List<Row> rows, int totalFailures) {
        Map<String, Integer> counts = countsByCategory(rows);
        int classified = 0;
        String dominant = "";
        int dominantCount = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (!UNCLASSIFIED.equals(entry.getKey())) {
                classified += entry.getValue();
                if (entry.getValue() > dominantCount) {
                    dominant = entry.getKey();
                    dominantCount = entry.getValue();
                }
            }
        }
        List<TriageNotice.Row> noticeRows = new ArrayList<>();
        for (Row row : rows) {
            noticeRows.add(new TriageNotice.Row(row.label(), row.category(), row.confidence()));
        }
        return new TriageNotice(totalFailures, classified, dominant, noticeRows);
    }

    private String advisory(List<Row> rows) {
        StringBuilder text = new StringBuilder("Jev triage (advisory):");
        Map<String, Integer> counts = countsByCategory(rows);
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            parts.add(displayName(entry.getKey()) + "×" + entry.getValue());
        }
        text.append(" categories: ").append(String.join(", ", parts)).append(".");
        for (Row row : rows) {
            text.append("\n- ").append(row.label()).append(" → ")
                    .append(displayName(row.category()))
                    .append(" (").append(Math.round(row.confidence() * 100)).append("%)");
        }
        return text.toString();
    }

    private static Map<String, Integer> countsByCategory(List<Row> rows) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Row row : rows) {
            counts.merge(row.category(), 1, Integer::sum);
        }
        return counts;
    }

    /** Display name for a category name or {@link #UNCLASSIFIED}; safe for the card too. */
    public static String displayName(String category) {
        if (UNCLASSIFIED.equals(category) || category == null) {
            return "Unclassified";
        }
        try {
            return Category.valueOf(category).displayName();
        } catch (IllegalArgumentException e) {
            return "Unclassified";
        }
    }

    private static String bound(String message) {
        String value = message == null ? "" : message;
        return value.length() <= MAX_MESSAGE_CHARS ? value : value.substring(0, MAX_MESSAGE_CHARS);
    }

    private static Map<String, String> criteria() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(Category.CONNECTION.name(),
                "DNS resolution failure, connection refused or reset, unreachable host, SSL handshake failure.");
        values.put(Category.TIMEOUT.name(), "Connect, read, or response timeout.");
        values.put(Category.HTTP_CLIENT.name(), "HTTP 4xx client error such as 400, 401, 403, 404, 405.");
        values.put(Category.HTTP_SERVER.name(), "HTTP 5xx server error.");
        values.put(Category.AUTH_SESSION.name(),
                "Authentication or session failure: redirect to login, expired or missing token, "
                        + "or a dynamic session value that was not correlated.");
        values.put(Category.ASSERTION.name(), "Assertion, checksum, or response validation failure.");
        values.put(Category.SCRIPT.name(), "JSR223, Groovy, BeanShell, or expression evaluation error.");
        values.put(Category.CONFIG.name(),
                "Malformed URL, unresolved variable, missing element, or test-plan misconfiguration.");
        values.put(Category.OTHER.name(), "A root cause that fits none of the other categories.");
        return Collections.unmodifiableMap(values);
    }
}
