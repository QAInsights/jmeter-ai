package org.qainsights.jmeter.ai.load;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure parser for the load targets and timing values supported by {@code @load}. */
public final class WorkloadParser {
    private static final String NUMBER = "(\\d+(?:\\.\\d+)?(?:k|m(?!s\\b))?)";
    private static final String UNIT = "(milliseconds?|msecs?|ms|seconds?|secs?|s|minutes?|mins?|m|hours?|hrs?|h)";
    private static final String DURATION = NUMBER + "\\s*-?\\s*" + UNIT + "\\b";
    private static final Pattern THOUSANDS = Pattern.compile("(\\d),(\\d{3})");
    private static final Pattern FRAGMENT_SPLIT = Pattern.compile("\\s*(?:,|;|\\band\\b|\\bwith\\b)\\s*");
    private static final Pattern STEP_SHAPE = Pattern.compile(
            "(?i)\\bstep\\s+" + NUMBER + "\\s+to\\s+" + NUMBER + "\\s+(rps|users?)"
                    + "\\s+by\\s+" + NUMBER + "\\s+every\\s+" + DURATION);
    private static final Pattern SPIKE_SHAPE = Pattern.compile(
            "(?i)\\bspike\\s+from\\s+" + NUMBER + "\\s+to\\s+" + NUMBER + "\\s+(rps|users?)"
                    + "\\s+for\\s+" + DURATION);
    private static final Pattern STRESS_SHAPE = Pattern.compile(
            "(?i)\\bstress\\s+up\\s+to\\s+" + NUMBER + "\\s+(rps|users?)\\s+step\\s+"
                    + NUMBER + "\\s+every\\s+" + DURATION);
    private static final Pattern RAMP_DOWN = Pattern.compile("(?i)\\bramp\\s*[- ]?\\s*down\\s+(" + DURATION + ")");
    private static final Pattern REQUEST_RATE = Pattern.compile(
            "(?i)(?<![\\w.])" + NUMBER
                    + "\\s*(rps|tps|qps|rpm|rph|requests?\\s*/\\s*(?:s|sec|secs|second|seconds|min|mins|minute|minutes|h|hr|hrs|hour|hours)"
                    + "|req\\s*/\\s*(?:s|sec|secs|second|seconds|min|mins|minute|minutes|h|hr|hrs|hour|hours)"
                    + "|hits\\s*/\\s*(?:s|sec|secs|second|seconds|min|mins|minute|minutes|h|hr|hrs|hour|hours)"
                    + "|transactions?\\s*/\\s*(?:s|sec|secs|second|seconds|min|mins|minute|minutes|h|hr|hrs|hour|hours)"
                    + "|requests?\\s+per\\s+(?:second|minute|hour)"
                    + "|req\\s+per\\s+(?:second|minute|hour)"
                    + "|hits\\s+per\\s+(?:second|minute|hour)"
                    + "|queries?\\s*/\\s*(?:s|sec|secs|second|seconds)"
                    + "|queries?\\s+per\\s+(?:second|minute|hour)"
                    + "|transactions?\\s+per\\s+(?:second|minute|hour))\\b");
    private static final Pattern MESSAGE_RATE = Pattern.compile(
            "(?i)(?<![\\w.])" + NUMBER
                    + "\\s*(?:msg\\s*/\\s*(?:s|sec|second)|messages?\\s*(?:/\\s*(?:s|sec|second)|per\\s+second))\\b");
    private static final Pattern ITERATION_RATE = Pattern.compile(
            "(?i)(?<![\\w.])" + NUMBER
                    + "\\s*(iterations?|journeys?|sessions?|arrivals?|users?)\\s+per\\s+"
                    + "(seconds?|secs?|s|minutes?|mins?|m|hours?|hrs?|h)\\b");
    private static final Pattern USERS = Pattern.compile(
            "(?i)(?<![\\w.])" + NUMBER + "\\s+(?:(?:concurrent|virtual)\\s+)?"
                    + "(users?|threads?|vus)(?!\\s+per\\b)\\b");
    private static final Pattern RAMP_PREFIX = Pattern.compile(
            "(?i)\\bramp\\s*[- ]?\\s*up\\s*(?:(?:of|over|in)\\s+)?(" + DURATION + ")");
    private static final Pattern RAMP_SUFFIX = Pattern.compile("(?i)" + DURATION + "\\s+ramp\\s*[- ]?\\s*up\\b");
    private static final Pattern HOLD_PREFIX = Pattern.compile(
            "(?i)\\b(?:for|hold(?:ing)?|steady\\s+state(?:\\s+for)?|soak(?:\\s+for)?|duration)\\s+(" + DURATION + ")");
    private static final Pattern HOLD_SUFFIX = Pattern.compile(
            "(?i)" + DURATION + "\\s+(?:soak|steady\\s+state|hold)\\b");
    private static final Pattern THINK_PREFIX = Pattern.compile(
            "(?i)\\bthink(?:\\s+time)?\\s+(" + DURATION + ")");
    private static final Pattern THINK_SUFFIX = Pattern.compile(
            "(?i)" + DURATION + "\\s+think(?:\\s+time)?\\b");
    private static final Pattern PAUSE = Pattern.compile("(?i)\\bpause\\s+(" + DURATION + ")");
    private static final Pattern RESPONSE_PREFIX = Pattern.compile(
            "(?i)\\b(?:(?:avg|average)\\s+)?(?:response(?:\\s+time)?|latency|rt)"
                    + "(?:\\s+(?:of|around|about)|\\s*~)?\\s*(" + DURATION + ")");
    private static final Pattern RESPONSE_SUFFIX = Pattern.compile(
            "(?i)" + DURATION + "\\s+response(?:\\s+time)?\\b");
    private static final Pattern AGENTS = Pattern.compile(
            "(?i)\\b(?:across|on|over|using)\\s+" + NUMBER
                    + "\\s+(agents?|generators?|engines?|machines?|injectors?|servers?|nodes?)\\b");
    private static final Pattern GENERATOR_VCPUS = Pattern.compile(
            "(?i)(?<![\\w.])" + NUMBER + "\\s*(?:vcpus?|cpus?|cores?)\\b");
    private static final Pattern GENERATOR_RAM = Pattern.compile(
            "(?i)(?<![\\w.])" + NUMBER + "\\s*gb(?:\\s*(?:ram|memory))?\\b");

