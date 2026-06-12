package org.qainsights.jmeter.ai.correlation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qainsights.jmeter.ai.generate.HttpRequestSpec;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Correlation autopilot for recorded traffic. Analyses a HAR offline and finds
 * <em>dynamic</em> values — ones that appear in an earlier JSON <b>response</b>
 * and are then sent in a later <b>request</b> (URL, body, or header) — the
 * tokens (CSRF, session ids, auth tokens) that must be extracted and replayed
 * for a load test to work. Each find becomes a {@link CorrelationCandidate} with
 * a suggested extractor, complementing the live-run {@link CorrelationEngine}.
 */
public final class HarCorrelationAnalyzer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Keys whose values are likely dynamic regardless of format. */
    private static final Pattern DYNAMIC_KEY = Pattern.compile(
            "(?i).*(token|csrf|session|sid|nonce|auth|secret|guid|uuid|state|verifier|hash|viewstate|nonce).*");
    private static final Pattern UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern LONG_TOKEN = Pattern.compile("[A-Za-z0-9._\\-]{16,}");

    private static final int MIN_VALUE_LEN = 8;

    private HarCorrelationAnalyzer() {
    }

    public static List<CorrelationCandidate> analyze(String harJson) throws Exception {
        List<Entry> entries = readEntries(harJson);
        List<CorrelationCandidate> candidates = new ArrayList<>();

        for (int i = 0; i < entries.size(); i++) {
            Entry source = entries.get(i);
            Map<String, String> dynamicValues = extractDynamicValues(source.responseBody); // value -> key
            for (Map.Entry<String, String> dv : dynamicValues.entrySet()) {
                String value = dv.getKey();
                String key = dv.getValue();

                CorrelationCandidate candidate = null;
                for (int j = i + 1; j < entries.size(); j++) {
                    if (entries.get(j).requestContains(value)) {
                        if (candidate == null) {
                            candidate = newCandidate(source, i, key, value);
                        }
                        candidate.addTargetSamplerIndex(j);
                        candidate.addTargetSamplerName(entries.get(j).name);
                    }
                }
                if (candidate != null) {
                    candidates.add(candidate);
                }
            }
        }
        return candidates;
    }

    private static CorrelationCandidate newCandidate(Entry source, int index, String key, String value) {
        CorrelationCandidate c = new CorrelationCandidate();
        c.setParameterName(key);
        c.setSampleValue(value);
        c.setSourceSamplerIndex(index);
        c.setSourceSamplerName(source.name);
        c.setSourceLocation("response body (JSON)");
        c.setExtractorType("json");
        c.setExtractionPattern("$.." + key);
        c.setVariableName(sanitizeVar(key));
        return c;
    }

    /** Recursively collect candidate (value -> key) pairs from a JSON response. */
    static Map<String, String> extractDynamicValues(String responseBody) {
        Map<String, String> found = new LinkedHashMap<>();
        if (responseBody == null || responseBody.isEmpty()) {
            return found;
        }
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            walk(root, found);
        } catch (Exception ignored) {
            // non-JSON response: out of scope for this analyzer
        }
        return found;
    }

    private static void walk(JsonNode node, Map<String, String> found) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                JsonNode child = e.getValue();
                if (child.isValueNode()) {
                    String value = child.asText("");
                    if (isDynamic(e.getKey(), value)) {
                        found.putIfAbsent(value, e.getKey());
                    }
                } else {
                    walk(child, found);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                walk(child, found);
            }
        }
    }

    static boolean isDynamic(String key, String value) {
        if (value == null || value.length() < MIN_VALUE_LEN) {
            return false;
        }
        boolean keyLooksDynamic = key != null && DYNAMIC_KEY.matcher(key).matches();
        boolean valueLooksDynamic = UUID.matcher(value).matches()
                || (LONG_TOKEN.matcher(value).matches() && hasMixedComplexity(value));
        return keyLooksDynamic || valueLooksDynamic;
    }

    /** Avoid flagging long-but-plain values (e.g. a sentence or a plain word). */
    private static boolean hasMixedComplexity(String v) {
        boolean digit = false, letter = false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (Character.isDigit(c)) digit = true;
            else if (Character.isLetter(c)) letter = true;
        }
        return digit && letter;
    }

    static String sanitizeVar(String key) {
        if (key == null || key.isEmpty()) {
            return "corr_var";
        }
        String v = key.replaceAll("[^A-Za-z0-9_]", "_");
        return Character.isDigit(v.charAt(0)) ? "_" + v : v;
    }

    private static List<Entry> readEntries(String harJson) throws Exception {
        List<Entry> out = new ArrayList<>();
        JsonNode entries = MAPPER.readTree(harJson).path("log").path("entries");
        if (!entries.isArray()) {
            return out;
        }
        for (JsonNode e : entries) {
            JsonNode request = e.path("request");
            String url = request.path("url").asText("");
            if (url.isEmpty()) {
                continue;
            }
            Entry entry = new Entry();
            HttpRequestSpec spec = org.qainsights.jmeter.ai.generate.HarParser.fromUrl(url);
            entry.name = (spec == null ? "request" : request.path("method").asText("GET") + " " + spec.getPath());
            entry.url = url;
            entry.requestBody = request.path("postData").path("text").asText("");
            StringBuilder headerBlob = new StringBuilder();
            for (JsonNode h : request.path("headers")) {
                headerBlob.append(h.path("value").asText("")).append('\n');
            }
            entry.headerBlob = headerBlob.toString();
            entry.responseBody = e.path("response").path("content").path("text").asText("");
            out.add(entry);
        }
        return out;
    }

    private static final class Entry {
        String name;
        String url;
        String requestBody;
        String headerBlob;
        String responseBody;

        boolean requestContains(String value) {
            return (url != null && url.contains(value))
                    || (requestBody != null && requestBody.contains(value))
                    || (headerBlob != null && headerBlob.contains(value));
        }
    }
}
