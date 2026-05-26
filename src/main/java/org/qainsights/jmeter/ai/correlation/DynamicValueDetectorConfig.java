package org.qainsights.jmeter.ai.correlation;

import org.qainsights.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Configuration holder for {@link DynamicValueDetector} patterns.
 * <p>
 * All regex patterns, static strings, and dictionary words used during dynamic value detection
 * can be customized via JMeter properties. If a property is not defined or contains an invalid
 * regex, the system falls back to the built-in default values.
 * </p>
 *
 * <h3>Property Keys</h3>
 * <ul>
 *   <li>{@code jmeter.ai.correlation.pattern.uuid} - UUID regex pattern</li>
 *   <li>{@code jmeter.ai.correlation.pattern.jwt} - JWT regex pattern</li>
 *   <li>{@code jmeter.ai.correlation.pattern.base64} - Base64 regex pattern</li>
 *   <li>{@code jmeter.ai.correlation.pattern.hex} - Long hex string regex pattern</li>
 *   <li>{@code jmeter.ai.correlation.pattern.known_token} - Known token name regex pattern</li>
 *   <li>{@code jmeter.ai.correlation.pattern.set_cookie} - Set-Cookie header regex pattern</li>
 *   <li>{@code jmeter.ai.correlation.pattern.key_value} - Key-value pair regex pattern</li>
 *   <li>{@code jmeter.ai.correlation.pattern.hidden_input} - Hidden HTML input regex pattern</li>
 *   <li>{@code jmeter.ai.correlation.pattern.quoted_value} - Quoted value regex pattern</li>
 *   <li>{@code jmeter.ai.correlation.static_strings} - Comma-separated list of static strings to exclude</li>
 *   <li>{@code jmeter.ai.correlation.dictionary_words} - Comma-separated list of dictionary words to exclude</li>
 * </ul>
 *
 * <h3>Custom Patterns</h3>
 * <p>
 * Users can define additional detection patterns without modifying source code by using
 * an indexed property convention:
 * </p>
 * <pre>
 * jmeter.ai.correlation.pattern.custom.1.name=tracking_id
 * jmeter.ai.correlation.pattern.custom.1.regex=[A-Z0-9]{8}-[A-Z0-9]{8}
 * jmeter.ai.correlation.pattern.custom.2.name=auth_code
 * jmeter.ai.correlation.pattern.custom.2.regex=AUTH-[0-9a-f]{16}
 * </pre>
 * <p>
 * The index starts at 1 and must be consecutive. Each custom pattern requires both a
 * {@code name} (used as the token type in detection results) and a {@code regex}.
 * Invalid regex entries are skipped with a warning.
 * </p>
 */
public class DynamicValueDetectorConfig {

    private static final Logger log = LoggerFactory.getLogger(DynamicValueDetectorConfig.class);

    // ──────────────────────────────────────────────
    // Property key constants
    // ──────────────────────────────────────────────
    public static final String PROP_UUID_PATTERN = "jmeter.ai.correlation.pattern.uuid";
    public static final String PROP_JWT_PATTERN = "jmeter.ai.correlation.pattern.jwt";
    public static final String PROP_BASE64_PATTERN = "jmeter.ai.correlation.pattern.base64";
    public static final String PROP_HEX_PATTERN = "jmeter.ai.correlation.pattern.hex";
    public static final String PROP_KNOWN_TOKEN_PATTERN = "jmeter.ai.correlation.pattern.known_token";
    public static final String PROP_SET_COOKIE_PATTERN = "jmeter.ai.correlation.pattern.set_cookie";
    public static final String PROP_KEY_VALUE_PATTERN = "jmeter.ai.correlation.pattern.key_value";
    public static final String PROP_HIDDEN_INPUT_PATTERN = "jmeter.ai.correlation.pattern.hidden_input";
    public static final String PROP_QUOTED_VALUE_PATTERN = "jmeter.ai.correlation.pattern.quoted_value";
    public static final String PROP_STATIC_STRINGS = "jmeter.ai.correlation.static_strings";
    public static final String PROP_DICTIONARY_WORDS = "jmeter.ai.correlation.dictionary_words";

    /** Prefix for user-defined custom patterns. Index starts at 1 (e.g. {@code ...custom.1.name}). */
    public static final String PROP_CUSTOM_PATTERN_PREFIX = "jmeter.ai.correlation.pattern.custom.";