    private WorkloadParser() {
    }

    /** Parses a message, ignoring a leading {@code @load} token. */
    public static WorkloadSpec parse(String message) {
        String body = message == null ? "" : message.trim();
        if (body.matches("(?is)^@load\\b.*")) {
            body = body.replaceFirst("(?is)^@load\\b", "").trim();
        }
        body = normalizeThousands(body).toLowerCase(Locale.ROOT);

        Values values = new Values();
        ShapeParse shapeParse = parseShape(body, values);
        body = shapeParse.remaining();
        List<String> unsupported = new ArrayList<>();
        List<String> unrecognized = new ArrayList<>();
        for (String raw : FRAGMENT_SPLIT.split(body)) {
            String fragment = raw.trim();
            if (fragment.isEmpty()) {
                continue;
            }
            String remaining = fragment;
            remaining = consumeMessageRate(remaining, values);
            remaining = consumeRate(remaining, values);
            remaining = consumeIterationRate(remaining, values);
            remaining = consumeUsers(remaining, values);
            remaining = consumeRamp(remaining, values);
            remaining = consumeHold(remaining, values);
            remaining = consumeThinkTime(remaining, values);
            remaining = consumeResponseTime(remaining, values);
            remaining = consumeAgents(remaining, values);
            remaining = consumeGeneratorVcpus(remaining, values);
            remaining = consumeGeneratorRam(remaining, values);
            String leftover = remaining.replaceAll("[\\s:]+", " ").trim();
            if (!leftover.isEmpty()) {
                unrecognized.add(leftover);
            }
        }
        return new WorkloadSpec(values.requestsPerSecond, values.messagesPerSecond,
                values.iterationsPerSecond, values.users,
                values.rampSeconds, values.holdSeconds, values.thinkMillis, values.responseMillis,
                values.agents, values.generatorVcpus, values.generatorRamGb,
                unsupported, unrecognized, values.duplicates, shapeParse.config());
    }

