package org.qainsights.jmeter.ai.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.TextDelta;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.qainsights.jmeter.ai.utils.Constants;
import org.qainsights.jmeter.ai.utils.ModelUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class DeepseekAiService implements AiService {

    private static final Logger log = LoggerFactory.getLogger(DeepseekAiService.class);

    private final OpenAIClient openAiClient;
    private final AnthropicClient anthropicClient;
    private final boolean isAnthropicFormat;
    private final String baseUrl;
    private final int maxHistorySize;
    private final String systemPrompt;
    private String model;
    private float temperature;
    private long maxTokens;

    public DeepseekAiService() {
        String apiKey = AiConfig.getProperty("deepseek.api.key", "");
        this.baseUrl = AiConfig.getProperty("deepseek.base.url", "https://api.deepseek.com");
        String format = AiConfig.getProperty("deepseek.api.format", "openai");
        this.isAnthropicFormat = "anthropic".equalsIgnoreCase(format);

        if (isAnthropicFormat) {
            this.anthropicClient = AnthropicOkHttpClient.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .build();
            this.openAiClient = null;
        } else {
            this.openAiClient = OpenAIOkHttpClient.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .build();
            this.anthropicClient = null;
        }

        this.model = AiConfig.getProperty("deepseek.default.model", "deepseek-chat");
        this.temperature = parseTemperature(AiConfig.getProperty("deepseek.temperature", "0.7"));
        this.maxHistorySize = Integer.parseInt(AiConfig.getProperty("deepseek.max.history.size", "10"));
        this.maxTokens = Long.parseLong(AiConfig.getProperty("deepseek.max.tokens", "4096"));

        String configuredPrompt = AiConfig.getProperty("deepseek.system.prompt", "");
        this.systemPrompt = (configuredPrompt != null && !configuredPrompt.isEmpty())
                ? configuredPrompt : Constants.DEFAULT_JMETER_SYSTEM_PROMPT;

        log.info("Initialized DeepSeek service with format: {}, baseUrl: {}, model: {}, temperature: {}",
                format, this.baseUrl, this.model, this.temperature);
    }

    DeepseekAiService(OpenAIClient openAiClient, AnthropicClient anthropicClient,
                      boolean isAnthropicFormat, String baseUrl, String model, float temperature,
                      int maxHistorySize, long maxTokens, String systemPrompt) {
        this.openAiClient = openAiClient;
        this.anthropicClient = anthropicClient;
        this.isAnthropicFormat = isAnthropicFormat;
        this.baseUrl = baseUrl;
        this.model = model;
        this.temperature = temperature;
        this.maxHistorySize = maxHistorySize;
        this.maxTokens = maxTokens;
        this.systemPrompt = systemPrompt;
    }

    private static float parseTemperature(String value) {
        return ModelUtils.parseTemperature(value);
    }

    public boolean isAnthropicFormat() {
        return isAnthropicFormat;
    }

    public OpenAIClient getClient() {
        return openAiClient;
    }

    public AnthropicClient getAnthropicClient() {
        return anthropicClient;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setModel(String modelId) {
        this.model = modelId;
        log.info("DeepSeek model set to: {}", modelId);
    }

    public String getCurrentModel() {
        return model;
    }

    @Override
    public String getName() {
        return "DeepSeek";
    }

    @Override
    public String generateResponse(List<String> conversation) {
        return generateResponse(conversation, this.model);
    }

    @Override
    public String generateResponse(List<String> conversation, String model) {
        if (isAnthropicFormat) {
            return generateAnthropicResponse(conversation, model);
        } else {
            return generateOpenAiResponse(conversation, model);
        }
    }

    private String generateOpenAiResponse(List<String> conversation, String model) {
        if (openAiClient == null) {
            return "Error: OpenAI client not initialized";
        }
        try {
            String modelToUse = (model != null && !model.isEmpty()) ? model : this.model;

            ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                    .maxCompletionTokens(maxTokens)
                    .model(modelToUse)
                    .temperature((double) temperature);

            paramsBuilder.addSystemMessage(systemPrompt);

            List<String> limitedHistory = buildLimitedHistory(conversation);
            List<String> cleanHistory = filterErrorMessages(limitedHistory);

            if (cleanHistory.isEmpty()) {
                paramsBuilder.addUserMessage("Hello, how can you help me with JMeter?");
            } else {
                for (int i = 0; i < cleanHistory.size(); i++) {
                    String msg = cleanHistory.get(i);
                    if (msg == null || msg.isEmpty()) {
                        continue;
                    }
                    if (i % 2 == 0) {
                        paramsBuilder.addUserMessage(msg);
                    } else {
                        paramsBuilder.addAssistantMessage(msg);
                    }
                }
            }

            ChatCompletionCreateParams params = paramsBuilder.build();
            ChatCompletion chatCompletion = openAiClient.chat().completions().create(params);

            ChatCompletion.Choice choice = chatCompletion.choices().get(0);
            return choice.message().content().orElse("No content available");

        } catch (Exception e) {
            log.error("Error generating response from DeepSeek (OpenAI format)", e);
            return "Error: " + e.getMessage();
        }
    }

    private String generateAnthropicResponse(List<String> conversation, String model) {
        if (anthropicClient == null) {
            return "Error: Anthropic client not initialized";
        }
        try {
            String modelToUse = (model != null && !model.isEmpty()) ? model : this.model;

            MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                    .maxTokens(maxTokens)
                    .model(modelToUse)
                    .temperature(temperature);

            paramsBuilder.system(systemPrompt);

            List<String> limitedHistory = buildLimitedHistory(conversation);
            List<String> cleanHistory = filterErrorMessages(limitedHistory);

            if (cleanHistory.isEmpty()) {
                paramsBuilder.addUserMessage("Hello, how can you help me with JMeter?");
            } else {
                for (int i = 0; i < cleanHistory.size(); i++) {
                    String msg = cleanHistory.get(i);
                    if (msg == null || msg.isEmpty()) {
                        continue;
                    }
                    if (i % 2 == 0) {
                        paramsBuilder.addUserMessage(msg);
                    } else {
                        paramsBuilder.addAssistantMessage(msg);
                    }
                }
            }

            MessageCreateParams params = paramsBuilder.build();
            Message message = anthropicClient.messages().create(params);

            StringBuilder sb = new StringBuilder();
            for (com.anthropic.models.messages.ContentBlock block : message.content()) {
                if (block.text().isPresent()) {
                    sb.append(block.text().get());
                }
            }
            return sb.toString();

        } catch (Exception e) {
            log.error("Error generating response from DeepSeek (Anthropic format)", e);
            return "Error: " + e.getMessage();
        }
    }

    @Override
    public Runnable generateStreamResponse(List<String> conversation, String model, Consumer<String> tokenConsumer, Runnable onComplete, Consumer<Exception> onError) {
        if (isAnthropicFormat) {
            return generateAnthropicStreamResponse(conversation, model, tokenConsumer, onComplete, onError);
        } else {
            return generateOpenAiStreamResponse(conversation, model, tokenConsumer, onComplete, onError);
        }
    }

    private Runnable generateOpenAiStreamResponse(List<String> conversation, String model, Consumer<String> tokenConsumer, Runnable onComplete, Consumer<Exception> onError) {
        if (openAiClient == null) {
            return () -> {
            };
        }
        String modelToUse = (model != null && !model.isEmpty()) ? model : this.model;

        ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                .maxCompletionTokens(maxTokens)
                .model(modelToUse)
                .temperature((double) temperature);

        paramsBuilder.addSystemMessage(systemPrompt);

        List<String> limitedHistory = buildLimitedHistory(conversation);
        List<String> cleanHistory = filterErrorMessages(limitedHistory);

        if (cleanHistory.isEmpty()) {
            paramsBuilder.addUserMessage("Hello, how can you help me with JMeter?");
        } else {
            for (int i = 0; i < cleanHistory.size(); i++) {
                String msg = cleanHistory.get(i);
                if (msg == null || msg.isEmpty()) {
                    continue;
                }
                if (i % 2 == 0) {
                    paramsBuilder.addUserMessage(msg);
                } else {
                    paramsBuilder.addAssistantMessage(msg);
                }
            }
        }

        ChatCompletionCreateParams params = paramsBuilder.build();

        Thread streamThread = new Thread(() -> {
            try {
                try (com.openai.core.http.StreamResponse<ChatCompletionChunk> stream = openAiClient.chat().completions().createStreaming(params)) {
                    stream.stream()
                            .flatMap(chunk -> chunk.choices().stream())
                            .flatMap(choice -> choice.delta().content().stream())
                            .forEach(text -> {
                                javax.swing.SwingUtilities.invokeLater(() -> tokenConsumer.accept(text));
                            });
                }

                javax.swing.SwingUtilities.invokeLater(onComplete);
            } catch (Exception e) {
                log.error("Error in DeepSeek streaming response (OpenAI format)", e);
                javax.swing.SwingUtilities.invokeLater(() -> onError.accept(e));
            }
        });
        streamThread.setDaemon(true);
        streamThread.start();

        return () -> {
            log.info("Cancelling DeepSeek stream");
            if (streamThread.isAlive()) {
                streamThread.interrupt();
            }
        };
    }

    private Runnable generateAnthropicStreamResponse(List<String> conversation, String model, Consumer<String> tokenConsumer, Runnable onComplete, Consumer<Exception> onError) {
        if (anthropicClient == null) {
            return () -> {
            };
        }
        String modelToUse = (model != null && !model.isEmpty()) ? model : this.model;

        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                .maxTokens(maxTokens)
                .model(modelToUse)
                .temperature(temperature);

        paramsBuilder.system(systemPrompt);

        List<String> limitedHistory = buildLimitedHistory(conversation);
        List<String> cleanHistory = filterErrorMessages(limitedHistory);

        if (cleanHistory.isEmpty()) {
            paramsBuilder.addUserMessage("Hello, how can you help me with JMeter?");
        } else {
            for (int i = 0; i < cleanHistory.size(); i++) {
                String msg = cleanHistory.get(i);
                if (msg == null || msg.isEmpty()) {
                    continue;
                }
                if (i % 2 == 0) {
                    paramsBuilder.addUserMessage(msg);
                } else {
                    paramsBuilder.addAssistantMessage(msg);
                }
            }
        }

        MessageCreateParams params = paramsBuilder.build();

        Thread streamThread = new Thread(() -> {
            try {
                try (StreamResponse<RawMessageStreamEvent> stream = anthropicClient.messages().createStreaming(params)) {
                    stream.stream()
                            .flatMap(event -> event.contentBlockDelta().stream())
                            .flatMap(delta -> delta.delta().text().stream())
                            .map(TextDelta::text)
                            .forEach(text -> {
                                javax.swing.SwingUtilities.invokeLater(() -> tokenConsumer.accept(text));
                            });
                }

                javax.swing.SwingUtilities.invokeLater(onComplete);
            } catch (Exception e) {
                log.error("Error in DeepSeek streaming response (Anthropic format)", e);
                javax.swing.SwingUtilities.invokeLater(() -> onError.accept(e));
            }
        });
        streamThread.setDaemon(true);
        streamThread.start();

        return () -> {
            log.info("Cancelling DeepSeek stream");
            if (streamThread.isAlive()) {
                streamThread.interrupt();
            }
        };
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
}