    // ──────────────────────────────────────────────
    // Default regex pattern strings
    // ──────────────────────────────────────────────
    public static final String DEFAULT_UUID_PATTERN =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    public static final String DEFAULT_JWT_PATTERN =
            "[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}";
    public static final String DEFAULT_BASE64_PATTERN =
            "[A-Za-z0-9+/]{20,}={0,2}";
    public static final String DEFAULT_HEX_PATTERN =
            "[0-9a-fA-F]{16,}";
    public static final String DEFAULT_KNOWN_TOKEN_PATTERN =
            "(?i)(JSESSIONID|jsessionid|sessionId|_sourcePage|__fp|_token|csrf|access_token|id_token|code|state|nonce)[\\\"'\\s:=]+([A-Za-z0-9._~+/=-]{3,})";
    public static final String DEFAULT_SET_COOKIE_PATTERN =
            "(?i)^Set-Cookie\\s*:\\s*([^=;\\s]+)=([^;\\s]+)";
    public static final String DEFAULT_KEY_VALUE_PATTERN =
            "(?i)[\\\"']?([A-Za-z0-9_.-]*(?:token|session|csrf|nonce|state|code|sourcepage|__fp)[A-Za-z0-9_.-]*)[\\\"']?\\s*[:=]\\s*[\\\"']?([^\\\"'&;,<>\\s]{3,})";
    public static final String DEFAULT_HIDDEN_INPUT_PATTERN =
            "(?i)<input\\b(?=[^>]*\\btype=[\\\"']?hidden[\\\"']?)(?=[^>]*\\bname=[\\\"']([^\\\"']+)[\\\"'])(?=[^>]*\\bvalue=[\\\"']([^\\\"']*)[\\\"'])[^>]*>";
    public static final String DEFAULT_QUOTED_VALUE_PATTERN =
            "[\\\"']([A-Za-z0-9._~+/=-]{11,})[\\\"']";

    // ──────────────────────────────────────────────
    // Default static strings and dictionary words
    // ──────────────────────────────────────────────
    public static final String DEFAULT_STATIC_STRINGS =
            "application/json,application/xml,text/html,text/plain,en-us,true,false,null,utf-8";
    public static final String DEFAULT_DICTIONARY_WORDS =
            "success,failure,message,status,content,application,language,response,request,cookie,header,session,token,login,logout";

    // ──────────────────────────────────────────────
    // Compiled patterns (loaded once at construction)
    // ──────────────────────────────────────────────
    private final Pattern uuidPattern;
    private final Pattern jwtPattern;
    private final Pattern base64Pattern;
    private final Pattern hexPattern;
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
     * Creates a configuration instance by reading patterns from JMeter properties.
     * Falls back to built-in defaults for any missing or invalid property values.
     */
    public DynamicValueDetectorConfig() {
        this.uuidPattern = compilePattern(PROP_UUID_PATTERN, DEFAULT_UUID_PATTERN, 0);
        this.jwtPattern = compilePattern(PROP_JWT_PATTERN, DEFAULT_JWT_PATTERN, 0);
        this.base64Pattern = compilePattern(PROP_BASE64_PATTERN, DEFAULT_BASE64_PATTERN, 0);
        this.hexPattern = compilePattern(PROP_HEX_PATTERN, DEFAULT_HEX_PATTERN, 0);
        this.knownTokenPattern = compilePattern(PROP_KNOWN_TOKEN_PATTERN, DEFAULT_KNOWN_TOKEN_PATTERN, 0);
        this.setCookiePattern = compilePattern(PROP_SET_COOKIE_PATTERN, DEFAULT_SET_COOKIE_PATTERN, Pattern.MULTILINE);
        this.keyValuePattern = compilePattern(PROP_KEY_VALUE_PATTERN, DEFAULT_KEY_VALUE_PATTERN, 0);
        this.hiddenInputPattern = compilePattern(PROP_HIDDEN_INPUT_PATTERN, DEFAULT_HIDDEN_INPUT_PATTERN, 0);
        this.quotedValuePattern = compilePattern(PROP_QUOTED_VALUE_PATTERN, DEFAULT_QUOTED_VALUE_PATTERN, 0);
        this.staticStrings = loadStringSet(PROP_STATIC_STRINGS, DEFAULT_STATIC_STRINGS);
        this.dictionaryWords = loadStringSet(PROP_DICTIONARY_WORDS, DEFAULT_DICTIONARY_WORDS);
        this.customPatterns = loadCustomPatterns();
    }

    // ──────────────────────────────────────────────
    // Accessors
    // ──────────────────────────────────────────────

    public Pattern getUuidPattern() {
        return uuidPattern;
    }

    public Pattern getJwtPattern() {
        return jwtPattern;
    }

    public Pattern getBase64Pattern() {
        return base64Pattern;
    }

    public Pattern getHexPattern() {
        return hexPattern;
    }

