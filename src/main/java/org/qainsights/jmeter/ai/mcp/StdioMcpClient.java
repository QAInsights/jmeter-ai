package org.qainsights.jmeter.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An {@link McpClient} speaking JSON-RPC 2.0 over the MCP stdio transport.
 * <p>
 * The stdio transport frames messages as <em>newline-delimited JSON</em> - one complete
 * JSON object per line, with no embedded newlines and no {@code Content-Length} headers.
 * Jackson's default serialisation is already single-line, which satisfies this.
 * <p>
 * Responses are read on a dedicated daemon thread and matched to callers by request id,
 * because a server may interleave notifications (progress, log messages) with responses
 * and may answer out of order. Every pending caller is failed if the stream closes, so a
 * server that dies mid-session surfaces as a prompt {@link McpException} rather than a
 * hang until timeout.
 * <p>
 * Takes streams rather than a {@code Process} so it can be tested against an in-memory
 * fake server with no Node installed.
 */
public final class StdioMcpClient implements McpClient {

    private static final Logger log = LoggerFactory.getLogger(StdioMcpClient.class);

    /** Protocol revision this client implements. Servers may negotiate a different one. */
    public static final String PROTOCOL_VERSION = "2025-06-18";

    private static final String CLIENT_NAME = "feather-wand";
    private static final String CLIENT_VERSION = "1.0.0";
    private static final long DEFAULT_TIMEOUT_MILLIS = 60_000L;

    private final ObjectMapper mapper = new ObjectMapper();
    private final BufferedReader in;
    private final BufferedWriter out;
    private final long timeoutMillis;
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Thread readerThread;

    private volatile boolean closed;

    /**
     * @param serverOutput the server's stdout (messages coming to us)
     * @param serverInput  the server's stdin (messages going to it)
     */
    public StdioMcpClient(InputStream serverOutput, OutputStream serverInput) {
        this(serverOutput, serverInput, DEFAULT_TIMEOUT_MILLIS);
    }

