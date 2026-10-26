package org.qainsights.jmeter.ai.utils;

import java.util.regex.Pattern;

public final class SafeText {
    private static final Pattern URL_USERINFO =
            Pattern.compile("(?i)\\b([a-z][a-z0-9+.-]*://)[^/\\s]+@");
    private static final Pattern URL_QUERY =
            Pattern.compile("(?i)\\b([a-z][a-z0-9+.-]*://[^\\s?#]+)\\?[^\\s]*");
    private static final String SENSITIVE_NAME =
            "(?:token|session|sid|csrf|xsrf|cookie|auth|password|passwd|secret|api[_-]?key|apikey)";
    private static final Pattern SENSITIVE_FIELD_VALUE = Pattern.compile(
            "(?i)([\"']?[^\"'\\s:=&,{}\\[\\]]{0,64}" + SENSITIVE_NAME
                    + "[^\"'\\s:=&,{}\\[\\]]{0,64}[\"']?\\s*[:=]\\s*)"
                    + "(?:\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|[^&,\\s}\\]]+)");
    private static final Pattern HIDDEN_INPUT = Pattern.compile(
            "(?is)<input\\b[^>]*>");
    private static final Pattern HIDDEN_TYPE = Pattern.compile(
            "(?i)\\btype\\s*=\\s*(?:\"hidden\"|'hidden'|hidden(?:\\s|/|>))");
    private static final Pattern HTML_NAME = Pattern.compile(
            "(?i)\\bname\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
    private static final Pattern HTML_VALUE = Pattern.compile(
            "(?is)(\\bvalue\\s*=\\s*)([\"'])(.*?)\\2");
    private static final Pattern BEARER_VALUE = Pattern.compile(
            "(?i)(\\bBearer\\s+)[^\\s,;\"']+");
    private static final Pattern JWT = Pattern.compile("eyJ[\\w-]+\\.[\\w-]+\\.[\\w-]*");

    private SafeText() {
    }

    public static String redactUrls(String text) {
        if (text == null) {
            return null;
        }
        String withoutUserInfo = URL_USERINFO.matcher(text).replaceAll("$1***@");
        return URL_QUERY.matcher(withoutUserInfo).replaceAll("$1?***");
    }

    /** Masks common sensitive JSON and form field values without retaining their contents. */
    public static String redactSensitiveValues(String text) {
        if (text == null) {
            return null;
        }
        String redacted = redactHiddenInputs(text);
        redacted = BEARER_VALUE.matcher(redacted).replaceAll("$1***");
        if (redacted.indexOf(':') >= 0 || redacted.indexOf('=') >= 0) {
            redacted = SENSITIVE_FIELD_VALUE.matcher(redacted).replaceAll("$1***");
        }
        return JWT.matcher(redacted).replaceAll("***");
    }

    private static String redactHiddenInputs(String text) {
        java.util.regex.Matcher inputs = HIDDEN_INPUT.matcher(text);
        StringBuilder redacted = new StringBuilder(text.length());
        int lastEnd = 0;
        while (inputs.find()) {
            String input = inputs.group();
            if (HIDDEN_TYPE.matcher(input).find() && hasSensitiveName(input)) {
                input = HTML_VALUE.matcher(input).replaceFirst("$1$2***$2");
            }
            redacted.append(text, lastEnd, inputs.start()).append(input);
            lastEnd = inputs.end();
        }
        return redacted.append(text, lastEnd, text.length()).toString();
    }

    private static boolean hasSensitiveName(String input) {
        java.util.regex.Matcher name = HTML_NAME.matcher(input);
        if (!name.find()) {
            return false;
        }
        String value = name.group(1) != null ? name.group(1)
                : name.group(2) != null ? name.group(2) : name.group(3);
        return value != null && value.toLowerCase(java.util.Locale.ROOT)
                .matches(".*" + SENSITIVE_NAME + ".*");
    }
}