    public Pattern getKnownTokenPattern() {
        return knownTokenPattern;
    }

    public Pattern getSetCookiePattern() {
        return setCookiePattern;
    }

    public Pattern getKeyValuePattern() {
        return keyValuePattern;
    }

    public Pattern getHiddenInputPattern() {
        return hiddenInputPattern;
    }

    public Pattern getQuotedValuePattern() {
        return quotedValuePattern;
    }

    public Set<String> getStaticStrings() {
        return staticStrings;
    }

    public Set<String> getDictionaryWords() {
        return dictionaryWords;
    }

    /**
     * Returns an unmodifiable map of user-defined custom patterns.
     * Keys are token names, values are compiled {@link Pattern} instances.
     */
    public Map<String, Pattern> getCustomPatterns() {
        return customPatterns;
    }

    // ──────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────

    /**
     * Compiles a regex pattern from a JMeter property, falling back to the provided default
     * if the property is not set or contains an invalid regex.
     *
     * @param propertyKey   the JMeter property key to read
     * @param defaultRegex  the fallback regex string
     * @param flags         regex compilation flags (e.g. {@link Pattern#MULTILINE})
     * @return a compiled {@link Pattern}, never {@code null}
     */
    private static Pattern compilePattern(String propertyKey, String defaultRegex, int flags) {
        String configuredRegex = AiConfig.getProperty(propertyKey, null);
        if (configuredRegex != null && !configuredRegex.trim().isEmpty()) {
            try {
                Pattern compiled = Pattern.compile(configuredRegex, flags);
                log.debug("Loaded custom pattern for '{}' from properties", propertyKey);
                return compiled;
            } catch (PatternSyntaxException e) {
                log.warn("Invalid regex configured for property '{}': {}. "
                                + "Falling back to default pattern. Error: {}",
                        propertyKey, configuredRegex, e.getDescription());
            }
        }
        return Pattern.compile(defaultRegex, flags);
    }

    /**
     * Loads a comma-separated list of strings from a JMeter property into an unmodifiable
     * {@link Set}, preserving insertion order. Falls back to the provided default if the
     * property is not set or blank.
     *
     * @param propertyKey  the JMeter property key to read
     * @param defaultValue the fallback comma-separated string
     * @return an unmodifiable set of trimmed, lower-cased strings
     */
    /**
     * Loads user-defined custom patterns from indexed properties.
     * <p>
     * Convention: {@code jmeter.ai.correlation.pattern.custom.<N>.name} and
     * {@code jmeter.ai.correlation.pattern.custom.<N>.regex} where N starts at 1
     * and increments consecutively.
     * </p>
     *
     * @return an unmodifiable map of token name to compiled pattern
     */
    private static Map<String, Pattern> loadCustomPatterns() {
        Map<String, Pattern> result = new LinkedHashMap<>();
        for (int index = 1; ; index++) {
            String name = AiConfig.getProperty(PROP_CUSTOM_PATTERN_PREFIX + index + ".name", null);
            String regex = AiConfig.getProperty(PROP_CUSTOM_PATTERN_PREFIX + index + ".regex", null);
            if (name == null || name.trim().isEmpty()) {
                // No more consecutive custom patterns
                break;
            }
            String tokenName = name.trim();
            if (regex == null || regex.trim().isEmpty()) {
                log.warn("Custom pattern '{}' (index {}) has no regex defined. Skipping.", tokenName, index);
                continue;
            }
            try {
                Pattern compiled = Pattern.compile(regex.trim());
                result.put(tokenName, compiled);
                log.debug("Loaded custom pattern '{}' (index {}): {}", tokenName, index, regex.trim());
            } catch (PatternSyntaxException e) {
                log.warn("Invalid regex for custom pattern '{}' (index {}): {}. Skipping. Error: {}",
                        tokenName, index, regex.trim(), e.getDescription());
            }
        }
        if (!result.isEmpty()) {
            log.info("Loaded {} custom detection pattern(s) from properties", result.size());
        }
        return Collections.unmodifiableMap(result);
    }

    private static Set<String> loadStringSet(String propertyKey, String defaultValue) {
        String raw = AiConfig.getProperty(propertyKey, null);
        if (raw == null || raw.trim().isEmpty()) {
            raw = defaultValue;
        }
        Set<String> result = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (result.isEmpty()) {
            // If user provided an empty/invalid list, fall back to defaults
            log.warn("Empty or invalid list configured for property '{}'. Falling back to defaults.", propertyKey);
            result = Arrays.stream(defaultValue.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(String::toLowerCase)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        return Collections.unmodifiableSet(result);
    }
}
