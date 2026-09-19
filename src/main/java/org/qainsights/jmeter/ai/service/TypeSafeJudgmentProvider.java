package org.qainsights.jmeter.ai.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class TypeSafeJudgmentProvider implements JudgmentProvider {

    public static final String DEFAULT_BASE_URL = "https://api.typesafe.ai";
    public static final String DEFAULT_MODEL = "jev-latest";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient SHARED_CLIENT = HttpClient.newBuilder().build();

    private final HttpClient client;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;

    public TypeSafeJudgmentProvider(String apiKey, String baseUrl, String model, Duration timeout) {
        this(SHARED_CLIENT, apiKey, endpoint(baseUrl), model, timeout);
    }

    TypeSafeJudgmentProvider(HttpClient client, String apiKey, URI endpoint, String model, Duration timeout) {
        this.client = client;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.endpoint = endpoint;
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
        this.timeout = normalizeTimeout(timeout);
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.CHOICE);
    }

    @Override
    public ChoiceAnswer choose(Object state, String instructions, Map<String, String> criteria) {
        if (apiKey.isEmpty()) {
            throw new JudgmentException("TypeSafe API key is not configured");
        }
        if (instructions == null || instructions.isBlank() || criteria == null || criteria.size() < 2) {
            throw new JudgmentException("A choice judgment needs instructions and at least two criteria");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody(state, instructions, criteria)))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new JudgmentException("TypeSafe request failed with HTTP " + response.statusCode());
            }
            return parseChoice(response.body(), criteria);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JudgmentException("TypeSafe request was interrupted", e);
        } catch (IOException | IllegalArgumentException e) {
            throw new JudgmentException("TypeSafe request failed", e);
        }
    }

    static URI endpoint(String baseUrl) {
        String value = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.trim();
        value = value.replaceAll("/+$", "");
        if (value.endsWith("/v1")) {
            value += "/systemone";
        } else if (!value.endsWith("/v1/systemone")) {
            value += "/v1/systemone";
        }
        return URI.create(value);
    }

    private String requestBody(Object state, String instructions, Map<String, String> criteria) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.set("state", MAPPER.valueToTree(state));
        root.put("model", model);
        ObjectNode route = root.putObject("questions").putObject("route");
        route.put("type", "choice");
        route.put("instructions", instructions);
        ObjectNode criteriaNode = route.putObject("criteria");
        criteria.forEach(criteriaNode::put);
        return MAPPER.writeValueAsString(root);
    }

    private static ChoiceAnswer parseChoice(String body, Map<String, String> criteria) throws IOException {
        JsonNode answer = MAPPER.readTree(body).path("answers").path("route");
        if (!"choice".equals(answer.path("type").asText())) {
            throw new JudgmentException("TypeSafe response did not contain a choice answer");
        }
        String choice = answer.path("choice").asText("");
        if (!criteria.containsKey(choice)) {
            throw new JudgmentException("TypeSafe response contained an unknown choice");
        }
        double confidence = answer.path("confidence").asDouble(Double.NaN);
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new JudgmentException("TypeSafe response contained invalid confidence");
        }
        Map<String, Double> probabilities = new LinkedHashMap<>();
        JsonNode probabilityNode = answer.path("probabilities");
        for (String option : criteria.keySet()) {
            JsonNode value = probabilityNode.path(option);
            double probability = value.isMissingNode() ? 0.0 : value.asDouble(Double.NaN);
            if (!Double.isFinite(probability) || probability < 0 || probability > 1) {
                throw new JudgmentException("TypeSafe response contained invalid probabilities");
            }
            probabilities.put(option, probability);
        }
        return new ChoiceAnswer(choice, probabilities, confidence);
    }

    private static Duration normalizeTimeout(Duration timeout) {
        return timeout == null || timeout.isZero() || timeout.isNegative() ? DEFAULT_TIMEOUT : timeout;
    }

    public static final class JudgmentException extends RuntimeException {
        public JudgmentException(String message) {
            super(message);
        }

        public JudgmentException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
