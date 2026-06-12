package org.qainsights.jmeter.ai.generate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Builds a {@link TestPlanModel} from a HAR (HTTP Archive) capture — the JSON a
 * browser or proxy exports. Requests are de-duplicated by {@code METHOD path} so
 * a noisy recording becomes a tidy test plan.
 */
public final class HarParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HarParser() {
    }

    public static TestPlanModel parse(String harJson, String testName) throws Exception {
        TestPlanModel model = new TestPlanModel(testName);
        JsonNode root = MAPPER.readTree(harJson);
        JsonNode entries = root.path("log").path("entries");
        if (!entries.isArray()) {
            return model;
        }

        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode entry : entries) {
            JsonNode request = entry.path("request");
            if (request.isMissingNode()) {
                continue;
            }
            String method = request.path("method").asText("GET");
            String url = request.path("url").asText("");
            if (url.isEmpty()) {
                continue;
            }

            HttpRequestSpec spec = fromUrl(url);
            if (spec == null) {
                continue;
            }
            spec.setMethod(method);

            String key = spec.getMethod() + " " + spec.getPath();
            if (!seen.add(key)) {
                continue; // duplicate request, keep the first
            }

            for (JsonNode h : request.path("headers")) {
                String name = h.path("name").asText("");
                if (isUsefulHeader(name)) {
                    spec.addHeader(name, h.path("value").asText(""));
                }
            }

            JsonNode postData = request.path("postData");
            if (!postData.isMissingNode()) {
                spec.setBody(postData.path("text").asText(""));
            }
            model.add(spec);
        }
        return model;
    }

    /** Parse a full URL into protocol/domain/port/path. Shared with correlation analysis. */
    public static HttpRequestSpec fromUrl(String url) {
        try {
            URI uri = URI.create(url);
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
                path = path + "?" + uri.getRawQuery();
            }
            return new HttpRequestSpec()
                    .setProtocol(uri.getScheme() == null ? "https" : uri.getScheme())
                    .setDomain(uri.getHost() == null ? "" : uri.getHost())
                    .setPort(uri.getPort())
                    .setPath(path);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Drop HTTP/2 pseudo-headers and ones JMeter sets itself. */
    static boolean isUsefulHeader(String name) {
        if (name == null || name.isEmpty() || name.startsWith(":")) {
            return false;
        }
        String n = name.toLowerCase();
        return !n.equals("host") && !n.equals("content-length") && !n.equals("connection");
    }
}
