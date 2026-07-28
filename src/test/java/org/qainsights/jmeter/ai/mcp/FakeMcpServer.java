package org.qainsights.jmeter.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * An in-memory MCP server for transport tests: a pair of pipes plus a thread that answers
 * newline-delimited JSON-RPC requests using a caller-supplied handler.
 * <p>
 * Lets the whole client stack be tested without Node, npx, a browser, or a real socket,
 * and makes otherwise-awkward cases (no response at all, a mid-request stream close,
 * out-of-order replies) trivial to reproduce deterministically.
 */
final class FakeMcpServer implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PipedInputStream clientReads;
    private final PipedOutputStream clientWrites;
    private final Writer serverWriter;
    private final BufferedReader serverReader;
    private final Thread thread;
    private final List<JsonNode> received = Collections.synchronizedList(new ArrayList<>());

    private volatile boolean running = true;

    /**
     * @param handler receives each request and returns the raw response line, or null to
     *                stay silent (used to exercise client-side timeouts)
     */
    FakeMcpServer(Function<JsonNode, String> handler) {
        try {
            // Generous buffers: a tool list with full JSON Schemas exceeds the 1KB default.
            clientReads = new PipedInputStream(64 * 1024);
            PipedOutputStream serverWrites = new PipedOutputStream(clientReads);
            PipedInputStream serverReads = new PipedInputStream(64 * 1024);
            clientWrites = new PipedOutputStream(serverReads);

            serverWriter = new OutputStreamWriter(serverWrites, StandardCharsets.UTF_8);
            serverReader = new BufferedReader(new InputStreamReader(serverReads, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Could not create fake MCP pipes", e);
        }

        thread = new Thread(() -> serve(handler), "fake-mcp-server");
        thread.setDaemon(true);
        thread.start();
    }

    private void serve(Function<JsonNode, String> handler) {
        try {
            String line;
            while (running && (line = serverReader.readLine()) != null) {
                JsonNode request = MAPPER.readTree(line);
                received.add(request);
                String response = handler.apply(request);
                if (response != null) {
                    send(response);
                }
            }
        } catch (IOException e) {
            // Expected when the client closes its end.
        }
    }

    /** Sends a raw line to the client, bypassing the handler. */
    synchronized void send(String rawLine) {
        try {
            serverWriter.write(rawLine);
            serverWriter.write('\n');
            serverWriter.flush();
        } catch (IOException e) {
            // Client has gone away; nothing useful to do in a test double.
        }
    }

    /** Simulates the server dying: closes its output so the client sees EOF. */
    void closeOutput() {
        try {
            serverWriter.close();
        } catch (IOException e) {
            // ignore
        }
    }

    /** The stream the client should treat as the server's stdout. */
    InputStream clientInput() {
        return clientReads;
    }

    /** The stream the client should treat as the server's stdin. */
    OutputStream clientOutput() {
        return clientWrites;
    }

    /** Every request the server has received, in arrival order. */
    List<JsonNode> received() {
        return received;
    }

    /** Waits until at least {@code count} requests have arrived, or the timeout expires. */
    boolean awaitRequests(int count, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (received.size() >= count) {
                return true;
            }
            Thread.sleep(10);
        }
        return received.size() >= count;
    }

    /** Builds a JSON-RPC success response echoing the request's id. */
    static String resultFor(JsonNode request, String resultJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + request.get("id") + ",\"result\":" + resultJson + "}";
    }

    /** Builds a JSON-RPC error response echoing the request's id. */
    static String errorFor(JsonNode request, int code, String message) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + request.get("id")
                + ",\"error\":{\"code\":" + code + ",\"message\":\"" + message + "\"}}";
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
        try {
            serverWriter.close();
        } catch (IOException e) {
            // ignore
        }
        try {
            serverReader.close();
        } catch (IOException e) {
            // ignore
        }
    }
}
