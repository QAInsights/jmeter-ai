package org.qainsights.jmeter.ai.sla;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic parser for {@code @sla} text such as "p95 under 800 ms and no 5xx".
 * Recognizes response-time limits and response-code rules only; everything else is reported
 * back as unsupported or not understood rather than guessed.
 */
public final class SlaParser {

    private static final Pattern CLAUSE_SPLIT = Pattern.compile("\\s*(?:,|;|&|\\band\\b|\\bplus\\b)\\s*");
    private static final Pattern DURATION = Pattern.compile(
            "(?:under|below|less than|lower than|within|at most|no more than|not more than|up to|max(?:imum)?(?: of)?|<=?)"
                    + "\\s*(\\d+(?:\\.\\d+)?)\\s*(ms|msec|millis(?:econds?)?|milliseconds?|s|secs?|seconds?)\\b");
    private static final Pattern STATISTIC = Pattern.compile(
            "\\b(p\\d{1,2}(?:\\.\\d+)?|\\d{1,2}(?:st|nd|rd|th) percentile|avg|average|mean|median)\\b");
    private static final Pattern EXACT_CODE = Pattern.compile(
            "^(?:only\\s+)?(?:http\\s+)?(?:status|response code|code)?\\s*(?:is\\s+|=\\s*|of\\s+)?([1-5]\\d\\d)(?:\\s+only)?$");
    private static final Pattern UNSUPPORTED = Pattern.compile(
            "\\b(error rate|errors? under|errors? below|%|percent|throughput|tps|rps|hits|requests per|apdex)\\b|%");

    private SlaParser() {
    }

    /** What one response-code rule checks. */
    public enum CodeRule {
        NO_5XX("5\\d\\d", true, "no 5xx response codes"),
        NO_4XX("4\\d\\d", true, "no 4xx response codes"),
        NO_4XX_5XX("[45]\\d\\d", true, "no 4xx or 5xx response codes"),
        ONLY_2XX("2\\d\\d", false, "only 2xx response codes");

        private final String pattern;
        private final boolean negated;
        private final String description;

        CodeRule(String pattern, boolean negated, String description) {
            this.pattern = pattern;
            this.negated = negated;
            this.description = description;
        }

        public String pattern() {
            return pattern;
        }

        public boolean negated() {
            return negated;
        }

        public String description() {
            return description;
        }
    }

    /**
     * Parse result. {@code maxMillis} is 0 when no time limit was given; {@code statistic} is the
     * percentile or average the user named (empty for none or "max"); {@code exactCode} is set for
     * "status 200"-style rules instead of a {@link CodeRule}.
     */
    public record SlaSpec(long maxMillis, String statistic, CodeRule codeRule, String exactCode,
                          List<String> unsupported, List<String> unrecognized) {
        public SlaSpec {
            statistic = statistic == null ? "" : statistic;
            exactCode = exactCode == null ? "" : exactCode;
            unsupported = List.copyOf(unsupported);
            unrecognized = List.copyOf(unrecognized);
        }

        public boolean isEmpty() {
            return maxMillis <= 0 && codeRule == null && exactCode.isEmpty();
        }
    }

    /** Parses the text after {@code @sla}; a leading {@code @sla} token is ignored. */
    public static SlaSpec parse(String text) {
        String body = text == null ? "" : text.trim();
        if (body.toLowerCase(Locale.ROOT).startsWith("@sla")) {
            body = body.substring(4);
        }
        long maxMillis = 0;
        String statistic = "";
        CodeRule codeRule = null;
        String exactCode = "";
        List<String> unsupported = new ArrayList<>();
        List<String> unrecognized = new ArrayList<>();

        for (String raw : CLAUSE_SPLIT.split(body.toLowerCase(Locale.ROOT).trim())) {
            String clause = raw.trim().replaceAll("\\s+", " ");
            if (clause.isEmpty()) {
                continue;
            }
            Matcher duration = DURATION.matcher(clause);
            if (duration.find() && !clause.contains("%")) {
                long millis = toMillis(duration.group(1), duration.group(2));
                if (millis > 0 && (maxMillis == 0 || millis < maxMillis)) {
                    maxMillis = millis;
                    Matcher stat = STATISTIC.matcher(clause);
                    statistic = stat.find() ? stat.group(1) : "";
                }
                continue;
            }
            CodeRule rule = codeRule(clause);
            if (rule != null) {
                codeRule = merge(codeRule, rule);
                continue;
            }
            Matcher exact = EXACT_CODE.matcher(clause);
            if (exact.matches() && clause.matches(".*\\b(only|status|code)\\b.*")) {
                exactCode = exact.group(1);
                continue;
            }
            if (UNSUPPORTED.matcher(clause).find()) {
                unsupported.add(raw.trim());
            } else {
                unrecognized.add(raw.trim());
            }
        }
        if (codeRule != null && !exactCode.isEmpty()) {
            codeRule = null;
        }
        return new SlaSpec(maxMillis, statistic, codeRule, exactCode, unsupported, unrecognized);
    }

    private static CodeRule codeRule(String clause) {
        boolean no = clause.matches(".*\\b(no|zero|without|never)\\b.*");
        boolean has5xx = clause.matches(".*\\b5xx\\b.*") || clause.contains("server error");
        boolean has4xx = clause.matches(".*\\b4xx\\b.*") || clause.contains("client error");
        if (no && has5xx && has4xx) {
            return CodeRule.NO_4XX_5XX;
        }
        if (no && has5xx) {
            return CodeRule.NO_5XX;
        }
        if (no && has4xx) {
            return CodeRule.NO_4XX;
        }
        if (no && clause.matches(".*\\b(http )?errors?\\b.*") && !clause.matches(".*\\d.*")) {
            return CodeRule.NO_4XX_5XX;
        }
        if (clause.matches(".*\\b2xx\\b.*") || clause.matches("^(all |only )?success(ful)?( responses?)?$")) {
            return CodeRule.ONLY_2XX;
        }
        return null;
    }

    private static CodeRule merge(CodeRule current, CodeRule next) {
        if (current == null || current == next) {
            return next;
        }
        if (current == CodeRule.ONLY_2XX || next == CodeRule.ONLY_2XX) {
            return CodeRule.ONLY_2XX;
        }
        return CodeRule.NO_4XX_5XX;
    }

    private static long toMillis(String amount, String unit) {
        double value = Double.parseDouble(amount);
        boolean seconds = unit.startsWith("s");
        return Math.round(seconds ? value * 1000 : value);
    }
}
