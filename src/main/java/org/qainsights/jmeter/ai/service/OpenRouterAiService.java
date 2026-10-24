package org.qainsights.jmeter.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import org.qainsights.jmeter.ai.agent.AgentJudgmentConfig;
import org.qainsights.jmeter.ai.service.usage.UsageStats;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.qainsights.jmeter.ai.utils.Constants;
import org.qainsights.jmeter.ai.utils.ModelUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/** OpenAI-compatible Chat Completions service for OpenRouter. */
public class OpenRouterAiService implements AiService {

    public static final String MODEL_PREFIX = "openrouter:";
    public static final String DEFAULT_BASE_URL = "https://openrouter.ai/api/v1";
    public static final String DEFAULT_MODEL = "anthropic/claude-sonnet-5.5";
    public static final String DEFAULT_FEATURED_MODELS =
            "anthropic/claude-sonnet-5.5,anthropic/claude-opus-5.5,openai/gpt-6.1-sol,"
                    + "google/gemini-4-argon,deepseek/deepseek-v4.1-flash,qwen/qwen3.8-max-prime,"
                    + "moonshotai/kimi-k3.1-pro,x-ai/grok-4.7";
    static final String REFERER_HEADER = "HTTP-Referer";
    static final String REFERER_VALUE = "https://jmeter.ai";
    static final String TITLE_HEADER = "X-Title";
    static final String TITLE_VALUE = "Feather Wand";

