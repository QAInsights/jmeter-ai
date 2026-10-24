package org.qainsights.jmeter.ai.utils;

import java.util.regex.Pattern;

public final class SafeText {
    private static final Pattern URL_USERINFO =
            Pattern.compile("(?i)\\b([a-z][a-z0-9+.-]*://)[^/\\s@]+@");
    private static final Pattern URL_QUERY =
            Pattern.compile("(?i)\\b([a-z][a-z0-9+.-]*://[^\\s?#]+)\\?[^\\s]*");

    private SafeText() {
    }

    public static String redactUrls(String text) {
        if (text == null) {
            return null;
        }
        String withoutUserInfo = URL_USERINFO.matcher(text).replaceAll("$1***@");
        return URL_QUERY.matcher(withoutUserInfo).replaceAll("$1?***");
    }
}
