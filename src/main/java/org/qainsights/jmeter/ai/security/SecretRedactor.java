package org.qainsights.jmeter.ai.security;

import org.qainsights.jmeter.ai.utils.AiConfig;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks secrets and credentials before any test-plan context is handed to an AI
 * CLI (written to {@code CLAUDE.md} / {@code AGENTS.md} / {@code KIRO.md}).
 *
 * <p>JMeter test plans routinely embed passwords, bearer tokens, API keys, and
 * other secrets in sampler fields, HTTP headers, and CSV data. Sending those to
 * an external agent is the single biggest blocker to enterprise adoption, so
 * redaction is <strong>enabled by default</strong> and can only be turned off
 * explicitly via {@code jmeter.ai.security.redaction.enabled=false}.
 *
 * <p>Redaction is intentionally conservative-to-aggressive: over-masking is safe,
 * under-masking leaks. Three layers are applied:
 * <ol>
 *   <li><b>Key-based</b> — any {@code name = value} / {@code "name": "value"} /
 *       {@code name=value} whose key looks like a credential.</li>
 *   <li><b>Bearer tokens</b> — {@code Authorization: Bearer &lt;token&gt;}.</li>
 *   <li><b>JWTs</b> — {@code eyJ...}.{...}.{...} anywhere in the text.</li>
 * </ol>
 */
public final class SecretRedactor {

    public static final String MASK = "***REDACTED***";

    private static final String ENABLED_PROP = "jmeter.ai.security.redaction.enabled";
    /** Comma-separated extra key fragments to treat as secret, e.g. {@code pin,otp}. */
    private static final String EXTRA_KEYS_PROP = "jmeter.ai.security.redaction.extra_keys";

    private static final String BASE_KEYS =
            "password|passwd|pwd|secret|secret[_-]?key|token|auth[_-]?token|api[_-]?key|apikey|"
                    + "access[_-]?key|client[_-]?secret|authorization|bearer|credential|private[_-]?key|"
                    + "connection[_-]?string|session[_-]?id|cookie";

    private static final Pattern BEARER =
            Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=\\-]{6,}");
    private static final Pattern JWT =
            Pattern.compile("eyJ[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,}");

    private SecretRedactor() {
    }

    /** @return {@code true} unless an admin has explicitly disabled redaction. */
    public static boolean isEnabled() {
        return AiConfig.getProperty(ENABLED_PROP, "true").equalsIgnoreCase("true");
    }

    /**
     * Redact secrets from {@code text}. Returns the input unchanged if redaction
     * is disabled or the input is null/empty.
     */
    public static String redact(String text) {
        if (text == null || text.isEmpty() || !isEnabled()) {
            return text;
        }
        String result = text;

        // 1) Key-based: <key><sep><value>. Keys may be dotted (e.g. HTTPSampler.password).
        Pattern keyValue = Pattern.compile(
                "(?i)([\\w.\\-]*(?:" + keywordAlternation() + ")[\\w.\\-]*[\"']?\\s*[:=]\\s*[\"']?)"
                        + "([^\"'\\r\\n,;}<>&]+)");
        result = replaceGroup2(result, keyValue);

        // 2) Authorization: Bearer <token>
        result = BEARER.matcher(result).replaceAll("$1" + Matcher.quoteReplacement(MASK));

        // 3) Standalone JWTs
        result = JWT.matcher(result).replaceAll(Matcher.quoteReplacement(MASK));

        return result;
    }

    private static String keywordAlternation() {
        String extra = AiConfig.getProperty(EXTRA_KEYS_PROP, "").trim();
        if (extra.isEmpty()) {
            return BASE_KEYS;
        }
        // Escape and OR-in the operator-supplied fragments.
        StringBuilder sb = new StringBuilder(BASE_KEYS);
        for (String frag : extra.split(",")) {
            String f = frag.trim();
            if (!f.isEmpty()) {
                sb.append('|').append(Pattern.quote(f));
            }
        }
        return sb.toString();
    }

    /** Replace capture group 2 (the value) with the mask, keeping the key prefix. */
    private static String replaceGroup2(String input, Pattern pattern) {
        Matcher m = pattern.matcher(input);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + MASK));
        }
        m.appendTail(out);
        return out.toString();
    }
}
