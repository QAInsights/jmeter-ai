package org.qainsights.jmeter.ai.correlation;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public class CrossRequestMatcher {
    public List<CorrelationCandidate> findMatches(List<SampleRecord> samples, DynamicValueDetector detector) {
        Objects.requireNonNull(samples, "samples");
        Objects.requireNonNull(detector, "detector");
        List<CorrelationCandidate> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (int sourceIndex = 0; sourceIndex < samples.size(); sourceIndex++) {
            SampleRecord source = samples.get(sourceIndex);
            List<DetectedValue> detectedValues = detector.detect(source);
            for (DetectedValue detectedValue : detectedValues) {
                String normalizedValue = normalize(detectedValue.getValue());
                if (normalizedValue.isEmpty()) {
                    continue;
                }
                for (int targetIndex = sourceIndex + 1; targetIndex < samples.size(); targetIndex++) {
                    SampleRecord target = samples.get(targetIndex);
                    String requestLocation = findRequestLocation(target, normalizedValue);
                    if (!requestLocation.isEmpty()) {
                        String key = source.getLabel() + "|" + target.getLabel() + "|" + normalizedValue + "|" + requestLocation;
                        if (seen.add(key)) {
                            ExtractorSuggestion suggestion = createSuggestion(source, detectedValue);
                            candidates.add(new CorrelationCandidate(normalizedValue, source, target,
                                    detectedValue.getLocation(), requestLocation, suggestion, CandidateStatus.PENDING));
                        }
                    }
                }
            }
        }
        return candidates;
    }

    private static String findRequestLocation(SampleRecord target, String value) {
        Set<String> encodedValues = encodedVariants(value);
        String pathParam = findUrlPathParameter(target.getRequestUrl(), value, encodedValues);
        if (!pathParam.isEmpty()) {
            return "URL Path: " + pathParam;
        }
        String queryParam = findQueryParameter(target.getRequestUrl(), value, encodedValues);
        if (!queryParam.isEmpty()) {
            return "URL Query: " + queryParam;
        }
        String header = findHeader(target.getRequestHeaders(), value, encodedValues);
        if (!header.isEmpty()) {
            return "Request Header: " + header;
        }
        String bodyField = findBodyField(target.getRequestBody(), value, encodedValues);
        if (!bodyField.isEmpty()) {
            return "Request Body: " + bodyField;
        }
        return "";
    }

    private static String findUrlPathParameter(String requestUrl, String value, Set<String> encodedValues) {
        String url = safe(requestUrl);
        int queryIndex = url.indexOf('?');
        String path = queryIndex < 0 ? url : url.substring(0, queryIndex);
        if (containsValue(path, value, encodedValues)) {
            java.util.regex.Matcher matcher = Pattern.compile("(?i);([^=;/]+)=([^;/?#]+)").matcher(path);
            while (matcher.find()) {
                if (containsValue(decode(matcher.group(2)), value, encodedValues)) {
                    return matcher.group(1);
                }
            }
            return "path";
        }
        return "";
    }

    private static String findQueryParameter(String requestUrl, String value, Set<String> encodedValues) {
        String url = safe(requestUrl);
        int queryIndex = url.indexOf('?');
        if (queryIndex < 0 || queryIndex + 1 >= url.length()) {
            return "";
        }
        String query = url.substring(queryIndex + 1);
        int fragmentIndex = query.indexOf('#');
        if (fragmentIndex >= 0) {
            query = query.substring(0, fragmentIndex);
        }
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            String name = decode(pair[0]);
            String candidate = pair.length > 1 ? decode(pair[1]) : "";
            if (containsValue(candidate, value, encodedValues) || containsValue(part, value, encodedValues)) {
                return name.isEmpty() ? "query" : name;
            }
        }
        return "";
    }

    private static String findHeader(String requestHeaders, String value, Set<String> encodedValues) {
        String[] lines = safe(requestHeaders).split("\\R");
        for (String line : lines) {
            int colonIndex = line.indexOf(':');
            if (colonIndex <= 0) {
                continue;
            }
            String headerName = line.substring(0, colonIndex).trim();
            String headerValue = decode(line.substring(colonIndex + 1).trim());
            if (containsValue(headerValue, value, encodedValues) || containsValue(line, value, encodedValues)) {
                return headerName;
            }
        }
        return "";
    }

    private static String findBodyField(String requestBody, String value, Set<String> encodedValues) {
        String body = safe(requestBody);
        if (body.isEmpty()) {
            return "";
        }
        String formField = findFormField(body, value, encodedValues);
        if (!formField.isEmpty()) {
            return formField;
        }
        String jsonField = findJsonField(body, value);
        if (!jsonField.isEmpty()) {
            return jsonField;
        }
        String xmlField = findXmlField(body, value);
        if (!xmlField.isEmpty()) {
            return xmlField;
        }
        return containsValue(decode(body), value, encodedValues) || containsValue(body, value, encodedValues) ? "body" : "";
    }

    private static String findFormField(String body, String value, Set<String> encodedValues) {
        for (String part : body.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length < 2) {
                continue;
            }
            String fieldValue = decode(pair[1]);
            if (containsValue(fieldValue, value, encodedValues) || containsValue(part, value, encodedValues)) {
                return decode(pair[0]);
            }
        }
        return "";
    }

    private static String findJsonField(String body, String value) {
        java.util.regex.Matcher matcher = Pattern.compile("[\\\"']([^\\\"']+)[\\\"']\\s*:\\s*[\\\"']([^\\\"']*)[\\\"']").matcher(body);
        while (matcher.find()) {
            if (decode(matcher.group(2)).contains(value)) {
                return matcher.group(1);
            }
        }
        return "";
    }

    private static String findXmlField(String body, String value) {
        java.util.regex.Matcher matcher = Pattern.compile("<([A-Za-z0-9_.:-]+)[^>]*>([^<]*)</\\1>").matcher(body);
        while (matcher.find()) {
            if (decode(matcher.group(2)).contains(value)) {
                return matcher.group(1);
            }
        }
        return "";
    }

    private static ExtractorSuggestion createSuggestion(SampleRecord source, DetectedValue detectedValue) {
        String variableName = toVariableName(detectedValue.getTokenName(), detectedValue.getLocation());
        if (isJson(source.getResponseBody()) && detectedValue.getLocation().toLowerCase(Locale.ROOT).contains("response body")
                && !detectedValue.getTokenName().isBlank()) {
            return new ExtractorSuggestion(ExtractorType.JSON_PATH, "$.." + detectedValue.getTokenName(), variableName, "1");
        }
        if (detectedValue.getLocation().toLowerCase(Locale.ROOT).contains("set-cookie") && !detectedValue.getTokenName().isBlank()) {
            String cookieName = Pattern.quote(detectedValue.getTokenName());
            return new ExtractorSuggestion(ExtractorType.REGEX, "(?i)Set-Cookie:\\s*" + cookieName + "=([^;\\r\\n]+)", variableName, "1");
        }
        if (detectedValue.getLocation().toLowerCase(Locale.ROOT).contains("hidden input") && !detectedValue.getTokenName().isBlank()) {
            String key = Pattern.quote(detectedValue.getTokenName());
            String expression = "name=[\\\"']" + key + "[\\\"'][^>]*value=[\\\"']([^\\\"']+)";
            return new ExtractorSuggestion(ExtractorType.REGEX, expression, variableName, "1");
        }
        if (!detectedValue.getTokenName().isBlank() && !"dynamic_value".equals(detectedValue.getTokenName())) {
            String key = Pattern.quote(detectedValue.getTokenName());
            String expression = "[\\\"']?" + key + "[\\\"']?\\s*[:=]\\s*[\\\"']?([^\\\"'&,;<>\\s]+)";
            return new ExtractorSuggestion(ExtractorType.REGEX, expression, variableName, "1");
        }
        return new ExtractorSuggestion(ExtractorType.REGEX, "(.+?)", variableName, "1");
    }

    private static String toVariableName(String tokenName, String location) {
        String source = tokenName == null || tokenName.isBlank() || "dynamic_value".equals(tokenName) ? location : tokenName;
        String normalized = source.replaceAll("(?i)Response Header:|Response Body:|Set-Cookie", "")
                .replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("^_+|_+$", "")
                .toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? "correlated_value" : normalized;
    }

    private static boolean isJson(String body) {
        String trimmed = safe(body).trim();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    private static String normalize(String value) {
        return decode(safe(value)).trim();
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(safe(value), StandardCharsets.UTF_8.name());
        } catch (IllegalArgumentException | UnsupportedEncodingException e) {
            return safe(value);
        }
    }

    private static Set<String> encodedVariants(String value) {
        Set<String> variants = new HashSet<>();
        String safeValue = safe(value);
        variants.add(safeValue);
        try {
            String encoded = URLEncoder.encode(safeValue, StandardCharsets.UTF_8.name());
            variants.add(encoded);
            variants.add(encoded.replace("+", "%20"));
        } catch (UnsupportedEncodingException e) {
            variants.add(safeValue.replace(" ", "+"));
        }
        return variants;
    }

    private static boolean containsValue(String text, String value, Set<String> encodedValues) {
        String safeText = safe(text);
        if (safeText.contains(value)) {
            return true;
        }
        for (String encodedValue : encodedValues) {
            if (!encodedValue.isEmpty() && safeText.contains(encodedValue)) {
                return true;
            }
        }
        return false;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
