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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(FailureTriage.class);

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

    /** Ordered run severity, lowest first; the score question uses this order as its scale. */
    public enum Severity {
        LOW("Low", "Isolated or minor failures. The run still represents the system's behaviour under load."),
        MEDIUM("Medium", "Some transactions fail. Results are degraded but still usable with caveats."),
        HIGH("High", "Key transactions fail. The results cannot be trusted for a performance sign-off."),
        CRITICAL("Critical", "The system is unreachable or most samples fail. The run is not valid.");

        private final String displayName;
        private final String description;

        Severity(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
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
    private static final String SEVERITY_INSTRUCTIONS =
            "Rate how severe these failures from one JMeter load test run are for the system under test. "
                    + "Use how many samples failed, their root-cause categories, response codes and messages.";
    private static final Map<String, String> SEVERITY_LEVELS = severityLevels();

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
        SeverityRating severity = rateSeverity(failures, rows, totalFailures);
        return new TriageOutcome(buildNotice(rows, totalFailures, severity), advisory(rows, severity));
    }

    /** Both outputs of one triage pass: the card payload and the model-facing advisory text. */
    public record TriageOutcome(TriageNotice notice, String advisory) {
    }

    private record Row(String label, String category, double confidence) {
    }

    private record SeverityRating(Severity severity, double confidence) {
    }

    /**
     * One score judgment for the whole run, only on SCORE-capable backends and only when at
     * least one failure was classified. Errors and low confidence leave the rating out.
     */
    private SeverityRating rateSeverity(List<TestRunSummary.Failure> failures, List<Row> rows, int totalFailures) {
        if (!provider.capabilities().contains(JudgmentProvider.Capability.SCORE)
                || rows.stream().allMatch(row -> UNCLASSIFIED.equals(row.category()))) {
            return null;
        }
        List<Map<String, String>> signatures = new ArrayList<>();
        List<TestRunSummary.Failure> unique = unique(failures);
        for (int i = 0; i < rows.size() && i < unique.size(); i++) {
            TestRunSummary.Failure failure = unique.get(i);
            signatures.add(Map.of("label", failure.getLabel(),
                    "response_code", failure.getResponseCode(),
                    "message", bound(failure.getMessage()),
                    "category", rows.get(i).category()));
        }
        try {
            JudgmentProvider.ScoreAnswer answer = provider.score(
                    Map.of("total_failures", totalFailures,
                            "unique_failures", unique.size(),
                            "failures", signatures),
                    SEVERITY_INSTRUCTIONS, SEVERITY_LEVELS);
            if (answer == null || answer.confidence() < minConfidence) {
                return null;
            }
            Severity[] levels = Severity.values();
            return new SeverityRating(levels[answer.level(levels.length)], answer.confidence());
        } catch (RuntimeException e) {
            log.warn("{} triage could not rate severity: {}", AgentJudgmentConfig.brand(), e.getMessage());
            return null;
        }
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
            log.warn("{} triage could not classify a failure: {}", AgentJudgmentConfig.brand(), e.getMessage());
            return new Row(failure.getLabel(), UNCLASSIFIED, 0.0);
        }
    }

    private TriageNotice buildNotice(List<Row> rows, int totalFailures, SeverityRating severity) {
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
        return severity == null
                ? new TriageNotice(totalFailures, classified, dominant, noticeRows)
                : new TriageNotice(totalFailures, classified, dominant, noticeRows,
                        severity.severity().name(), severity.confidence());
    }

    private String advisory(List<Row> rows, SeverityRating severity) {
        StringBuilder text = new StringBuilder(AgentJudgmentConfig.brand() + " triage (advisory):");
        Map<String, Integer> counts = countsByCategory(rows);
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            parts.add(displayName(entry.getKey()) + "×" + entry.getValue());
        }
        text.append(" categories: ").append(String.join(", ", parts)).append(".");
        if (severity != null) {
            text.append(" Severity: ").append(severity.severity().displayName())
                    .append(" (").append(Math.round(severity.confidence() * 100)).append("%).");
        }
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

    /** Display name for a severity name; empty for blank or unknown values. */
    public static String severityDisplayName(String severity) {
        if (severity == null || severity.isEmpty()) {
            return "";
        }
        try {
            return Severity.valueOf(severity).displayName();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static String bound(String message) {
        String value = message == null ? "" : message;
        return value.length() <= MAX_MESSAGE_CHARS ? value : value.substring(0, MAX_MESSAGE_CHARS);
    }

    private static Map<String, String> severityLevels() {
        Map<String, String> values = new LinkedHashMap<>();
        for (Severity severity : Severity.values()) {
            values.put(severity.displayName(), severity.description);
        }
        return Collections.unmodifiableMap(values);
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
