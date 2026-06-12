package org.qainsights.jmeter.ai.generate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Protocol-neutral description of one HTTP request, the unit a {@link TestPlanModel}
 * is built from. Parsers (HAR, OpenAPI) produce these; {@link JmxTestPlanWriter}
 * turns each into an {@code HTTPSamplerProxy}.
 */
public final class HttpRequestSpec {

    private String name;
    private String method = "GET";
    private String protocol = "https";
    private String domain = "";
    private int port = -1; // -1 = unset (JMeter infers from protocol)
    private String path = "/";
    private final Map<String, String> headers = new LinkedHashMap<>();
    private String body = "";

    public String getName() {
        return name != null && !name.isEmpty() ? name : (method + " " + path);
    }

    public HttpRequestSpec setName(String name) {
        this.name = name;
        return this;
    }

    public String getMethod() {
        return method;
    }

    public HttpRequestSpec setMethod(String method) {
        if (method != null && !method.isEmpty()) {
            this.method = method.toUpperCase();
        }
        return this;
    }

    public String getProtocol() {
        return protocol;
    }

    public HttpRequestSpec setProtocol(String protocol) {
        if (protocol != null && !protocol.isEmpty()) {
            this.protocol = protocol;
        }
        return this;
    }

    public String getDomain() {
        return domain;
    }

    public HttpRequestSpec setDomain(String domain) {
        this.domain = domain == null ? "" : domain;
        return this;
    }

    public int getPort() {
        return port;
    }

    public HttpRequestSpec setPort(int port) {
        this.port = port;
        return this;
    }

    public String getPath() {
        return path;
    }

    public HttpRequestSpec setPath(String path) {
        this.path = (path == null || path.isEmpty()) ? "/" : path;
        return this;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public HttpRequestSpec addHeader(String name, String value) {
        if (name != null && !name.isEmpty()) {
            headers.put(name, value == null ? "" : value);
        }
        return this;
    }

    public String getBody() {
        return body;
    }

    public HttpRequestSpec setBody(String body) {
        this.body = body == null ? "" : body;
        return this;
    }

    public boolean hasBody() {
        return body != null && !body.isEmpty();
    }
}
