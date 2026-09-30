package org.qainsights.jmeter.ai.telemetry;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HttpTelemetrySenderTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startServer(int status) throws IOException {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> userAgent = new AtomicReference<>();
        this.body = body;
        this.contentType = contentType;
        this.userAgent = userAgent;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/ping", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/ping";
    }

    private AtomicReference<String> body;
    private AtomicReference<String> contentType;
    private AtomicReference<String> userAgent;

    @Test
    void postsBodyAndHeaders() throws Exception {
        String url = startServer(204);
        HttpTelemetrySender sender = new HttpTelemetrySender(url, "3.8.5");
        assertTrue(sender.send("{\"event\":\"daily\"}"));
        assertEquals("{\"event\":\"daily\"}", body.get());
        assertEquals("application/json", contentType.get());
        assertEquals("FeatherWand/3.8.5", userAgent.get());
    }

    @Test
    void returnsFalseOnServerError() throws Exception {
        String url = startServer(500);
        assertFalse(new HttpTelemetrySender(url, "3.8.5").send("{}"));
    }

    @Test
    void returnsFalseOnConnectionRefused() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        HttpTelemetrySender sender = new HttpTelemetrySender(
                "http://127.0.0.1:" + port + "/v1/ping", "3.8.5");
        assertFalse(sender.send("{}"));
    }
}
