package org.qainsights.jmeter.ai.correlation;

import java.util.Objects;

public final class SampleRecord {
    private final String label;
    private final String requestUrl;
    private final String requestHeaders;
    private final String requestBody;
    private final String responseHeaders;
    private final String responseBody;
    private final long timestamp;

    public SampleRecord(String label, String requestUrl, String requestHeaders, String requestBody,
                        String responseHeaders, String responseBody, long timestamp) {
        this.label = Objects.requireNonNullElse(label, "");
        this.requestUrl = Objects.requireNonNullElse(requestUrl, "");
        this.requestHeaders = Objects.requireNonNullElse(requestHeaders, "");
        this.requestBody = Objects.requireNonNullElse(requestBody, "");
        this.responseHeaders = Objects.requireNonNullElse(responseHeaders, "");
        this.responseBody = Objects.requireNonNullElse(responseBody, "");
        this.timestamp = timestamp;
    }

    public String getLabel() {
        return label;
    }

    public String getRequestUrl() {
        return requestUrl;
    }

    public String getRequestHeaders() {
        return requestHeaders;
    }

    public String getRequestBody() {
        return requestBody;
    }

    public String getResponseHeaders() {
        return responseHeaders;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