    private static ShapeParse parseShape(String body, Values values) {
        WorkloadShape shape = WorkloadShape.LOAD;
        WorkloadShapeConfig config = WorkloadShapeConfig.load();
        String remaining = body;
        Matcher rampDown = RAMP_DOWN.matcher(remaining);
        Long rampDownSeconds = null;
        if (rampDown.find()) {
            rampDownSeconds = (long) Math.ceil(durationMillis(rampDown.group(2), rampDown.group(3)) / 1000.0);
            remaining = removeMatch(remaining, rampDown.start(), rampDown.end());
        }
        Matcher match = STEP_SHAPE.matcher(remaining);
        if (match.find()) {
            shape = WorkloadShape.STEP;
            double start = number(match.group(1));
            double end = number(match.group(2));
            double step = number(match.group(4));
            long duration = (long) Math.ceil(durationMillis(match.group(5), match.group(6)) / 1000.0);
            config = shapeConfig(shape, match.group(3), start, end, step, duration,
                    null, null, null, null, null, rampDownSeconds);
            setParsedShapeTarget(values, match.group(3), end);
            remaining = removeMatch(remaining, match.start(), match.end());
        } else {
            match = SPIKE_SHAPE.matcher(remaining);
            if (match.find()) {
                shape = WorkloadShape.SPIKE;
                double baseline = number(match.group(1));
                double peak = number(match.group(2));
                long duration = (long) Math.ceil(durationMillis(match.group(4), match.group(5)) / 1000.0);
                config = shapeConfig(shape, match.group(3), null, null, null, null,
                        baseline, peak, 60L, duration, 60L, rampDownSeconds);
                setParsedShapeTarget(values, match.group(3), peak);
                remaining = removeMatch(remaining, match.start(), match.end());
            } else {
                match = STRESS_SHAPE.matcher(remaining);
                if (match.find()) {
                    shape = WorkloadShape.STRESS;
                    double ceiling = number(match.group(1));
                    double step = number(match.group(3));
                    long duration = (long) Math.ceil(durationMillis(match.group(4), match.group(5)) / 1000.0);
                    config = shapeConfig(shape, match.group(2), 100.0, ceiling, step, duration,
                            null, null, null, null, null, rampDownSeconds);
                    setParsedShapeTarget(values, match.group(2), ceiling);
                    remaining = removeMatch(remaining, match.start(), match.end());
                } else if (remaining.matches("(?is)^\\s*(?:endurance|soak)\\b.*")) {
                    shape = WorkloadShape.ENDURANCE;
                    remaining = remaining.replaceFirst("(?is)^\\s*(?:endurance|soak)\\b", " ");
                    config = new WorkloadShapeConfig(shape, null, null, null, null, null,
                            null, null, null, null, null, List.of(), rampDownSeconds, ArrivalMode.RANDOM);
                }
            }
        }
        if (shape == WorkloadShape.LOAD && rampDownSeconds != null) {
            config = new WorkloadShapeConfig(shape, null, null, null, null, null,
                    null, null, null, null, null, List.of(), rampDownSeconds, ArrivalMode.RANDOM);
        } else if (rampDownSeconds != null && config.rampDownSeconds() == null) {
            config = new WorkloadShapeConfig(shape, config.startTarget(), config.endTarget(),
                    config.stepTarget(), config.stepDurationSeconds(), config.rampSeconds(),
                    config.baselineTarget(), config.peakTarget(), config.warmupSeconds(),
                    config.spikeSeconds(), config.recoverySeconds(), config.customStages(),
                    rampDownSeconds, config.arrivalMode());
        }
        return new ShapeParse(remaining, config);
    }

    private static WorkloadShapeConfig shapeConfig(WorkloadShape shape, String unit,
                                                   Double start, Double end, Double step, Long stepDuration,
                                                   Double baseline, Double peak, Long warmup,
                                                   Long spike, Long recovery, Long rampDown) {
        return new WorkloadShapeConfig(shape, start, end, step, stepDuration, null,
                baseline, peak, warmup, spike, recovery, List.of(), rampDown, ArrivalMode.RANDOM);
    }

    private static void setParsedShapeTarget(Values values, String unit, double target) {
        if (unit.startsWith("user")) {
            values.set("users", (int) Math.ceil(target), () -> values.users = (int) Math.ceil(target));
        } else {
            values.set("requests per second", target,
                    () -> values.requestsPerSecond = target);
        }
    }

    private static String removeMatch(String text, int start, int end) {
        return text.substring(0, start) + " ".repeat(end - start) + text.substring(end);
    }

    private record ShapeParse(String remaining, WorkloadShapeConfig config) {
    }

    private static String normalizeThousands(String text) {
        String normalized = text;
        String previous;
        do {
            previous = normalized;
            normalized = THOUSANDS.matcher(normalized).replaceAll("$1$2");
        } while (!normalized.equals(previous));
        return normalized;
    }

