package org.qainsights.jmeter.ai.telemetry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Posts the ping with the JDK HttpClient, honoring the default proxy
 * configuration. Never throws: any failure returns false so counts are retried
 * on the next tick.
 */
public class HttpTelemetrySender implements TelemetrySender {
    private static final Logger log = LoggerFactory.getLogger(HttpTelemetrySender.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final String endpoint;
    private final String userAgent;
    private final HttpClient client;

    public HttpTelemetrySender(String endpoint, String pluginVersion) {
        this(endpoint, pluginVersion,
                HttpClient.newBuilder()
                        .proxy(ProxySelector.getDefault())
                        .connectTimeout(TIMEOUT)
                        .build());
    }

    /** Package-private seam for tests. */
    HttpTelemetrySender(String endpoint, String pluginVersion, HttpClient client) {
        this.endpoint = endpoint;
        this.userAgent = "FeatherWand/" + pluginVersion;
        this.client = client;
    }

    @Override
    public boolean send(String json) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", userAgent)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Throwable t) {
            log.debug("Telemetry ping failed", t);
            return false;
        }
    }
}
