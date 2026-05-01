package org.qainsights.jmeter.ai.service;

import io.github.ollama4j.Ollama;
import io.github.ollama4j.models.chat.OllamaChatMessageRole;
import io.github.ollama4j.models.chat.OllamaChatRequest;
import io.github.ollama4j.models.chat.OllamaChatResult;
import io.github.ollama4j.models.chat.OllamaChatStreamObserver;
import io.github.ollama4j.models.request.ThinkMode;
import io.github.ollama4j.models.response.Model;
import io.github.ollama4j.utils.OptionsBuilder;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.qainsights.jmeter.ai.utils.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

// Ollama Help https://ollama4j.github.io/ollama4j/intro
public class OllamaAiService implements AiService {

    private static final Logger logger = LoggerFactory.getLogger(OllamaAiService.class);
    private final Ollama ollamaClient;
    private String model;
    private final String host;
    private float temperature;
    private final int maxHistorySize;
    private final boolean isThinkingModeEnabled;
    private final ThinkMode thinkingMode;
    private final long requestTimeoutSeconds;
    private final String systemPrompt;


    public OllamaAiService() {
        this.host = buildHost(
                AiConfig.getProperty("ollama.host", "http://localhost"),
                AiConfig.getProperty("ollama.port", "11434"));

        this.model = AiConfig.getProperty("ollama.default.model", "deepseek-r1:1.5b");
        this.temperature = parseTemperature(AiConfig.getProperty("ollama.temperature", "0.5"));
        this.maxHistorySize = Integer.parseInt(AiConfig.getProperty("ollama.max.history.size", "10"));
        this.isThinkingModeEnabled = AiConfig.getProperty("ollama.thinking.mode", "DISABLED").equalsIgnoreCase("enabled");
        this.thinkingMode = parseThinkingMode(AiConfig.getProperty("ollama.thinking.level", "MEDIUM"));
        this.requestTimeoutSeconds = parseTimeout(AiConfig.getProperty("ollama.request.timeout.seconds", "120"));
        this.ollamaClient = new Ollama(this.host);
        this.ollamaClient.setRequestTimeoutSeconds(this.requestTimeoutSeconds);
        String configuredPrompt = AiConfig.getProperty("ollama.system.prompt", "");
        this.systemPrompt = (configuredPrompt != null && !configuredPrompt.isEmpty())
                ? configuredPrompt : Constants.DEFAULT_JMETER_SYSTEM_PROMPT;

        logger.info("Initialized Ollama service with host: {}, model: {}, thinking mode: {}, timeout: {}s",
                this.host, this.model, this.isThinkingModeEnabled ? this.thinkingMode : "DISABLED", this.requestTimeoutSeconds);
    }

    private static String buildHost(String hostValue, String portValue) {
        if (hostValue == null || hostValue.isEmpty()) {
            return "http://localhost:11434";
        }
        if (!portValue.isEmpty() && !hostValue.matches(".*:\\d+/?$")) {
            hostValue = hostValue.endsWith("/") ? hostValue.substring(0, hostValue.length() - 1) : hostValue;
            return hostValue + ":" + portValue;
        }
        return hostValue;
    }

    private static float parseTemperature(String value) {
        try {
            float temp = Float.parseFloat(value);
            if (temp < 0 || temp >= 1) {
                logger.warn("Temperature must be between 0 and 1. Provided value: {}. Setting to default 0.5", temp);
                return 0.5f;
            }
            return temp;
        } catch (NumberFormatException e) {
            logger.warn("Invalid temperature value: '{}'. Setting to default 0.5", value);
            return 0.5f;
        }
    }

    private static ThinkMode parseThinkingMode(String value) {
        try {
            return ThinkMode.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid thinking level: '{}'. Setting to default MEDIUM", value);
            return ThinkMode.MEDIUM;
        }
    }

    private static long parseTimeout(String value) {
        try {
            long timeout = Long.parseLong(value);
            if (timeout <= 0) {
                logger.warn("Request timeout must be positive. Provided value: {}. Setting to default 120s", timeout);
                return 120L;
            }
            return timeout;
        } catch (NumberFormatException e) {
            logger.warn("Invalid request timeout value: '{}'. Setting to default 120s", value);
            return 120L;
        }
    }

    public boolean isReachable() {
        try {
            return this.ollamaClient.ping();
        } catch (Exception e) {
            logger.error("Ollama is not reachable at {}", this.host);
            return false;
        }
    }

