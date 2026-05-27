package org.qainsights.jmeter.ai.correlation;

import org.qainsights.jmeter.ai.utils.AiConfig;

import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class CorrelationConfig {

    private final Set<String> knownTokens;
    private final List<CustomPattern> customPatterns;
    private final Set<String> excludeValues;
    private final int minValueLength;
    private final double minEntropy;

    public CorrelationConfig() {
        this.knownTokens = parseKnownTokens();
        this.customPatterns = parseCustomPatterns();
        this.excludeValues = parseExcludeValues();
        this.minValueLength = parseInt("jmeter.ai.correlation.min_value_length", 3);
        this.minEntropy = parseDouble("jmeter.ai.correlation.min_entropy", 2.8);
    }

    public Set<String> getKnownTokens() { return knownTokens; }
    public List<CustomPattern> getCustomPatterns() { return customPatterns; }
    public Set<String> getExcludeValues() { return excludeValues; }
    public int getMinValueLength() { return minValueLength; }
    public double getMinEntropy() { return minEntropy; }

    private Set<String> parseKnownTokens() {
        String raw = AiConfig.getProperty("jmeter.ai.correlation.known_tokens",
                "JSESSIONID,jsessionid,sessionId,_sourcePage,__fp,_token,csrf,access_token,id_token,code,state,nonce");
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                tokens.add(trimmed.toLowerCase(Locale.ROOT));
            }
        }
        return tokens;
    }

    private List<CustomPattern> parseCustomPatterns() {
        List<CustomPattern> patterns = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            String raw = AiConfig.getProperty("jmeter.ai.correlation.custom." + i, null);
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            String[] parts = raw.split("::", 2);
            if (parts.length != 2) {
                continue;
            }
            String name = parts[0].trim();
            String regex = parts[1].trim();
            if (name.isEmpty() || regex.isEmpty()) {
                continue;
            }
            try {
                Pattern.compile(regex);
                patterns.add(new CustomPattern(name, regex));
            } catch (PatternSyntaxException ignored) {
            }
        }
        return patterns;
    }

    private Set<String> parseExcludeValues() {
        String raw = AiConfig.getProperty("jmeter.ai.correlation.exclude_values",
                "true,false,null,undefined,application/json,text/html,utf-8,en-us");
        Set<String> values = new HashSet<>();
        for (String v : raw.split(",")) {
            String trimmed = v.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return values;
    }

    private int parseInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(AiConfig.getProperty(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private double parseDouble(String key, double defaultValue) {
        try {
            return Double.parseDouble(AiConfig.getProperty(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static class CustomPattern {
        private final String name;
        private final Pattern pattern;

        CustomPattern(String name, String regex) {
            this.name = name;
            this.pattern = Pattern.compile(regex);
        }

        public String getName() { return name; }
        public Pattern getPattern() { return pattern; }
    }
}
