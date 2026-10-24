package org.qainsights.jmeter.ai.service;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.qainsights.jmeter.ai.service.TypeSafeJudgmentProvider.JudgmentException;
import org.qainsights.jmeter.ai.utils.AiConfig;

/**
 * Minimal client for Ollama's {@code POST /api/embed}. It reuses {@code ollama.host} and
 * {@code ollama.port}; the model comes from {@code jmeter.ai.embeddings.model}.
 */
public final class OllamaEmbeddingClient {

    public static final String MODEL_KEY = "jmeter.ai.embeddings.model";
    public static final String DEFAULT_MODEL = "embeddinggemma";
    private static final int MAX_ERROR_CHARS = 200;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient SHARED_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private final HttpClient client;
    private final String host;
    private final URI endpoint;
    private final String model;
    private final Duration timeout;

    public OllamaEmbeddingClient(String host, String model, Duration timeout) {
        this(SHARED_CLIENT, host, model, timeout);
    }

    OllamaEmbeddingClient(HttpClient client, String host, String model, Duration timeout) {
        this.client = client;
        this.host = host == null || host.isBlank() ? "http://localhost:11434" : stripSlash(host.trim());
        this.endpoint = URI.create(this.host + "/api/embed");
        this.model = model(model);
        this.timeout = timeout == null || timeout.isZero() || timeout.isNegative()
                ? TypeSafeJudgmentProvider.DEFAULT_TIMEOUT : timeout;
    }

    /** Client for the configured Ollama server and embedding model. */
    public static OllamaEmbeddingClient fromConfig(Duration timeout) {
        return new OllamaEmbeddingClient(
                OllamaAiService.buildHost(AiConfig.getProperty("ollama.host", "http://localhost"),
                        AiConfig.getProperty("ollama.port", "11434")),
                AiConfig.getProperty(MODEL_KEY, DEFAULT_MODEL), timeout);
    }

    /** The configured model name, or the default when blank. */
    public static String model(String value) {
        return value == null || value.isBlank() ? DEFAULT_MODEL : value.trim();
    }

    public String baseUrl() {
        return host;
    }

    public String model() {
        return model;
    }

    /** One embedding per input, in input order. Throws {@link JudgmentException} on any failure. */
    public List<float[]> embed(List<String> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw new JudgmentException("No text to embed");
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        ArrayNode input = body.putArray("input");
        inputs.forEach(input::add);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new JudgmentException("Ollama embedding request timed out after " + timeout.toSeconds() + "s", e);
        } catch (ConnectException e) {
            throw new JudgmentException("Ollama is not reachable at " + host + "; start it with `ollama serve`", e);
        } catch (IOException e) {
            throw new JudgmentException("Ollama embedding request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JudgmentException("Ollama embedding request was interrupted", e);
        }
        return parse(response.statusCode(), response.body(), inputs.size());
    }

    private List<float[]> parse(int status, String text, int expected) {
        JsonNode root = readTree(text);
        String error = root == null ? "" : root.path("error").asText("");
        if (status == 404 || error.toLowerCase(Locale.ROOT).contains("not found")) {
            throw new JudgmentException(
                    "Ollama model '" + model + "' is not available; run `ollama pull " + model + "`", 404);
        }
        if (status < 200 || status >= 300) {
            String detail = error.isBlank() ? "" : ": " + truncate(error);
            throw new JudgmentException("Ollama embedding request failed with HTTP " + status + detail, status);
        }
        JsonNode embeddings = root == null ? null : root.get("embeddings");
        if (embeddings == null || !embeddings.isArray() || embeddings.size() != expected) {
            throw new JudgmentException("Ollama returned " + (embeddings == null || !embeddings.isArray()
                    ? "no" : embeddings.size()) + " embeddings for " + expected + " inputs");
        }
        List<float[]> vectors = new ArrayList<>(expected);
        for (JsonNode vector : embeddings) {
            if (!vector.isArray() || vector.isEmpty()) {
                throw new JudgmentException("Ollama returned an empty embedding");
            }
            float[] values = new float[vector.size()];
            for (int i = 0; i < values.length; i++) {
                JsonNode value = vector.get(i);
                if (!value.isNumber()) {
                    throw new JudgmentException("Ollama returned a non-numeric embedding");
                }
                values[i] = (float) value.asDouble();
            }
            vectors.add(values);
        }
        return vectors;
    }

    private static JsonNode readTree(String text) {
        try {
            return text == null || text.isBlank() ? null : MAPPER.readTree(text);
        } catch (IOException e) {
            return null;
        }
    }

    private static String truncate(String value) {
        return value.length() <= MAX_ERROR_CHARS ? value : value.substring(0, MAX_ERROR_CHARS) + "...";
    }

    private static String stripSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
