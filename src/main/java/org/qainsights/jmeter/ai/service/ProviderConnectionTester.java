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
import java.util.Objects;
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

    private final HttpSender sender;

    public ProviderConnectionTester() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        this.sender = request -> {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        };
    }

    public ProviderConnectionTester(HttpSender sender) {
        this.sender = Objects.requireNonNull(sender);
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
                || uri.getRawFragment() != null) {
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
