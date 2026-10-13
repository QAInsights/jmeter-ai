package org.qainsights.jmeter.ai.mcpserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Streamable HTTP transport for {@link McpServerProtocol}, bound to the loopback interface only.
 * Every POST must carry the bearer token; requests from a non-local browser origin are refused
 * so a web page cannot reach the server through DNS rebinding. Replies are plain JSON, and the
 * server offers no SSE stream, so GET and DELETE answer 405.
 */
public final class McpHttpServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpHttpServer.class);

    public static final String PATH = "/mcp";
    static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
    private static final int WORKER_THREADS = 4;
    static final int QUEUE_CAPACITY = 32;
    /** Overflow lane that only serves ping and stop_test while the workers are saturated. */
    static final int CONTROL_QUEUE_CAPACITY = 16;
    private static final String CONTROL_THREAD = "feather-wand-mcp-control";
    private static final byte[] IPV4_LOOPBACK = {127, 0, 0, 1};
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final HttpServer server;
    private final ExecutorService workers;
    private final ExecutorService control;
    private volatile byte[] expectedToken;
    private final McpServerProtocol protocol;

    private McpHttpServer(HttpServer server, ExecutorService workers, ExecutorService control, String token,
            McpServerProtocol protocol) {
        this.server = server;
        this.workers = workers;
        this.control = control;
        this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
        this.protocol = protocol;
    }

    /** Binds 127.0.0.1 on {@code port} (0 picks a free port) and starts serving. */
    public static McpHttpServer start(int port, String token, McpServerProtocol protocol) throws IOException {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token must not be blank");
        }
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getByAddress("localhost", IPV4_LOOPBACK), port), 0);
        AtomicInteger counter = new AtomicInteger();
        ExecutorService workers = new ThreadPoolExecutor(WORKER_THREADS, WORKER_THREADS, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY), r -> {
                    Thread t = new Thread(r, "feather-wand-mcp-" + counter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
        ExecutorService control = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(CONTROL_QUEUE_CAPACITY), r -> {
                    Thread t = new Thread(r, CONTROL_THREAD);
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
        McpHttpServer mcp = new McpHttpServer(server, workers, control, token, protocol);
        server.createContext(PATH, mcp::handle);
        server.setExecutor(task -> {
            try {
                workers.execute(task);
            } catch (RejectedExecutionException e) {
                control.execute(task);
            }
        });
        server.start();
        return mcp;
    }

    String token() {
        return new String(expectedToken, StandardCharsets.UTF_8);
    }

    /** Swaps the accepted token without dropping the listener. */
    public void setToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token must not be blank");
        }
        expectedToken = token.getBytes(StandardCharsets.UTF_8);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        workers.shutdownNow();
        control.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!PATH.equals(exchange.getRequestURI().getPath())) {
                sendText(exchange, 404, "Not found");
                return;
            }
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (origin != null && !isLocalOrigin(origin)) {
                sendText(exchange, 403, "Forbidden origin");
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                sendText(exchange, 405, "Method not allowed");
                return;
            }
            if (!authorized(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"feather-wand\"");
                sendText(exchange, 401, "Missing or invalid bearer token");
                return;
            }
            String version = exchange.getRequestHeaders().getFirst("MCP-Protocol-Version");
            if (version != null && !McpServerProtocol.SUPPORTED_VERSIONS.contains(version.trim())) {
                sendText(exchange, 400, "Unsupported MCP-Protocol-Version: " + version);
                return;
            }
            byte[] body;
            try (InputStream in = exchange.getRequestBody()) {
                body = in.readNBytes(MAX_BODY_BYTES + 1);
            }
            if (body.length > MAX_BODY_BYTES) {
                sendText(exchange, 413, "Request body too large");
                return;
            }
            JsonNode message;
            try {
                message = json.readTree(body);
            } catch (IOException e) {
                message = null;
            }
            if (message == null || message.isMissingNode()) {
                sendJson(exchange, 400, protocol.parseError());
                return;
            }
            if (CONTROL_THREAD.equals(Thread.currentThread().getName()) && !McpServerProtocol.isControlRequest(message)) {
                exchange.getResponseHeaders().set("Retry-After", "5");
                sendText(exchange, 503, "Feather Wand MCP server is busy; retry shortly");
                return;
            }
            JsonNode reply = protocol.handle(message);
            if (reply == null) {
                exchange.sendResponseHeaders(202, -1);
                return;
            }
            sendJson(exchange, 200, reply);
        } catch (RuntimeException e) {
            log.warn("MCP HTTP request failed", e);
            try {
                sendText(exchange, 500, "Internal error");
            } catch (IOException | RuntimeException ignored) {
                // headers already sent
            }
        }
    }

    boolean authorized(String header) {
        if (header == null) {
            return false;
        }
        String value = header.trim();
        int space = value.indexOf(' ');
        if (space < 0 || !value.substring(0, space).equalsIgnoreCase("Bearer")) {
            return false;
        }
        return MessageDigest.isEqual(expectedToken, value.substring(space + 1).trim().getBytes(StandardCharsets.UTF_8));
    }

    static boolean isLocalOrigin(String origin) {
        try {
            URI uri = URI.create(origin.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https")) && LOCAL_HOSTS.contains(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void sendJson(HttpExchange exchange, int status, JsonNode body) throws IOException {
        send(exchange, status, "application/json", json.writeValueAsBytes(body));
    }

    private static void sendText(HttpExchange exchange, int status, String body) throws IOException {
        send(exchange, status, "text/plain; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
