package org.qainsights.jmeter.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import com.openai.errors.OpenAIServiceException;
import org.qainsights.jmeter.ai.agent.AgentJudgmentConfig;
import org.qainsights.jmeter.ai.agent.AgentRoutingConfig;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.qainsights.jmeter.ai.utils.GatewayConfig;
import org.qainsights.jmeter.ai.utils.SafeText;

public final class ProviderConnectionTester {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper JSON = new ObjectMapper();

    public enum Outcome {
        OK,
        AUTH_REJECTED,
        RATE_LIMITED,
        HTTP_ERROR,
        NETWORK_ERROR
    }

    public record Result(
            Outcome outcome,
            int modelCount,
            List<String> ollamaModelNames,
            int httpCode,
            String reason) {
        public Result {
            ollamaModelNames = ollamaModelNames == null ? List.of() : List.copyOf(ollamaModelNames);
        }
    }

    public record Response(int statusCode, String body) {
    }

    public record OllamaEndpoint(String host, String port) {
    }

    @FunctionalInterface
    public interface HttpSender {
        Response send(HttpRequest request) throws IOException, InterruptedException;
    }

    @FunctionalInterface
    public interface SmartRoutingTester {
        Result test(String backend, String key);
    }

    private final HttpSender sender;
    private final SmartRoutingTester smartRoutingTester;

    public ProviderConnectionTester() {
        this(defaultSender(), null);
    }

