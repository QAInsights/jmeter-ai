package org.qainsights.jmeter.ai.sla;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
            "(?<!not )(?:under|below|less than|lower than|within|at most|no more than|not more than|up to|max(?:imum)?(?: of)?|<=?)"
                    + "\\s*(\\d+(?:\\.\\d+)?)\\s*(ms|msec|millis(?:econds?)?|milliseconds?|s|secs?|seconds?)\\b");
    private static final Pattern STATISTIC = Pattern.compile(
            "\\b(p\\d{1,2}(?:\\.\\d+)?|\\d{1,2}(?:st|nd|rd|th) percentile|avg|average|mean|median)\\b");
    private static final Pattern EXACT_CODE = Pattern.compile(
            "^(?:only\\s+)?(?:http\\s+)?(?:status|response code|code)?\\s*(?:is\\s+|=\\s*|of\\s+)?([1-5]\\d\\d)(?:\\s+only)?$");
    private static final Pattern UNSUPPORTED = Pattern.compile(
            "\\b(error rate|errors? under|errors? below|%|percent|throughput|tps|rps|hits|requests per|apdex)\\b|%");

    private static final Pattern FILLER = Pattern.compile(
            "\\b(response times?|latency|latencies|each|every|all|samples?|requests?|should|must|be|is|are|keep|"
                    + "the|with|max|maximum|times?|of|for|in)\\b");
    private static final Pattern CODE_WORDS = Pattern.compile(
            "\\b(no|zero|without|never|only|any|http|status|response|responses|codes?|errors?|server|client|"
                    + "5xx|4xx|2xx|or|nor|success|successful)\\b");

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
     * "status 200"-style rules instead of a {@link CodeRule}. {@code superseded} lists looser time
     * limits that the strictest one already covers; {@code conflicts} lists response-code rules that
     * contradict each other, in which case no response-code rule is kept.
     */
    public record SlaSpec(long maxMillis, String statistic, CodeRule codeRule, String exactCode,
                          List<String> unsupported, List<String> unrecognized,
                          List<String> superseded, List<String> conflicts) {
        public SlaSpec {
            statistic = statistic == null ? "" : statistic;
            exactCode = exactCode == null ? "" : exactCode;
            unsupported = List.copyOf(unsupported);
            unrecognized = List.copyOf(unrecognized);
            superseded = List.copyOf(superseded);
            conflicts = List.copyOf(conflicts);
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
        String durationLabel = "";
        CodeRule codeRule = null;
        String exactCode = "";
        List<String> unsupported = new ArrayList<>();
        List<String> unrecognized = new ArrayList<>();
        List<String> superseded = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();

        Deque<String> pending = new ArrayDeque<>();
        for (String raw : CLAUSE_SPLIT.split(body.toLowerCase(Locale.ROOT).trim())) {
            pending.add(raw.trim());
        }
        while (!pending.isEmpty()) {
            String raw = pending.poll();
            String clause = raw.replaceAll("\\s+", " ");
            if (clause.isEmpty()) {
                continue;
            }
            if (UNSUPPORTED.matcher(clause).find()) {
                unsupported.add(raw);
                continue;
            }
            Matcher duration = DURATION.matcher(clause);
            if (duration.find()) {
                long millis = toMillis(duration.group(1), duration.group(2));
                Matcher stat = STATISTIC.matcher(clause);
                String clauseStatistic = stat.find() ? stat.group(1) : "";
                String label = (clauseStatistic.isEmpty() ? "" : clauseStatistic + " ") + duration.group().trim();
                if (millis <= 0) {
                    unrecognized.add(label);
                } else if (maxMillis == 0 || millis < maxMillis) {
                    if (maxMillis > 0) {
                        superseded.add(durationLabel);
                    }
                    maxMillis = millis;
                    statistic = clauseStatistic;
                    durationLabel = label;
                } else {
                    superseded.add(label);
                }
                String rest = leftover(clause.substring(0, duration.start()) + " " + clause.substring(duration.end()),
                        STATISTIC, FILLER);
                if (!rest.isEmpty()) {
                    pending.addFirst(rest);
                }
                continue;
            }
            CodeRule rule = codeRule(clause);
            if (rule != null) {
                if (leftover(clause, CODE_WORDS, FILLER).isEmpty()) {
                    codeRule = merge(codeRule, rule);
                } else {
                    unrecognized.add(raw);
                }
                continue;
            }
            Matcher exact = EXACT_CODE.matcher(clause);
            if (exact.matches() && clause.matches(".*\\b(only|status|code|http)\\b.*")) {
                String code = exact.group(1);
                if (!exactCode.isEmpty() && !exactCode.equals(code)) {
                    conflicts.add("status " + exactCode + " vs status " + code);
                } else {
                    exactCode = code;
                }
                continue;
            }
            unrecognized.add(raw);
        }
        if (codeRule != null && !exactCode.isEmpty()) {
            boolean matches = exactCode.matches(codeRule.pattern());
            if (codeRule.negated() == matches) {
                conflicts.add("status " + exactCode + " vs " + codeRule.description());
            }
            codeRule = null;
        }
        if (!conflicts.isEmpty()) {
            codeRule = null;
            exactCode = "";
        }
        return new SlaSpec(maxMillis, statistic, codeRule, exactCode, unsupported, unrecognized,
                superseded, conflicts);
    }

    private static String leftover(String text, Pattern... remove) {
        String rest = text;
        for (Pattern pattern : remove) {
            rest = pattern.matcher(rest).replaceAll(" ");
        }
        return rest.replaceAll("\\s+", " ").trim();
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
        if (no) {
            return null;
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

    /** Rounds down so a fractional limit is never loosened. */
    private static long toMillis(String amount, String unit) {
        double value = Double.parseDouble(amount);
        boolean seconds = unit.startsWith("s");
        return (long) Math.floor(seconds ? value * 1000 : value);
    }
}