    public StdioMcpClient(InputStream serverOutput, OutputStream serverInput, long timeoutMillis) {
        if (serverOutput == null || serverInput == null) {
            throw new IllegalArgumentException("MCP streams must not be null");
        }
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
        this.in = new BufferedReader(new InputStreamReader(serverOutput, StandardCharsets.UTF_8));
        this.out = new BufferedWriter(new OutputStreamWriter(serverInput, StandardCharsets.UTF_8));
        this.timeoutMillis = timeoutMillis;
        this.readerThread = new Thread(this::readLoop, "mcp-stdio-reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();
    }

    @Override
    public String initialize() {
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.set("capabilities", mapper.createObjectNode());
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", CLIENT_NAME);
        clientInfo.put("version", CLIENT_VERSION);

        JsonNode result = request("initialize", params);

        // Per spec the server is not ready for requests until it receives this notification.
        notifyServer("notifications/initialized", mapper.createObjectNode());

        String negotiated = result.path("protocolVersion").asText(PROTOCOL_VERSION);
        log.info("MCP session initialised with {} (protocol {})",
                result.path("serverInfo").path("name").asText("unknown server"), negotiated);
        return negotiated;
    }

    @Override
    public List<McpTool> listTools() {
        List<McpTool> tools = new ArrayList<>();
        String cursor = null;
        do {
            ObjectNode params = mapper.createObjectNode();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            JsonNode result = request("tools/list", params);
            for (JsonNode tool : result.path("tools")) {
                tools.add(new McpTool(
                        tool.path("name").asText(),
                        tool.path("description").asText(""),
                        tool.get("inputSchema")));
            }
            JsonNode next = result.get("nextCursor");
            cursor = next == null || next.isNull() ? null : next.asText();
        } while (cursor != null && !cursor.isEmpty());
        return Collections.unmodifiableList(tools);
    }

    @Override
    public McpToolResult callTool(String name, Map<String, Object> arguments) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Tool name must not be blank");
        }
        ObjectNode params = mapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments == null
                ? mapper.createObjectNode()
                : mapper.valueToTree(arguments));

        JsonNode result = request("tools/call", params);
        return new McpToolResult(result.path("isError").asBoolean(false), flattenContent(result));
    }

    /**
     * Concatenates the text parts of an MCP content array. Non-text parts (images, audio,
     * embedded resources) are summarised by type rather than dropped silently, so a model
     * seeing an empty result knows something was returned that we cannot render.
     */
    private static String flattenContent(JsonNode result) {
        JsonNode content = result.path("content");
        if (!content.isArray()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode part : content) {
            String type = part.path("type").asText("");
            if ("text".equals(type)) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(part.path("text").asText(""));
            } else if (!type.isEmpty()) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append('[').append(type).append(" content omitted]");
            }
        }
        return text.toString();
    }

    /**
     * Sends a request and blocks until the matching response arrives.
     *
     * @return the {@code result} object
     * @throws McpException on timeout, transport failure, or a JSON-RPC error response
     */
    private JsonNode request(String method, JsonNode params) {
        if (closed) {
            throw new McpException("MCP client is closed; cannot call " + method);
        }
        long id = nextId.getAndIncrement();
        ObjectNode message = mapper.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.set("params", params);

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            writeLine(message);
            JsonNode response = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            if (response.has("error")) {
                JsonNode error = response.get("error");
                throw new McpException("MCP server rejected '" + method + "': "
                        + error.path("message").asText("unknown error")
                        + " (code " + error.path("code").asInt() + ")");
            }
            return response.path("result");
        } catch (TimeoutException e) {
            throw new McpException("MCP server did not respond to '" + method + "' within "
                    + timeoutMillis + "ms", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("Interrupted while waiting for '" + method + "'", e);
        } catch (ExecutionException e) {
            throw new McpException("MCP request '" + method + "' failed: "
                    + e.getCause().getMessage(), e.getCause());
        } finally {
            pending.remove(id);
        }
    }

    /** Sends a notification, which by definition carries no id and expects no response. */
    private void notifyServer(String method, JsonNode params) {
        ObjectNode message = mapper.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        message.set("params", params);
        writeLine(message);
    }

    private synchronized void writeLine(ObjectNode message) {
        try {
            out.write(mapper.writeValueAsString(message));
            out.write('\n');
            out.flush();
        } catch (IOException e) {
            throw new McpException("Could not write to the MCP server: " + e.getMessage(), e);
        }
    }

    private void readLoop() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                dispatch(line);
            }
            failPending(new McpException("The MCP server closed its output stream"));
        } catch (IOException e) {
            if (!closed) {
                failPending(new McpException("Lost connection to the MCP server: " + e.getMessage(), e));
            } else {
                failPending(new McpException("MCP client closed"));
            }
        }
    }

    private void dispatch(String line) {
        JsonNode message;
        try {
            message = mapper.readTree(line);
        } catch (IOException e) {
            // Servers occasionally emit non-JSON banners on stdout; ignore rather than die.
            log.debug("Ignoring non-JSON line from MCP server: {}", line);
            return;
        }
        JsonNode id = message.get("id");
        if (id == null || id.isNull()) {
            log.debug("MCP notification: {}", message.path("method").asText("(none)"));
            return;
        }
        CompletableFuture<JsonNode> waiting = pending.get(id.asLong());
        if (waiting == null) {
            log.debug("Ignoring MCP response with unknown id {}", id);
            return;
        }
        waiting.complete(message);
    }

    private void failPending(McpException cause) {
        for (Map.Entry<Long, CompletableFuture<JsonNode>> entry : pending.entrySet()) {
            entry.getValue().completeExceptionally(cause);
        }
        pending.clear();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        readerThread.interrupt();
        try {
            out.close();
        } catch (IOException e) {
            log.debug("Error closing MCP output stream", e);
        }
        try {
            in.close();
        } catch (IOException e) {
            log.debug("Error closing MCP input stream", e);
        }
        failPending(new McpException("MCP client closed"));
    }
}
