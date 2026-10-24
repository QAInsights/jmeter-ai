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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class TypeSafeJudgmentProvider implements JudgmentProvider {

    public static final String DEFAULT_BASE_URL = "https://api.typesafe.ai";
    public static final String DEFAULT_MODEL = "jev-latest";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    static final String SCORE_QUESTION = "severity";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final HttpClient SHARED_CLIENT = HttpClient.newBuilder().build();

    private final HttpClient client;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final String label;

    public TypeSafeJudgmentProvider(String apiKey, String baseUrl, String model, Duration timeout) {
        this(SHARED_CLIENT, apiKey, endpoint(baseUrl), model, timeout);
    }

    TypeSafeJudgmentProvider(HttpClient client, String apiKey, URI endpoint, String model, Duration timeout) {
        this(client, apiKey, endpoint, model, timeout, "TypeSafe");
    }

    /** Same System One wire format sent to another host (e.g. OpenRouter); {@code label} prefixes errors. */
    TypeSafeJudgmentProvider(HttpClient client, String apiKey, URI endpoint, String model, Duration timeout,
                             String label) {
        this.label = label;
        this.client = client;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.endpoint = endpoint;
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
        this.timeout = normalizeTimeout(timeout);
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.CHOICE, Capability.SCORE);
    }

    @Override
    public ChoiceAnswer choose(Object state, String instructions, Map<String, String> criteria) {
        if (apiKey.isEmpty()) {
            throw new JudgmentException(label + " API key is not configured");
        }
        if (instructions == null || instructions.isBlank() || criteria == null || criteria.size() < 2) {
            throw new JudgmentException("A choice judgment needs instructions and at least two criteria");
        }
        try {
            return parseChoice(send(requestBody(state, instructions, criteria)), criteria, label);
        } catch (IOException | IllegalArgumentException e) {
            throw new JudgmentException(label + " request failed", e);
        }
    }

    @Override
    public ScoreAnswer score(Object state, String instructions, Map<String, String> levels) {
        if (apiKey.isEmpty()) {
            throw new JudgmentException(label + " API key is not configured");
        }
        if (instructions == null || instructions.isBlank() || levels == null || levels.size() < 2) {
            throw new JudgmentException("A score judgment needs instructions and at least two levels");
        }
        try {
            return parseScore(send(scoreRequestBody(state, instructions, levels)), levels.size(), label);
        } catch (IOException | IllegalArgumentException e) {
            throw new JudgmentException(label + " request failed", e);
        }
    }

    private String send(String body) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new JudgmentException(
                        label + " request failed with HTTP " + response.statusCode(), response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JudgmentException(label + " request was interrupted", e);
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

    private String scoreRequestBody(Object state, String instructions, Map<String, String> levels)
            throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.set("state", MAPPER.valueToTree(state));
        root.put("model", model);
        ObjectNode question = root.putObject("questions").putObject(SCORE_QUESTION);
        question.put("type", "score");
        question.put("instructions", instructions);
        ArrayNode criteria = question.putArray("criteria");
        JudgmentProvider.levelDescriptions(levels).forEach(criteria::add);
        return MAPPER.writeValueAsString(root);
    }

    static ScoreAnswer parseScore(String body, int levelCount, String label) throws IOException {
        JsonNode answer = MAPPER.readTree(body).path("answers").path(SCORE_QUESTION);
        if (!"score".equals(answer.path("type").asText())) {
            throw new JudgmentException(label + " response did not contain a score answer");
        }
        JsonNode scoreNode = answer.path("score");
        JsonNode confidenceNode = answer.path("confidence");
        if (!scoreNode.isNumber() || !confidenceNode.isNumber()) {
            throw new JudgmentException(label + " response contained an invalid score");
        }
        double score = scoreNode.asDouble();
        double confidence = confidenceNode.asDouble();
        if (!JudgmentProvider.validScore(score, confidence, levelCount)) {
            throw new JudgmentException(label + " response contained an invalid score");
        }
        return new ScoreAnswer(score, confidence);
    }

    private static ChoiceAnswer parseChoice(String body, Map<String, String> criteria, String label)
            throws IOException {
        JsonNode answer = MAPPER.readTree(body).path("answers").path("route");
        if (!"choice".equals(answer.path("type").asText())) {
            throw new JudgmentException(label + " response did not contain a choice answer");
        }
        String choice = answer.path("choice").asText("");
        if (!criteria.containsKey(choice)) {
            throw new JudgmentException(label + " response contained an unknown choice");
        }
        double confidence = answer.path("confidence").asDouble(Double.NaN);
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new JudgmentException(label + " response contained invalid confidence");
        }
        Map<String, Double> probabilities = new LinkedHashMap<>();
        JsonNode probabilityNode = answer.path("probabilities");
        for (String option : criteria.keySet()) {
            JsonNode value = probabilityNode.path(option);
            double probability = value.isMissingNode() ? 0.0 : value.asDouble(Double.NaN);
            if (!Double.isFinite(probability) || probability < 0 || probability > 1) {
                throw new JudgmentException(label + " response contained invalid probabilities");
            }
            probabilities.put(option, probability);
        }
        return new ChoiceAnswer(choice, probabilities, confidence);
    }

    private static Duration normalizeTimeout(Duration timeout) {
        return timeout == null || timeout.isZero() || timeout.isNegative() ? DEFAULT_TIMEOUT : timeout;
    }

    public static final class JudgmentException extends RuntimeException {
        private final boolean pending;
        private final int httpCode;

        public JudgmentException(String message) {
            super(message);
            this.pending = false;
            this.httpCode = 0;
        }

        public JudgmentException(String message, Throwable cause) {
            super(message, cause);
            this.pending = false;
            this.httpCode = 0;
        }

        public JudgmentException(String message, int httpCode) {
            super(message);
            this.pending = false;
            this.httpCode = httpCode;
        }

        public JudgmentException(String message, boolean pending) {
            super(message);
            this.pending = pending;
            this.httpCode = 0;
        }

        public boolean isPending() {
            return pending;
        }

        public int httpCode() {
            return httpCode;
        }
    }
}