    private static String consumeRate(String text, Values values) {
        Matcher matcher = REQUEST_RATE.matcher(text);
        StringBuilder rest = new StringBuilder();
        int cursor = 0;
        while (matcher.find()) {
            rest.append(text, cursor, matcher.start());
            rest.append(" ".repeat(matcher.end() - matcher.start()));
            cursor = matcher.end();
            String unit = matcher.group(2).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
            double rate = number(matcher.group(1));
            if (unit.endsWith("minute") || unit.endsWith("minutes") || unit.endsWith("min")
                    || unit.endsWith("mins") || unit.equals("rpm")) {
                rate /= 60.0;
            } else if (unit.endsWith("hour") || unit.endsWith("hours") || unit.endsWith("hr")
                    || unit.endsWith("hrs") || unit.endsWith("/h") || unit.equals("rph")) {
                rate /= 3600.0;
            }
            double parsedRate = rate;
            values.set("requests per second", parsedRate, () -> values.requestsPerSecond = parsedRate);
        }
        rest.append(text.substring(cursor));
        return rest.toString();
    }

    private static String consumeMessageRate(String text, Values values) {
        Matcher matcher = MESSAGE_RATE.matcher(text);
        StringBuilder rest = new StringBuilder();
        int cursor = 0;
        while (matcher.find()) {
            rest.append(text, cursor, matcher.start());
            rest.append(" ".repeat(matcher.end() - matcher.start()));
            cursor = matcher.end();
            double rate = number(matcher.group(1));
            values.set("messages per second", rate, () -> values.messagesPerSecond = rate);
        }
        rest.append(text.substring(cursor));
        return rest.toString();
    }

    private static String consumeIterationRate(String text, Values values) {
        Matcher matcher = ITERATION_RATE.matcher(text);
        StringBuilder rest = new StringBuilder();
        int cursor = 0;
        while (matcher.find()) {
            rest.append(text, cursor, matcher.start());
            rest.append(" ".repeat(matcher.end() - matcher.start()));
            cursor = matcher.end();
            double rate = number(matcher.group(1));
            String unit = matcher.group(3);
            if (unit.startsWith("m")) {
                rate /= 60.0;
            } else if (unit.startsWith("h")) {
                rate /= 3600.0;
            }
            double parsedRate = rate;
            values.set("iterations per second", parsedRate, () -> values.iterationsPerSecond = parsedRate);
        }
        rest.append(text.substring(cursor));
        return rest.toString();
    }

    private static String consumeUsers(String text, Values values) {
        Matcher matcher = USERS.matcher(text);
        StringBuilder rest = new StringBuilder();
        int cursor = 0;
        while (matcher.find()) {
            rest.append(text, cursor, matcher.start());
            rest.append(" ".repeat(matcher.end() - matcher.start()));
            cursor = matcher.end();
            int count = (int) Math.ceil(number(matcher.group(1)));
            values.set("users", count, () -> values.users = count);
        }
        rest.append(text.substring(cursor));
        return rest.toString();
    }

    private static String consumeRamp(String text, Values values) {
        return consumeDuration(text, values, "ramp-up", LongValue.RAMP, RAMP_PREFIX, RAMP_SUFFIX);
    }

    private static String consumeHold(String text, Values values) {
        return consumeDuration(text, values, "hold", LongValue.HOLD, HOLD_PREFIX, HOLD_SUFFIX);
    }

    private static String consumeThinkTime(String text, Values values) {
        return consumeDuration(text, values, "think time", LongValue.THINK, THINK_PREFIX, THINK_SUFFIX, PAUSE);
    }

    private static String consumeResponseTime(String text, Values values) {
        return consumeDuration(text, values, "response time", LongValue.RESPONSE, RESPONSE_PREFIX, RESPONSE_SUFFIX);
    }