    public boolean isValidModel(String configuredModel) {
        if (configuredModel == null || configuredModel.isEmpty()) {
            return false;
        }
        try {
            List<Model> models = this.ollamaClient.listModels();
            for (Model m : models) {
                if (m.getName().equals(configuredModel)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            logger.error("Model is not valid", e);
            return false;
        }
    }

    public List<Model> listModels() {
        try {
            return this.ollamaClient.listModels();
        } catch (Exception e) {
            logger.warn("Ollama is not available ({}). Skipping Ollama model discovery.", e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public String getName() {
        return "Ollama";
    }

    @Override
    public String generateResponse(List<String> messages) {
        return generateResponse(messages, this.systemPrompt);
    }

    public void setModel(String modelId) {
        this.model = modelId;
        logger.info("Ollama Model set to: {}", modelId);
    }


    public boolean isThinkingModeValid() {
        return this.thinkingMode == ThinkMode.LOW || this.thinkingMode == ThinkMode.MEDIUM || this.thinkingMode == ThinkMode.HIGH;
    }

    @Override
    public String generateResponse(List<String> messages, String systemPrompt) {

        OllamaChatRequest request = OllamaChatRequest.builder();
        OllamaChatResult result = null;

        if (!isValidModel(this.model)) {
            logger.warn("Configured model '{}' is not available. Using default or failing.", this.model);
        }

        if (isThinkingModeEnabled && !isThinkingModeValid()) {
            logger.warn("Thinking mode is enabled but thinking level '{}' is not valid. Disabling thinking mode.", this.thinkingMode);
        }

        try {
            request = buildOllamaChatRequest(request);

            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                request.withMessage(OllamaChatMessageRole.SYSTEM, systemPrompt);
            } else {
                request.withMessage(OllamaChatMessageRole.SYSTEM, Constants.DEFAULT_JMETER_SYSTEM_PROMPT);
            }

            List<String> limitedHistory;
            if (messages.size() > maxHistorySize) {
                limitedHistory = messages.subList(messages.size() - maxHistorySize, messages.size());
            } else {
                limitedHistory = new ArrayList<>(messages);
            }

            for (int i = 0; i < limitedHistory.size(); i++) {
                String msg = limitedHistory.get(i);
                if (msg == null || msg.isEmpty()) continue;
                if (i % 2 == 0) {
                    request.withMessage(OllamaChatMessageRole.USER, msg);
                } else {
                    request.withMessage(OllamaChatMessageRole.ASSISTANT, msg);
                }
            }

            result = ollamaClient.chat(request, null);
            return result.getResponseModel().getMessage().getResponse();

        } catch (Exception e) {
            logger.error("Error generating response from Ollama", e);
            return "Error generating response: " + e.getMessage();
        }
    }

    @Override
    public Runnable generateStreamResponse(List<String> conversation, String model, Consumer<String> tokenConsumer, Runnable onComplete, Consumer<Exception> onError) {
        if (model != null && !model.isEmpty()) {
            this.model = model;
        }

        Thread streamThread = new Thread(() -> {
            try {
                OllamaChatRequest request = OllamaChatRequest.builder();
                request = buildOllamaChatRequest(request);

                if (systemPrompt != null && !systemPrompt.isEmpty()) {
                    request.withMessage(OllamaChatMessageRole.SYSTEM, systemPrompt);
                } else {
                    request.withMessage(OllamaChatMessageRole.SYSTEM, Constants.DEFAULT_JMETER_SYSTEM_PROMPT);
                }

                List<String> limitedHistory = conversation.size() > maxHistorySize
                        ? conversation.subList(conversation.size() - maxHistorySize, conversation.size())
                        : new ArrayList<>(conversation);

                for (int i = 0; i < limitedHistory.size(); i++) {
                    String msg = limitedHistory.get(i);
                    if (msg == null || msg.isEmpty()) continue;
                    if (i % 2 == 0) {
                        request.withMessage(OllamaChatMessageRole.USER, msg);
                    } else {
                        request.withMessage(OllamaChatMessageRole.ASSISTANT, msg);
                    }
                }

                OllamaChatStreamObserver observer = new OllamaChatStreamObserver();
                observer.setResponseStreamHandler(token -> {
                    if (!Thread.currentThread().isInterrupted()) {
                        tokenConsumer.accept(token);
                    }
                });

                ollamaClient.chat(request, observer);

                if (!Thread.currentThread().isInterrupted()) {
                    onComplete.run();
                }
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    logger.info("Ollama streaming cancelled");
                } else {
                    logger.error("Error generating streaming response from Ollama", e);
                    onError.accept(e);
                }
            }
        });

        streamThread.setDaemon(true);
        streamThread.start();
        return streamThread::interrupt;
    }

    private OllamaChatRequest buildOllamaChatRequest(OllamaChatRequest request) {

        if (isThinkingModeEnabled && isThinkingModeValid()) {
            return request.withThinking(this.thinkingMode)
                    .withOptions(new OptionsBuilder().setTemperature(this.temperature).build())
                    .withModel(this.model).build();
        } else {
            return request.withThinking(ThinkMode.DISABLED)
                    .withOptions(new OptionsBuilder().setTemperature(this.temperature).build())
                    .withModel(this.model).build();
        }
    }
}
