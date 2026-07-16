package org.qainsights.jmeter.ai.service;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.models.Model;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.qainsights.jmeter.ai.utils.Constants;
import org.qainsights.jmeter.ai.utils.ModelUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * AI service implementation for Meta Muse Spark model.
 * Uses the OpenAI-compatible endpoint at https://api.meta.ai/v1.
 */
public class MetaMuseAiService implements AiService {

    private static final Logger log = LoggerFactory.getLogger(MetaMuseAiService.class);

    private final OpenAIClient client;
    private final String baseUrl;
    private final int maxHistorySize;
    private final String systemPrompt;
    private String model;
    private final float temperature;
    private final long maxTokens;

    public MetaMuseAiService() {
        String apiKey = AiConfig.getProperty("meta.api.key", "");
        this.baseUrl = AiConfig.getProperty("meta.base.url", "https://api.meta.ai/v1");
        this.model = AiConfig.getProperty("meta.default.model", "muse-spark-1.1");
        this.temperature = ModelUtils.parseTemperature(
                AiConfig.getProperty("meta.temperature", "0.7"));
        this.maxHistorySize = Integer.parseInt(
                AiConfig.getProperty("meta.max.history.size", "10"));
        this.maxTokens = Long.parseLong(
                AiConfig.getProperty("meta.max.tokens", "4096"));

        String configuredPrompt = AiConfig.getProperty("meta.system.prompt", "");
        this.systemPrompt = (configuredPrompt != null && !configuredPrompt.isEmpty())
                ? configuredPrompt : Constants.DEFAULT_JMETER_SYSTEM_PROMPT;

        if (apiKey != null && !apiKey.isEmpty() && !"YOUR_META_API_KEY".equals(apiKey)) {
            this.client = OpenAIOkHttpClient.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .build();
        } else {
            this.client = null;
        }

        log.info("Initialized Meta Muse service with baseUrl: {}, model: {}, temperature: {}",
                this.baseUrl, this.model, this.temperature);
    }

    /** Package-private constructor for testing. */
    MetaMuseAiService(OpenAIClient client, String baseUrl, String model,
                      float temperature, int maxHistorySize, long maxTokens,
                      String systemPrompt) {
        this.client = client;
        this.baseUrl = baseUrl;
        this.model = model;
        this.temperature = temperature;
        this.maxHistorySize = maxHistorySize;
        this.maxTokens = maxTokens;
        this.systemPrompt = systemPrompt;
    }

    public OpenAIClient getClient() {
        return client;
    }

    public void setModel(String modelId) {
        this.model = modelId;
        log.info("Meta Muse model set to: {}", modelId);
    }

    public String getCurrentModel() {
        return model;
    }

    @Override
    public String getName() {
        return "Meta Muse";
    }

    @Override
    public String generateResponse(List<String> conversation) {
        return generateResponse(conversation, this.model);
    }

    @Override
    public String generateResponse(List<String> conversation, String model) {
        if (client == null) {
            return "Error: Meta Muse client not initialized. Set meta.api.key in jmeter.properties.";
        }
        try {
            ChatCompletionCreateParams params = buildParams(conversation, model);
            ChatCompletion completion = client.chat().completions().create(params);
            return completion.choices().get(0).message().content().orElse("No content available");
        } catch (Exception e) {
            log.error("Error generating response from Meta Muse", e);
            return "Error: " + e.getMessage();
        }
    }

    @Override
    public Runnable generateStreamResponse(List<String> conversation, String model,
                                           Consumer<String> tokenConsumer,
                                           Runnable onComplete,
                                           Consumer<Exception> onError) {
        if (client == null) {
            return () -> {};
        }
        ChatCompletionCreateParams params = buildParams(conversation, model);
        Thread streamThread = new Thread(() -> {
            try {
                try (com.openai.core.http.StreamResponse<ChatCompletionChunk> stream =
                             client.chat().completions().createStreaming(params)) {
                    stream.stream()
                            .flatMap(chunk -> chunk.choices().stream())
                            .flatMap(choice -> choice.delta().content().stream())
                            .forEach(text -> javax.swing.SwingUtilities.invokeLater(() -> tokenConsumer.accept(text)));
                }
                javax.swing.SwingUtilities.invokeLater(onComplete);
            } catch (Exception e) {
                log.error("Error in Meta Muse streaming response", e);
                javax.swing.SwingUtilities.invokeLater(() -> onError.accept(e));
            }
        });
        streamThread.setDaemon(true);
        streamThread.start();

        return () -> {
            log.info("Cancelling Meta Muse stream");
            if (streamThread.isAlive()) {
                streamThread.interrupt();
            }
        };
    }

    private ChatCompletionCreateParams buildParams(List<String> conversation, String targetModel) {
        String modelToUse = (targetModel != null && !targetModel.isEmpty()) ? targetModel : this.model;
        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                .maxCompletionTokens(maxTokens)
                .model(modelToUse)
                .temperature((double) temperature)
                .addSystemMessage(systemPrompt);

        List<String> history = filterErrorMessages(buildLimitedHistory(conversation));
        if (history.isEmpty()) {
            builder.addUserMessage("Hello, how can you help me with JMeter?");
        } else {
            appendConversation(builder, history);
        }
        return builder.build();
    }

    private void appendConversation(ChatCompletionCreateParams.Builder builder, List<String> history) {
        for (int i = 0; i < history.size(); i++) {
            String msg = history.get(i);
            if (msg != null && !msg.isEmpty()) {
                if (i % 2 == 0) {
                    builder.addUserMessage(msg);
                } else {
                    builder.addAssistantMessage(msg);
                }
            }
        }
    }

    private List<String> buildLimitedHistory(List<String> conversation) {
        if (conversation == null || conversation.isEmpty()) {
            return new ArrayList<>();
        }
        if (conversation.size() > maxHistorySize) {
            return conversation.subList(conversation.size() - maxHistorySize, conversation.size());
        }
        return new ArrayList<>(conversation);
    }

    private List<String> filterErrorMessages(List<String> messages) {
        List<String> clean = new ArrayList<>();
        for (String msg : messages) {
            if (msg != null && !msg.startsWith("Error:")) {
                clean.add(msg);
            }
        }
        return clean;
    }

    public List<String> listModels() {
        List<String> models = new ArrayList<>();
        if (client != null) {
            try {
                com.openai.models.models.ModelListPage page = client.models().list();
                if (page != null && page.data() != null) {
                    models = page.data().stream()
                            .map(Model::id)
                            .filter(MetaMuseAiService::isChatModel)
                            .collect(Collectors.toList());
                }
                log.info("Retrieved {} Meta Muse models", models.size());
            } catch (Exception e) {
                log.error("Error listing Meta Muse models: {}", e.getMessage(), e);
            }
        }
        if (models.isEmpty()) {
            models.add("muse-spark-1.1");
        }
        return models;
    }

    private static boolean isChatModel(String modelId) {
        String lower = modelId.toLowerCase();
        return (lower.contains("muse") || lower.contains("spark"))
                && !lower.contains("embedding")
                && !lower.contains("image");
    }
}