    private static final Logger log = LoggerFactory.getLogger(OpenRouterAiService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration MODEL_LIST_TIMEOUT = Duration.ofSeconds(10);
    private static final String CLIENT_NOT_INITIALIZED_MESSAGE =
            "OpenRouter client not initialized. Set openrouter.api.key in "
                    + "user.properties (or jmeter.properties) and restart JMeter.";

    private final OpenAIClient openAiClient;
    private final HttpClient modelListClient;
    private final String apiKey;
    private final String baseUrl;
    private final int maxHistorySize;
    private final String systemPrompt;
    private final float temperature;
    private final long maxTokens;
    private final List<String> featuredModels;
    private final String defaultModel;
    private String model;
    private volatile List<String> cachedModels;
    private UsageStats usageStats;

    private record ModelCatalog(List<String> models, boolean successful) {
    }

    public OpenRouterAiService() {
        this(AiConfig.getProperty(AgentJudgmentConfig.OPENROUTER_API_KEY, ""),
                AiConfig.getProperty(AgentJudgmentConfig.OPENROUTER_BASE_URL_KEY, DEFAULT_BASE_URL),
                AiConfig.getProperty("openrouter.default.model", DEFAULT_MODEL),
                AiConfig.getProperty("openrouter.temperature", "0.7"),
                AiConfig.getProperty("openrouter.max.history.size", "10"),
                AiConfig.getProperty("openrouter.max.tokens", "4096"),
                AiConfig.getProperty("openrouter.system.prompt", ""),
                AiConfig.getProperty("openrouter.models", DEFAULT_FEATURED_MODELS),
                HttpClient.newBuilder().connectTimeout(MODEL_LIST_TIMEOUT).build());
    }

    private OpenRouterAiService(
            String apiKey,
            String baseUrl,
            String model,
            String temperature,
            String maxHistorySize,
            String maxTokens,
            String systemPrompt,
            String featuredModels,
            HttpClient modelListClient) {
        this(apiKey, baseUrl, model, ModelUtils.parseTemperature(temperature),
                Integer.parseInt(maxHistorySize), Long.parseLong(maxTokens),
                systemPrompt, parseFeaturedModels(featuredModels), modelListClient);
    }

    /** Package-private constructor for tests. */
    OpenRouterAiService(
            OpenAIClient openAiClient,
            String apiKey,
            String baseUrl,
            String model,
            float temperature,
            int maxHistorySize,
            long maxTokens,
            String systemPrompt,
            List<String> featuredModels,
            HttpClient modelListClient) {
        this.openAiClient = openAiClient;
        this.apiKey = usableApiKey(apiKey) ? apiKey.trim() : null;
        this.baseUrl = chatBaseUrl(baseUrl);
        this.defaultModel = model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
        this.model = defaultModel;
        this.temperature = temperature;
        this.maxHistorySize = maxHistorySize;
        this.maxTokens = maxTokens;
        this.systemPrompt = systemPrompt == null || systemPrompt.isEmpty()
                ? Constants.DEFAULT_JMETER_SYSTEM_PROMPT : systemPrompt;
        this.featuredModels = featuredModels == null ? List.of() : List.copyOf(featuredModels);
        this.modelListClient = modelListClient;
    }

    private OpenRouterAiService(
            String apiKey,
            String baseUrl,
            String model,
            float temperature,
            int maxHistorySize,
            long maxTokens,
            String systemPrompt,
            List<String> featuredModels,
            HttpClient modelListClient) {
        this(apiKey != null && usableApiKey(apiKey)
                        ? OpenAIOkHttpClient.builder()
                                .apiKey(apiKey.trim())
                                .baseUrl(chatBaseUrl(baseUrl))
                                .putHeader(REFERER_HEADER, REFERER_VALUE)
                                .putHeader(TITLE_HEADER, TITLE_VALUE)
                                .build()
                        : null,
                apiKey, baseUrl, model, temperature, maxHistorySize, maxTokens,
                systemPrompt, featuredModels, modelListClient);
    }

    private static boolean usableApiKey(String value) {
        return value != null && !value.isBlank()
                && !"YOUR_OPENROUTER_API_KEY".equalsIgnoreCase(value.trim());
    }

    static String chatBaseUrl(String configured) {
        if (configured == null || configured.isBlank()) {
            return DEFAULT_BASE_URL;
        }
        String baseUrl = trimTrailingSlashes(configured.trim());
        String decisionsSuffix = "/api/alpha/decisions";
        if (baseUrl.endsWith(decisionsSuffix)) {
            return baseUrl.substring(0, baseUrl.length() - decisionsSuffix.length()) + "/api/v1";
        }
        if (baseUrl.endsWith("/api")) {
            return baseUrl + "/v1";
        }
        return baseUrl;
    }

    private static List<String> parseFeaturedModels(String configured) {
        String value = configured == null ? "" : configured;
        List<String> models = new ArrayList<>();
        for (String part : value.split(",")) {
            String model = part.trim();
            if (!model.isEmpty() && !models.contains(model)) {
                models.add(model);
            }
        }
        return List.copyOf(models);
    }

    public OpenAIClient getClient() {
        return openAiClient;
    }

    public void setModel(String modelId) {
        this.model = modelId;
        log.info("OpenRouter model set to: {}", modelId);
    }

    public String getCurrentModel() {
        return model;
    }

    public List<String> featuredModelIds() {
        if (openAiClient == null) {
            return List.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String featured : featuredModels) {
            if (featured != null && !featured.isBlank()) {
                ids.add(prefixedModelId(featured));
            }
        }
        ids.add(prefixedModelId(defaultModel));
        return List.copyOf(ids);
    }

    private static String prefixedModelId(String modelId) {
        return modelId.startsWith(MODEL_PREFIX) ? modelId : MODEL_PREFIX + modelId;
    }

    @Override
    public void setUsageStats(UsageStats stats) {
        this.usageStats = stats;
    }

    @Override
    public String getName() {
        return "OpenRouter";
    }

    @Override
    public String generateResponse(List<String> conversation) {
        return generateResponse(conversation, model);
    }

    @Override
    public String generateResponse(List<String> conversation, String model) {
        if (openAiClient == null) {
            return "Error: " + CLIENT_NOT_INITIALIZED_MESSAGE;
        }
        String modelToUse = model == null || model.isBlank() ? this.model : model;
        try {
            ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                    .maxCompletionTokens(maxTokens)
                    .model(modelToUse);
            if (supportsCustomTemperature(modelToUse)) {
                paramsBuilder.temperature((double) temperature);
            }
            paramsBuilder.addSystemMessage(systemPrompt);
            appendConversation(paramsBuilder, filterErrorMessages(buildLimitedHistory(conversation)));
            ChatCompletion completion = openAiClient.chat().completions().create(paramsBuilder.build());
            if (usageStats != null) {
                completion.usage().ifPresent(usage ->
                        usageStats.record("openrouter:" + modelToUse,
                                usage.promptTokens(), usage.completionTokens()));
            }
            return completion.choices().get(0).message().content().orElse("No content available");
        } catch (Exception e) {
            log.error("Error generating response from OpenRouter: {}", e.getClass().getSimpleName());
            return "Error: " + safeErrorMessage(e);
        }
    }

    @Override
    public Runnable generateStreamResponse(
            List<String> conversation,
            String model,
            Consumer<String> tokenConsumer,
            Runnable onComplete,
            Consumer<Exception> onError) {
        return generateStreamResponse(conversation, model, tokenConsumer,
                ignored -> { }, onComplete, onError);
    }

    @Override
    public Runnable generateStreamResponse(
            List<String> conversation,
            String model,
            Consumer<String> tokenConsumer,
            Consumer<String> reasoningConsumer,
            Runnable onComplete,
            Consumer<Exception> onError) {
        if (openAiClient == null) {
            javax.swing.SwingUtilities.invokeLater(
                    () -> onError.accept(new IllegalStateException(CLIENT_NOT_INITIALIZED_MESSAGE)));
            return () -> { };
        }
        String modelToUse = model == null || model.isBlank() ? this.model : model;
        ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                .maxCompletionTokens(maxTokens)
                .model(modelToUse)
                .streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build())
                .addSystemMessage(systemPrompt);
        if (supportsCustomTemperature(modelToUse)) {
            paramsBuilder.temperature((double) temperature);
        }
        appendConversation(paramsBuilder, filterErrorMessages(buildLimitedHistory(conversation)));

        Thread streamThread = new Thread(() -> {
            try {
                java.util.concurrent.atomic.AtomicReference<com.openai.models.completions.CompletionUsage>
                        streamUsage = new java.util.concurrent.atomic.AtomicReference<>();
                try (com.openai.core.http.StreamResponse<ChatCompletionChunk> stream =
                             openAiClient.chat().completions().createStreaming(paramsBuilder.build())) {
                    stream.stream().forEach(chunk -> {
                        chunk.usage().ifPresent(streamUsage::set);
                        chunk.choices().forEach(choice -> choice.delta().content().ifPresent(text ->
                                javax.swing.SwingUtilities.invokeLater(() -> tokenConsumer.accept(text))));
                    });
                }
                if (usageStats != null && streamUsage.get() != null) {
                    usageStats.record("openrouter:" + modelToUse,
                            streamUsage.get().promptTokens(), streamUsage.get().completionTokens());
                }
                javax.swing.SwingUtilities.invokeLater(onComplete);
            } catch (Exception e) {
                log.error("Error in OpenRouter streaming response: {}", e.getClass().getSimpleName());
                javax.swing.SwingUtilities.invokeLater(() -> onError.accept(e));
            }
        }, "openrouter-chat-stream");
        streamThread.setDaemon(true);
        streamThread.start();
        return () -> {
            log.info("Cancelling OpenRouter stream");
            if (streamThread.isAlive()) {
                streamThread.interrupt();
            }
        };
    }

