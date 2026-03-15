package org.qainsights.jmeter.ai.service;

import java.util.ArrayList;
import java.util.List;

import org.qainsights.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.ollama4j.Ollama;
import io.github.ollama4j.models.response.Model;
import io.github.ollama4j.models.chat.OllamaChatRequest;
import io.github.ollama4j.models.chat.OllamaChatMessageRole;
import io.github.ollama4j.models.chat.OllamaChatResult;

public class OllamaAiService implements AiService {

    private static final Logger logger = LoggerFactory.getLogger(OllamaAiService.class);
    private final Ollama ollamaClient;
    private String model;
    private final String host;
    private final float temperature;
    private final int maxHistorySize;

    private static final String DEFAULT_JMETER_SYSTEM_PROMPT = "You are a JMeter expert. Your task is to analyze the user's request and generate the appropriate JMeter test plan. Please provide the response in the correct format.";

    public OllamaAiService() {
        String hostValue = AiConfig.getProperty("ollama.host", "http://localhost");
        String portValue = AiConfig.getProperty("ollama.port", "11434");
        
        if (hostValue != null && !hostValue.isEmpty() && !portValue.isEmpty() && !hostValue.matches(".*:\\d+/?$")) {
            hostValue = hostValue.endsWith("/") ? hostValue.substring(0, hostValue.length() - 1) : hostValue;
            this.host = hostValue + ":" + portValue;
        } else {
            this.host = hostValue != null && !hostValue.isEmpty() ? hostValue : "http://localhost:11434";
        }
        
        this.model = AiConfig.getProperty("ollama.default.model", "deepseek-r1:1.5b");
        this.temperature = Float.parseFloat(AiConfig.getProperty("ollama.temperature", "0.5"));
        this.maxHistorySize = Integer.parseInt(AiConfig.getProperty("ollama.max.history.size", "10"));
        this.ollamaClient = new Ollama(this.host);
        
        logger.info("Initialized Ollama service with host: {}, model: {}", this.host, this.model);
    }

    public boolean isReachable() {
        try {
            return this.ollamaClient.ping();
        } catch (Exception e) {
            logger.error("Ollama is not reachable at " + this.host);
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
            logger.error("Error listing models", e);
            return new ArrayList<>();
        }
    }

    @Override
    public String getName() {
        return "Ollama";
    }

    @Override
    public String generateResponse(List<String> messages) {
        return generateResponse(messages, DEFAULT_JMETER_SYSTEM_PROMPT);
    }

    public void setModel(String modelId) {
        this.model = modelId;
        logger.info("Ollama Model set to: {}", modelId);
    }

    @Override
    public String generateResponse(List<String> messages, String systemPrompt) {
        if (!isValidModel(this.model)) {
            logger.warn("Configured model '{}' is not available. Using default or failing.", this.model);
            // Optionally, we could attempt to pull the model, but for now we complain.
        }

        try {
            OllamaChatRequest request = OllamaChatRequest.builder()
                .withModel(this.model)
                .build();

            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                request.withMessage(OllamaChatMessageRole.SYSTEM, systemPrompt);
            } else {
                request.withMessage(OllamaChatMessageRole.SYSTEM, DEFAULT_JMETER_SYSTEM_PROMPT);
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

            OllamaChatResult result = ollamaClient.chat(request, null);
            return result.getResponseModel().getMessage().getResponse();
            
        } catch (Exception e) {
            logger.error("Error generating response from Ollama", e);
            return "Error generating response: " + e.getMessage();
        }
    }
}
