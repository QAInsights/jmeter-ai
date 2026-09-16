package org.qainsights.jmeter.ai.utils;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration shared by the OpenAI-compatible and Anthropic gateway clients.
 */
public final class GatewayConfig {
    public static final String OPENAI_DEFAULT_BASE_URL = "https://api.openai.com/v1";
    public static final String ANTHROPIC_DEFAULT_BASE_URL = "https://api.anthropic.com";
    /** Matches the SDK defaults, which retry 408/409/429/5xx with backoff. */
    public static final int DEFAULT_MAX_RETRIES = 2;

    private static final Logger log = LoggerFactory.getLogger(GatewayConfig.class);

    private GatewayConfig() {
    }

    public static String openAiBaseUrl() {
        return configuredBaseUrl("openai.base.url", OPENAI_DEFAULT_BASE_URL);
    }

    public static String anthropicBaseUrl() {
        return configuredBaseUrl("anthropic.base.url", ANTHROPIC_DEFAULT_BASE_URL);
    }

    public static boolean isOpenAiGateway() {
        return !OPENAI_DEFAULT_BASE_URL.equals(openAiBaseUrl());
    }

    public static boolean isAnthropicGateway() {
        return !ANTHROPIC_DEFAULT_BASE_URL.equals(anthropicBaseUrl());
    }

    /**
     * True when a custom OpenAI-compatible gateway can authenticate with
     * configured headers instead of a vendor API key.
     */
    public static boolean hasOpenAiGatewayCredentials() {
        return isOpenAiGateway() && !openAiHeaders().isEmpty();
    }

    /**
     * True when a custom Anthropic-compatible gateway can authenticate with
     * configured headers instead of a vendor API key.
     */
    public static boolean hasAnthropicGatewayCredentials() {
        return isAnthropicGateway() && !anthropicHeaders().isEmpty();
    }

    public static Map<String, String> openAiHeaders() {
        return parseHeaders(AiConfig.getProperty("openai.extra.headers", ""));
    }

    public static Map<String, String> anthropicHeaders() {
        return parseHeaders(AiConfig.getProperty("anthropic.extra.headers", ""));
    }

    public static List<String> openAiConfiguredModels() {
        return parseModels(AiConfig.getProperty("openai.models", ""));
    }

    public static List<String> anthropicConfiguredModels() {
        return parseModels(AiConfig.getProperty("anthropic.models", ""));
    }

    static Map<String, String> parseHeaders(String raw) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return headers;
        }

        for (String entry : raw.split(";", -1)) {
            if (entry.trim().isEmpty()) {
                continue;
            }
            int separator = entry.indexOf('=');
            if (separator < 0) {
                log.warn("Ignoring malformed gateway header entry without '=': {}", entry);
                continue;
            }
            String name = entry.substring(0, separator).trim();
            String value = entry.substring(separator + 1).trim();
            if (name.isEmpty()) {
                log.warn("Ignoring malformed gateway header entry with empty name: {}", entry);
                continue;
            }
            headers.put(name, value);
        }
        return headers;
    }

    /**
     * Automatic SDK retries for OpenAI-compatible requests. Each retry of a 429
     * response re-sends the whole prompt, so quota-metered gateways may want 0.
     */
    public static int openAiMaxRetries() {
        return parseRetries(AiConfig.getProperty("openai.max.retries", ""));
    }

    public static int anthropicMaxRetries() {
        return parseRetries(AiConfig.getProperty("anthropic.max.retries", ""));
    }

    static int parseRetries(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return DEFAULT_MAX_RETRIES;
        }
        try {
            int retries = Integer.parseInt(raw.trim());
            if (retries < 0) {
                log.warn("Ignoring negative max.retries value {}; using default {}", raw, DEFAULT_MAX_RETRIES);
                return DEFAULT_MAX_RETRIES;
            }
            return retries;
        } catch (NumberFormatException e) {
            log.warn("Ignoring non-numeric max.retries value '{}'; using default {}", raw, DEFAULT_MAX_RETRIES);
            return DEFAULT_MAX_RETRIES;
        }
    }

    static List<String> parseModels(String raw) {
        List<String> models = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return models;
        }

        for (String model : raw.split(",", -1)) {
            String trimmed = model.trim();
            if (!trimmed.isEmpty()) {
                models.add(trimmed);
            }
        }
        return models;
    }

    public static OpenAIOkHttpClient.Builder apply(OpenAIOkHttpClient.Builder builder) {
        String baseUrl = openAiBaseUrl();
        if (baseUrl.regionMatches(true, 0, "http://", 0, 7)) {
            log.warn("Gateway base URL {} uses plaintext HTTP; API keys and prompts will be sent unencrypted",
                    baseUrl);
        }
        builder.baseUrl(baseUrl);
        builder.maxRetries(openAiMaxRetries());
        openAiHeaders().forEach(builder::putHeader);
        return builder;
    }

    public static AnthropicOkHttpClient.Builder apply(AnthropicOkHttpClient.Builder builder) {
        String baseUrl = anthropicBaseUrl();
        if (baseUrl.regionMatches(true, 0, "http://", 0, 7)) {
            log.warn("Gateway base URL {} uses plaintext HTTP; API keys and prompts will be sent unencrypted",
                    baseUrl);
        }
        builder.baseUrl(baseUrl);
        builder.maxRetries(anthropicMaxRetries());
        anthropicHeaders().forEach(builder::putHeader);
        return builder;
    }

    private static String configuredBaseUrl(String property, String defaultUrl) {
        String configured = AiConfig.getProperty(property, defaultUrl);
        if (configured == null || configured.trim().isEmpty()) {
            return defaultUrl;
        }
        return configured.trim();
    }
}