    /** Lists cached OpenRouter models that advertise tool support. */
    public List<String> listModels() {
        if (openAiClient == null) {
            return List.of();
        }
        List<String> models = cachedModels;
        if (models != null) {
            return models;
        }
        synchronized (this) {
            if (cachedModels == null) {
                ModelCatalog catalog = fetchModels();
                if (catalog.successful()) {
                    cachedModels = List.copyOf(catalog.models());
                } else {
                    return catalog.models();
                }
            }
            return cachedModels;
        }
    }

    private ModelCatalog fetchModels() {
        List<String> fallback = fallbackModels();
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(
                            URI.create(chatBaseUrl(baseUrl) + "/models?supported_parameters=tools"))
                    .timeout(MODEL_LIST_TIMEOUT)
                    .header(REFERER_HEADER, REFERER_VALUE)
                    .header(TITLE_HEADER, TITLE_VALUE)
                    .GET();
            if (apiKey != null) {
                request.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> response =
                    modelListClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.info("OpenRouter model listing returned HTTP {}; using configured fallback models",
                        response.statusCode());
                return new ModelCatalog(fallback, false);
            }
            JsonNode data = JSON.readTree(response.body()).path("data");
            if (!data.isArray()) {
                throw new IOException("Invalid OpenRouter model-list response");
            }
            List<String> available = new ArrayList<>();
            for (JsonNode item : data) {
                String id = item.path("id").asText("").trim();
                if (!id.isEmpty() && !id.toLowerCase(Locale.ROOT).endsWith(":batch")) {
                    available.add(id);
                }
            }
            return new ModelCatalog(orderModels(available), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("OpenRouter model listing was interrupted; using configured fallback models");
            return new ModelCatalog(fallback, false);
        } catch (Exception e) {
            log.debug("OpenRouter model listing failed ({}); using configured fallback models",
                    e.getClass().getSimpleName());
            return new ModelCatalog(fallback, false);
        }
    }

