package org.qainsights.jmeter.ai.generate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link TestPlanModel} from an OpenAPI / Swagger document (JSON). One
 * HTTP sampler is produced per path + operation. Supports OpenAPI 3 ({@code servers})
 * and Swagger 2 ({@code host} / {@code basePath} / {@code schemes}).
 */
public final class OpenApiParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> METHODS =
            Arrays.asList("get", "post", "put", "delete", "patch", "head", "options");

    private OpenApiParser() {
    }

    public static TestPlanModel parse(String json, String testName) throws Exception {
        JsonNode root = MAPPER.readTree(json);
        TestPlanModel model = new TestPlanModel(resolveName(testName, root));

        Base base = resolveBase(root);
        JsonNode paths = root.path("paths");
        Iterator<Map.Entry<String, JsonNode>> it = paths.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> pathEntry = it.next();
            String path = pathEntry.getKey();
            JsonNode operations = pathEntry.getValue();
            for (String method : METHODS) {
                JsonNode op = operations.path(method);
                if (op.isMissingNode()) {
                    continue;
                }
                HttpRequestSpec spec = new HttpRequestSpec()
                        .setMethod(method)
                        .setProtocol(base.protocol)
                        .setDomain(base.domain)
                        .setPort(base.port)
                        .setPath(join(base.basePath, path));
                String summary = op.path("summary").asText(op.path("operationId").asText(""));
                if (!summary.isEmpty()) {
                    spec.setName(method.toUpperCase() + " " + path + " - " + summary);
                } else {
                    spec.setName(method.toUpperCase() + " " + path);
                }
                model.add(spec);
            }
        }
        return model;
    }

    private static String resolveName(String testName, JsonNode root) {
        if (testName != null && !testName.isEmpty()) {
            return testName;
        }
        String title = root.path("info").path("title").asText("");
        return title.isEmpty() ? "Generated Test Plan" : title;
    }

    private static Base resolveBase(JsonNode root) {
        Base base = new Base();
        // OpenAPI 3: servers[0].url
        JsonNode servers = root.path("servers");
        if (servers.isArray() && servers.size() > 0) {
            String url = servers.get(0).path("url").asText("");
            if (!url.isEmpty()) {
                applyUrl(base, url);
                return base;
            }
        }
        // Swagger 2: host + basePath + schemes
        String host = root.path("host").asText("");
        if (!host.isEmpty()) {
            JsonNode schemes = root.path("schemes");
            if (schemes.isArray() && schemes.size() > 0) {
                base.protocol = schemes.get(0).asText("https");
            }
            int colon = host.indexOf(':');
            if (colon >= 0) {
                base.domain = host.substring(0, colon);
                try {
                    base.port = Integer.parseInt(host.substring(colon + 1));
                } catch (NumberFormatException ignored) {
                    // leave port unset
                }
            } else {
                base.domain = host;
            }
            base.basePath = root.path("basePath").asText("");
        }
        return base;
    }

    private static void applyUrl(Base base, String url) {
        // Relative server URL (e.g. "/v1") only contributes a base path.
        if (url.startsWith("/")) {
            base.basePath = url;
            return;
        }
        try {
            URI uri = URI.create(url);
            if (uri.getScheme() != null) {
                base.protocol = uri.getScheme();
            }
            if (uri.getHost() != null) {
                base.domain = uri.getHost();
            }
            base.port = uri.getPort();
            if (uri.getRawPath() != null && !uri.getRawPath().equals("/")) {
                base.basePath = uri.getRawPath();
            }
        } catch (IllegalArgumentException ignored) {
            // leave defaults
        }
    }

    static String join(String basePath, String path) {
        String b = basePath == null ? "" : basePath;
        if (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        String p = path == null || path.isEmpty() ? "/" : path;
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        String joined = b + p;
        return joined.isEmpty() ? "/" : joined;
    }

    private static final class Base {
        String protocol = "https";
        String domain = "";
        int port = -1;
        String basePath = "";
    }
}
