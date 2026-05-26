package org.qainsights.jmeter.ai.correlation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects dynamic values (tokens, session IDs, UUIDs, JWTs, etc.) within HTTP
 * sample responses. All detection patterns are externalised and can be
 * customised through JMeter properties via {@link DynamicValueDetectorConfig}.
 */
public class DynamicValueDetector {

    // Compiled patterns (loaded from configuration)
    private final Pattern uuidPattern;
    private final Pattern jwtPattern;
    private final Pattern base64Pattern;
    private final Pattern longHexPattern;
    private final Pattern knownTokenPattern;
    private final Pattern setCookiePattern;
    private final Pattern keyValuePattern;
    private final Pattern hiddenInputPattern;
    private final Pattern quotedValuePattern;
    private final Set<String> staticStrings;
    private final Set<String> dictionaryWords;
    /** User-defined custom patterns: token name → compiled pattern. */
    private final Map<String, Pattern> customPatterns;

    /**
     * Creates a detector using the default configuration (reads JMeter properties
     * and falls back to built-in defaults).
     */
    public DynamicValueDetector() {
        this(new DynamicValueDetectorConfig());
    }

    /**
     * Creates a detector backed by the given configuration. Useful for testing
     * or when a pre-built configuration is available.
     *
     * @param config the configuration to use; must not be {@code null}
     */
    public DynamicValueDetector(DynamicValueDetectorConfig config) {
        this.uuidPattern = config.getUuidPattern();
        this.jwtPattern = config.getJwtPattern();
        this.base64Pattern = config.getBase64Pattern();
        this.longHexPattern = config.getHexPattern();
        this.knownTokenPattern = config.getKnownTokenPattern();
        this.setCookiePattern = config.getSetCookiePattern();
        this.keyValuePattern = config.getKeyValuePattern();
        this.hiddenInputPattern = config.getHiddenInputPattern();
        this.quotedValuePattern = config.getQuotedValuePattern();
        this.staticStrings = config.getStaticStrings();
        this.dictionaryWords = config.getDictionaryWords();
        this.customPatterns = config.getCustomPatterns();
    }

    public List<DetectedValue> detect(SampleRecord sample) {
        Set<String> seen = new HashSet<>();
        List<DetectedValue> values = new ArrayList<>();
        collectFromKnownNames(sample.getResponseHeaders(), "Response Header", values, seen);
        collectFromKnownNames(sample.getResponseBody(), "Response Body", values, seen);
        collectHiddenInputs(sample.getResponseBody(), values, seen);
        collectCookies(sample.getResponseHeaders(), values, seen);
        collectPattern(uuidPattern, sample.getResponseHeaders(), "Response Header", "uuid", values, seen);
        collectPattern(uuidPattern, sample.getResponseBody(), "Response Body", "uuid", values, seen);
        collectPattern(jwtPattern, sample.getResponseHeaders(), "Response Header", "jwt", values, seen);
        collectPattern(jwtPattern, sample.getResponseBody(), "Response Body", "jwt", values, seen);
        collectPattern(base64Pattern, sample.getResponseHeaders(), "Response Header", "base64", values, seen);
        collectPattern(base64Pattern, sample.getResponseBody(), "Response Body", "base64", values, seen);
        collectPattern(longHexPattern, sample.getResponseHeaders(), "Response Header", "hex", values, seen);
        collectPattern(longHexPattern, sample.getResponseBody(), "Response Body", "hex", values, seen);
        collectEntropyValues(sample.getResponseHeaders(), "Response Header", values, seen);
        collectEntropyValues(sample.getResponseBody(), "Response Body", values, seen);
        // Apply user-defined custom patterns to both headers and body
        for (Map.Entry<String, Pattern> entry : customPatterns.entrySet()) {
            collectPattern(entry.getValue(), sample.getResponseHeaders(), "Response Header", entry.getKey(), values, seen);
            collectPattern(entry.getValue(), sample.getResponseBody(), "Response Body", entry.getKey(), values, seen);
        }
        return values;
    }

    private void collectFromKnownNames(String text, String location, List<DetectedValue> values, Set<String> seen) {
        collectNamedPattern(knownTokenPattern, text, location, values, seen);
        collectNamedPattern(keyValuePattern, text, location, values, seen);
    }

    private void collectCookies(String text, List<DetectedValue> values, Set<String> seen) {
        Matcher matcher = setCookiePattern.matcher(safe(text));
        while (matcher.find()) {
            addValue(matcher.group(2), "Response Header: Set-Cookie " + matcher.group(1), matcher.group(1), values, seen);
        }
    }

    private void collectHiddenInputs(String text, List<DetectedValue> values, Set<String> seen) {
        Matcher matcher = hiddenInputPattern.matcher(safe(text));
        while (matcher.find()) {
            addValue(matcher.group(2), "Response Body: hidden input " + matcher.group(1), matcher.group(1), values, seen);
        }
    }

    private void collectNamedPattern(Pattern pattern, String text, String location, List<DetectedValue> values, Set<String> seen) {
        Matcher matcher = pattern.matcher(safe(text));
        while (matcher.find()) {
            addValue(matcher.group(2), location + ": " + matcher.group(1), matcher.group(1), values, seen);
        }
    }

    private void collectPattern(Pattern pattern, String text, String location, String tokenName, List<DetectedValue> values, Set<String> seen) {
        Matcher matcher = pattern.matcher(safe(text));
        while (matcher.find()) {
            addValue(matcher.group(), location, tokenName, values, seen);
        }
    }

    private void collectEntropyValues(String text, String location, List<DetectedValue> values, Set<String> seen) {
        Set<String> candidates = new LinkedHashSet<>();
        Matcher quotedMatcher = quotedValuePattern.matcher(safe(text));
        while (quotedMatcher.find()) {
            candidates.add(quotedMatcher.group(1));
        }
        Matcher keyValueMatcher = keyValuePattern.matcher(safe(text));
        while (keyValueMatcher.find()) {
            candidates.add(keyValueMatcher.group(2));
        }
        for (String candidate : candidates) {
            if (candidate.length() > 10 && shannonEntropy(candidate) > 3.5d) {
                addValue(candidate, location, "dynamic_value", values, seen);
            }
        }
    }

    private void addValue(String value, String location, String tokenName, List<DetectedValue> values, Set<String> seen) {
        String normalized = sanitize(value);
        if (shouldExclude(normalized)) {
            return;
        }
        String key = normalized + "|" + location;
        if (seen.add(key)) {
            values.add(new DetectedValue(normalized, location, tokenName));
        }
    }

    private boolean shouldExclude(String value) {
        if (value.isEmpty()) {
            return true;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (staticStrings.contains(lower)) {
            return true;
        }
        if (value.matches("\\d{1,9}")) {
            return true;
        }
        return value.matches("[A-Za-z]+") && (dictionaryWords.contains(lower) || shannonEntropy(value) < 2.8d);
    }

    private static double shannonEntropy(String value) {
        if (value == null || value.isEmpty()) {
            return 0d;
        }
        Map<Integer, Long> counts = value.chars().boxed().collect(java.util.stream.Collectors.groupingBy(c -> c, java.util.stream.Collectors.counting()));
        double length = value.length();
        double entropy = 0d;
        for (Long count : counts.values()) {
            double probability = count / length;
            entropy -= probability * (Math.log(probability) / Math.log(2));
        }
        return entropy;
    }

    private static String sanitize(String value) {
        return safe(value).trim().replaceAll("^[\\\"']|[\\\"']$", "");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