    private static boolean supportsCustomTemperature(String modelId) {
        String openAiPrefix = "openai/";
        String temperatureModelId = modelId != null && modelId.startsWith(openAiPrefix)
                ? modelId.substring(openAiPrefix.length()) : modelId;
        return OpenAiService.supportsCustomTemperature(temperatureModelId);
    }

    private List<String> orderModels(List<String> available) {
        Set<String> availableSet = new LinkedHashSet<>(available);
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (String featured : featuredModels) {
            if (availableSet.contains(featured)) {
                ordered.add(featured);
            }
        }
        if (availableSet.contains(model)) {
            ordered.add(model);
        }
        for (String id : available) {
            ordered.add(id);
        }
        return List.copyOf(ordered);
    }

    private List<String> fallbackModels() {
        LinkedHashSet<String> models = new LinkedHashSet<>(featuredModels);
        models.add(model);
        return List.copyOf(models);
    }

    private static String trimTrailingSlashes(String value) {
        return value.replaceAll("/+$", "");
    }

    private String safeErrorMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return apiKey == null ? message : message.replace(apiKey, "[REDACTED]");
    }

    private void appendConversation(ChatCompletionCreateParams.Builder builder, List<String> history) {
        if (history.isEmpty()) {
            builder.addUserMessage("Hello, how can you help me with JMeter?");
            return;
        }
        for (int i = 0; i < history.size(); i++) {
            String message = history.get(i);
            if (message == null || message.isEmpty()) {
                continue;
            }
            if (i % 2 == 0) {
                builder.addUserMessage(message);
            } else {
                builder.addAssistantMessage(message);
            }
        }
    }

    private List<String> buildLimitedHistory(List<String> conversation) {
        if (conversation == null || conversation.isEmpty()) {
            return new ArrayList<>();
        }
        if (conversation.size() > maxHistorySize) {
            return HistoryWindow.lastTurns(conversation, maxHistorySize);
        }
        return new ArrayList<>(conversation);
    }

    private List<String> filterErrorMessages(List<String> messages) {
        List<String> clean = new ArrayList<>();
        for (String message : HistoryWindow.maskFailedReplies(messages)) {
            if (message != null) {
                clean.add(message);
            }
        }
        return clean;
    }
}