    private static String consumeDuration(String text, Values values, String name, LongValue target,
                                          Pattern... patterns) {
        String remaining = text;
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(remaining);
            StringBuilder rest = new StringBuilder();
            int cursor = 0;
            while (matcher.find()) {
                rest.append(remaining, cursor, matcher.start());
                rest.append(" ".repeat(matcher.end() - matcher.start()));
                cursor = matcher.end();
                int numberGroup = matcher.groupCount() >= 3 ? 2 : 1;
                double millis = durationMillis(matcher.group(numberGroup), matcher.group(numberGroup + 1));
                long value = target == LongValue.THINK || target == LongValue.RESPONSE
                        ? (long) Math.ceil(millis) : (long) Math.ceil(millis / 1000.0);
                values.set(name, value, () -> values.put(target, value));
            }
            rest.append(remaining.substring(cursor));
            remaining = rest.toString();
        }
        return remaining;
    }

    private static String consumeAgents(String text, Values values) {
        Matcher matcher = AGENTS.matcher(text);
        StringBuilder rest = new StringBuilder();
        int cursor = 0;
        while (matcher.find()) {
            rest.append(text, cursor, matcher.start());
            rest.append(" ".repeat(matcher.end() - matcher.start()));
            cursor = matcher.end();
            int count = (int) Math.ceil(number(matcher.group(1)));
            values.set("agents", count, () -> values.agents = count);
        }
        rest.append(text.substring(cursor));
        return rest.toString();
    }

    private static String consumeGeneratorVcpus(String text, Values values) {
        Matcher matcher = GENERATOR_VCPUS.matcher(text);
        StringBuilder rest = new StringBuilder();
        int cursor = 0;
        while (matcher.find()) {
            rest.append(text, cursor, matcher.start());
            rest.append(" ".repeat(matcher.end() - matcher.start()));
            cursor = matcher.end();
            double parsed = number(matcher.group(1));
            int count = (int) Math.ceil(parsed);
            values.set("generator vCPUs", count, () -> values.generatorVcpus = count);
        }
        rest.append(text.substring(cursor));
        return rest.toString();
    }

    private static String consumeGeneratorRam(String text, Values values) {
        Matcher matcher = GENERATOR_RAM.matcher(text);
        StringBuilder rest = new StringBuilder();
        int cursor = 0;
        while (matcher.find()) {
            rest.append(text, cursor, matcher.start());
            rest.append(" ".repeat(matcher.end() - matcher.start()));
            cursor = matcher.end();
            double amount = number(matcher.group(1));
            values.set("generator RAM", amount, () -> values.generatorRamGb = amount);
        }
        rest.append(text.substring(cursor));
        return rest.toString();
    }

    private static double durationMillis(String amount, String unit) {
        double value = number(amount);
        String normalized = unit.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("ms") || normalized.startsWith("msec") || normalized.startsWith("millisecond")) {
            return value;
        }
        if (normalized.startsWith("s")) {
            return value * 1000;
        }
        if (normalized.startsWith("m")) {
            return value * 60_000;
        }
        return value * 3_600_000;
    }

    private static double number(String value) {
        char suffix = Character.toLowerCase(value.charAt(value.length() - 1));
        double scale = suffix == 'k' ? 1_000 : suffix == 'm' ? 1_000_000 : 1;
        String numeric = scale == 1 ? value : value.substring(0, value.length() - 1);
        return Double.parseDouble(numeric) * scale;
    }

    @FunctionalInterface
    private interface Assignment {
        void set();
    }

    private enum LongValue {
        RAMP, HOLD, THINK, RESPONSE
    }

    private static final class Values {
        private Double requestsPerSecond;
        private Double messagesPerSecond;
        private Double iterationsPerSecond;
        private Integer users;
        private Long rampSeconds;
        private Long holdSeconds;
        private Long thinkMillis;
        private Long responseMillis;
        private Integer agents;
        private Integer generatorVcpus;
        private Double generatorRamGb;
        private final List<String> duplicates = new ArrayList<>();

        private void set(String field, Object value, Assignment assignment) {
            Object previous = switch (field) {
                case "requests per second" -> requestsPerSecond;
                case "messages per second" -> messagesPerSecond;
                case "iterations per second" -> iterationsPerSecond;
                case "users" -> users;
                case "ramp-up" -> rampSeconds;
                case "hold" -> holdSeconds;
                case "think time" -> thinkMillis;
                case "response time" -> responseMillis;
                case "agents" -> agents;
                case "generator vCPUs" -> generatorVcpus;
                case "generator RAM" -> generatorRamGb;
                default -> null;
            };
            if (previous != null) {
                duplicates.add("Repeated " + field + " value; the last value wins.");
            }
            assignment.set();
        }

        private void put(LongValue target, long value) {
            switch (target) {
                case RAMP -> rampSeconds = value;
                case HOLD -> holdSeconds = value;
                case THINK -> thinkMillis = value;
                case RESPONSE -> responseMillis = value;
            }
        }
    }
}
