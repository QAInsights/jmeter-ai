package org.qainsights.jmeter.ai.fix;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.jmeter.samplers.SampleResult;

/** Deterministic heuristics for successful HTTP responses that contain an application failure. */
public final class SoftFailureDetector {
    private static final Pattern TITLE = Pattern.compile("(?is)<title\\b[^>]*>(.*?)</title>");
    private static final Pattern TITLE_FAILURE = Pattern.compile(
            "(?i)\\b(?:not found|error|exception|forbidden|unauthorized|access denied|invalid)\\b");
    private static final Pattern HTML_DOCUMENT = Pattern.compile(
            "(?is)^\\s*(?:<!doctype\\s+html\\b|<html\\b)");
    private static final Pattern BODY_FAILURE = Pattern.compile(
            "HTTP Status [45]|Stack trace|Exception:");
    private static final Pattern AUTH_PATH = Pattern.compile("(?i)(?:login|signin|signon|logon)");
    private static final Pattern REQUEST_PATH = Pattern.compile("(?m)^(?:GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\\s+(\\S+)");

    private SoftFailureDetector() {
    }

    public static String reason(SampleResult result) {
        if (result == null || !result.isSuccessful()) {
            return null;
        }
        int code;
        try {
            code = Integer.parseInt(result.getResponseCode());
        } catch (NumberFormatException e) {
            return null;
        }
        if (code < 200 || code >= 400) {
            return null;
        }
        String body = result.getResponseDataAsString();
        String contentType = result.getContentType() == null ? "" : result.getContentType().toLowerCase(Locale.ROOT);
        if (contentType.contains("html") || contentType.isBlank() && isHtmlDocument(body)) {
            String title = title(body);
            if (TITLE_FAILURE.matcher(title).find()) {
                return "page title indicates an error: " + title;
            }
        }
        if ((contentType.contains("html") || contentType.contains("text/plain"))
                && body != null && BODY_FAILURE.matcher(body).find()) {
            return "response body contains a server error";
        }
        String finalPath = path(result.getURL() == null ? null : result.getURL().toExternalForm());
        String requestedPath = requestedPath(result.getSamplerData());
        if (finalPath != null && AUTH_PATH.matcher(finalPath).find()
                && (requestedPath == null || !AUTH_PATH.matcher(requestedPath).find())) {
            return "request was redirected to an authentication page";
        }
        return null;
    }

    private static boolean isHtmlDocument(String body) {
        return body != null && HTML_DOCUMENT.matcher(body).find();
    }

    public static String titleText(SampleResult result) {
        return result == null ? "" : title(result.getResponseDataAsString());
    }

    private static String title(String body) {
        if (body == null) {
            return "";
        }
        Matcher matcher = TITLE.matcher(body);
        return matcher.find() ? matcher.group(1).replaceAll("(?s)<[^>]+>", " ").trim() : "";
    }

    private static String requestedPath(String samplerData) {
        if (samplerData == null) {
            return null;
        }
        Matcher matcher = REQUEST_PATH.matcher(samplerData);
        return matcher.find() ? path(matcher.group(1)) : null;
    }

    private static String path(String value) {
        if (value == null) {
            return null;
        }
        try {
            String path = URI.create(value).getPath();
            return path == null ? "" : path.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