    private static HttpSender defaultSender() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        return request -> {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        };
    }

    public ProviderConnectionTester(HttpSender sender) {
        this(sender, null);
    }

    public ProviderConnectionTester(HttpSender sender, SmartRoutingTester smartRoutingTester) {
        this.sender = Objects.requireNonNull(sender);
        this.smartRoutingTester = smartRoutingTester == null ? this::testSmartRoutingReal : smartRoutingTester;
    }

    public Result test(ProviderSetupOption option, String key) {
        return test(option, key, null, -1);
    }

    public Result test(ProviderSetupOption option, String key, String ollamaHost, int ollamaPort) {
        if (option == null) {
            throw new IllegalArgumentException("A provider is required");
        }
        try {
            HttpRequest request = requestFor(option, key, ollamaHost, ollamaPort);
            Response response = sender.send(request);
            int code = response.statusCode();
            if (option == ProviderSetupOption.GEMINI && code == 400
                    && isGeminiKeyRejected(response.body())) {
                return new Result(Outcome.AUTH_REJECTED, 0, List.of(), code, "Authentication was rejected");
            }
            if (code == 401 || code == 403) {
                return new Result(Outcome.AUTH_REJECTED, 0, List.of(), code, "Authentication was rejected");
            }
            if (code == 429) {
                return new Result(Outcome.RATE_LIMITED, 0, List.of(), code, "Provider rate limit reached");
            }
            if (code < 200 || code >= 300) {
                return new Result(Outcome.HTTP_ERROR, 0, List.of(), code, "HTTP " + code);
            }
            return parseSuccess(option, response.body(), code);
        } catch (IOException e) {
            return networkError(e, key);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(Outcome.NETWORK_ERROR, 0, List.of(), 0, "Connection test was interrupted");
        } catch (RuntimeException e) {
            return networkError(e, key);
        }
    }

    public Result testSmartRouting(String backend, String key) {
        return smartRoutingTester.test(backend, key);
    }

    private Result testSmartRoutingReal(String backend, String key) {
        try {
            JudgmentProvider provider = switch (backend) {
                case "typesafe" -> new TypeSafeJudgmentProvider(
                        key,
                        AiConfig.getProperty(AgentRoutingConfig.BASE_URL_KEY,
                                TypeSafeJudgmentProvider.DEFAULT_BASE_URL),
                        AiConfig.getProperty(AgentRoutingConfig.MODEL_KEY,
                                TypeSafeJudgmentProvider.DEFAULT_MODEL),
                        AgentRoutingConfig.timeout());
                case "openai" -> new OpenAiDecisionsJudgmentProvider(
                        key,
                        AiConfig.getProperty(AgentJudgmentConfig.OPENAI_MODEL_KEY,
                                OpenAiDecisionsJudgmentProvider.DEFAULT_MODEL),
                        AgentRoutingConfig.timeout());
                case "openrouter" -> new OpenRouterDecisionsJudgmentProvider(
                        key,
                        AiConfig.getProperty(AgentJudgmentConfig.OPENROUTER_BASE_URL_KEY,
                                OpenRouterDecisionsJudgmentProvider.DEFAULT_BASE_URL),
                        AiConfig.getProperty(AgentJudgmentConfig.OPENROUTER_MODEL_KEY,
                                OpenRouterDecisionsJudgmentProvider.DEFAULT_MODEL),
                        AgentRoutingConfig.timeout());
                default -> throw new IllegalArgumentException("Unsupported smart routing backend");
            };
            provider.choose(
                    Map.of("message", "Choose the first option for this connection check."),
                    "Select the preferred option for this connection check.",
                    Map.of("FIRST", "Select the first option.", "SECOND", "Select the second option."));
            return new Result(Outcome.OK, 0, List.of(), 200, null);
        } catch (RuntimeException e) {
            return judgmentError(e, key);
        }
    }

    public Result testOllamaEmbeddings() {
        try {
            OllamaEmbeddingClient client = OllamaEmbeddingClient.fromConfig(AgentRoutingConfig.timeout());
            client.embed(List.of("Connection check"));
            return new Result(Outcome.OK, 0, List.of(), 200, null);
        } catch (RuntimeException e) {
            return judgmentError(e, null);
        }
    }

    private static Result judgmentError(RuntimeException error, String key) {
        int code = 0;
        boolean ioFailure = false;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof TypeSafeJudgmentProvider.JudgmentException judgment
                    && judgment.httpCode() != 0) {
                code = judgment.httpCode();
            } else if (cause instanceof OpenAIServiceException openAiError) {
                code = openAiError.statusCode();
            }
            ioFailure |= cause instanceof IOException;
        }
        Outcome outcome = code == 401 || code == 403 ? Outcome.AUTH_REJECTED
                : code == 429 ? Outcome.RATE_LIMITED
                : code != 0 ? Outcome.HTTP_ERROR
                : ioFailure ? Outcome.NETWORK_ERROR : Outcome.HTTP_ERROR;
        String reason = SafeText.redactUrls(error.getMessage() == null
                ? "Connection test failed" : error.getMessage());
        if (key != null && !key.isEmpty()) {
            reason = reason.replace(key, "***");
        }
        if (reason.length() > 300) {
            reason = reason.substring(0, 297) + "...";
        }
        return new Result(outcome, 0, List.of(), code, reason);
    }

    public static OllamaEndpoint normalizeOllamaHost(String value) {
        String hostValue = value == null || value.isBlank() ? "http://localhost" : value.trim();
        if (!hostValue.contains("://")) {
            hostValue = "http://" + hostValue;
        }
        URI uri = URI.create(hostValue);
        String scheme = uri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || uri.getHost() == null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || (uri.getRawPath() != null
                        && !uri.getRawPath().isEmpty()
                        && !"/".equals(uri.getRawPath()))) {
            throw new IllegalArgumentException("Invalid Ollama host");
        }

        String authority = uri.getRawAuthority();
        String port = uri.getPort() < 0 ? null : Integer.toString(uri.getPort());
        if (port != null) {
            authority = authority.substring(0, authority.length() - port.length() - 1);
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        String normalizedHost = scheme.toLowerCase(Locale.ROOT) + "://" + authority + path;
        while (normalizedHost.endsWith("/")) {
            normalizedHost = normalizedHost.substring(0, normalizedHost.length() - 1);
        }
        return new OllamaEndpoint(normalizedHost, port);
    }

    public static String ollamaBaseUrl(String host, String port) {
        OllamaEndpoint endpoint = normalizeOllamaHost(host);
        String resolvedPort = endpoint.port() == null ? port : endpoint.port();
        return OllamaAiService.buildHost(endpoint.host(), resolvedPort);
    }

    private static HttpRequest requestFor(
            ProviderSetupOption option, String key, String ollamaHost, int ollamaPort) {
        String url;
        switch (option) {
            case ANTHROPIC -> url = appendPath(GatewayConfig.anthropicBaseUrl(), "/v1/models");
            case OPENAI -> url = appendPath(GatewayConfig.openAiBaseUrl(), "/models");
            case GEMINI -> url = "https://generativelanguage.googleapis.com/v1beta/models";
            case DEEPSEEK -> url = appendPath(
                    AiConfig.getProperty("deepseek.base.url", "https://api.deepseek.com"), "/models");
            case GROK -> url = appendPath(
                    AiConfig.getProperty("grok.base.url", "https://api.x.ai/v1"), "/models");
            case META_MUSE -> url = appendPath(
                    AiConfig.getProperty("meta.base.url", "https://api.meta.ai/v1"), "/models");
            case OLLAMA -> {
                String host = ollamaHost == null
                        ? AiConfig.getProperty("ollama.host", "http://localhost") : ollamaHost;
                String port = ollamaPort < 0
                        ? AiConfig.getProperty("ollama.port", "11434") : Integer.toString(ollamaPort);
                url = appendPath(ollamaBaseUrl(host, port), "/api/tags");
            }
            default -> throw new IllegalArgumentException("Unsupported provider");
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .GET();
        if (option == ProviderSetupOption.ANTHROPIC) {
            builder.header("x-api-key", key == null ? "" : key)
                    .header("anthropic-version", "2023-06-01");
        } else if (option == ProviderSetupOption.GEMINI) {
            builder.header("x-goog-api-key", key == null ? "" : key);
        } else if (option.requiresKey()) {
            builder.header("Authorization", "Bearer " + (key == null ? "" : key));
        }
        return builder.build();
    }

    private static Result parseSuccess(ProviderSetupOption option, String body, int code) {
        try {
            JsonNode root = JSON.readTree(body == null ? "{}" : body);
            JsonNode models = root.path("models");
            if (!models.isArray()) {
                models = root.path("data");
            }
            if (!models.isArray()) {
                return new Result(Outcome.HTTP_ERROR, 0, List.of(), code, "Unexpected model-list response");
            }
            if (option == ProviderSetupOption.OLLAMA) {
                List<String> names = new ArrayList<>();
                for (JsonNode model : models) {
                    String name = model.path("name").asText("");
                    if (!name.isBlank()) {
                        names.add(name);
                    }
                }
                return new Result(Outcome.OK, names.size(), names, code, null);
            }
            return new Result(Outcome.OK, models.size(), List.of(), code, null);
        } catch (IOException | RuntimeException e) {
            return new Result(Outcome.HTTP_ERROR, 0, List.of(), code, "Unexpected model-list response");
        }
    }

    private static boolean isGeminiKeyRejected(String body) {
        if (body == null) {
            return false;
        }
        return body.contains("API_KEY_INVALID")
                || body.toLowerCase(Locale.ROOT).contains("api key not valid");
    }

    private static Result networkError(Exception error, String key) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = "Connection failed";
        }
        message = SafeText.redactUrls(message);
        if (key != null && !key.isEmpty()) {
            message = message.replace(key, "***");
        }
        if (message.length() > 300) {
            message = message.substring(0, 297) + "...";
        }
        return new Result(Outcome.NETWORK_ERROR, 0, List.of(), 0, message);
    }

    private static String appendPath(String base, String path) {
        String resolved = base == null || base.isBlank() ? "" : base.trim();
        while (resolved.endsWith("/")) {
            resolved = resolved.substring(0, resolved.length() - 1);
        }
        return resolved + path;
    }

}
