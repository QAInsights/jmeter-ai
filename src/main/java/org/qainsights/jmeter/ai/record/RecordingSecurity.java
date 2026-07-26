package org.qainsights.jmeter.ai.record;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.jmeter.util.JMeterUtils;

/**
 * Security helpers shared by the recording workflow: navigation origin
 * allow-listing and {@code ${NAME}} secret placeholder resolution.
 * <p>
 * These are deliberately free of any browser-automation types so they can guard
 * tool arguments before they reach the browser, independently of how the browser
 * is driven.
 */
public final class RecordingSecurity {

    private static final Pattern SECRET_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");

    private RecordingSecurity() {
    }

    /**
     * Checks whether {@code url} may be navigated to.
     *
     * @param url               the candidate navigation target
     * @param baseUri           the session's base URI; the same host is always allowed
     * @param allowedOriginsStr comma-separated additional allowed hosts; may be null or empty
     * @return true if the target host matches the base URI host or an explicitly allowed host
     */
    public static boolean isAllowedOrigin(String url, String baseUri, String allowedOriginsStr) {
        try {
            URI targetUri = new URI(url);
            URI base = new URI(baseUri);
            if (targetUri.getHost() == null) {
                return true;
            }
            if (targetUri.getHost().equalsIgnoreCase(base.getHost())) {
                return true;
            }
            if (allowedOriginsStr != null && !allowedOriginsStr.trim().isEmpty()) {
                for (String allowed : allowedOriginsStr.split(",")) {
                    if (targetUri.getHost().equalsIgnoreCase(allowed.trim())) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            // Malformed URI is never allowed.
        }
        return false;
    }

    /**
     * Replaces every {@code ${NAME}} placeholder with its value, looking up the
     * environment first and JMeter properties second.
     *
     * @param value the raw value, possibly containing placeholders; may be null
     * @return the resolved value, or null when {@code value} is null
     * @throws RecordingException if a placeholder cannot be resolved
     */
    public static String resolveSecret(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = SECRET_PATTERN.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String envVal = System.getenv(name);
            if (envVal != null) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(envVal));
            } else {
                String propVal = JMeterUtils.getProperty(name);
                if (propVal != null) {
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(propVal));
                } else {
                    throw new RecordingException("Unresolved secret placeholder: ${" + name + "}");
                }
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
