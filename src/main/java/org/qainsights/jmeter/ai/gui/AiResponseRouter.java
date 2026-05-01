package org.qainsights.jmeter.ai.gui;

import org.qainsights.jmeter.ai.service.AiService;
import org.qainsights.jmeter.ai.service.ClaudeService;
import org.qainsights.jmeter.ai.service.OllamaAiService;
import org.qainsights.jmeter.ai.service.OpenAiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/**
 * Routes AI generation requests to the appropriate service based on the
 * selected model ID prefix and manages service selection logic.
 */
public class AiResponseRouter {
    private static final Logger log = LoggerFactory.getLogger(AiResponseRouter.class);

    private final ClaudeService claudeService;
    private final OpenAiService openAiService;
    private final OllamaAiService ollamaService;

    public AiResponseRouter(ClaudeService claudeService, OpenAiService openAiService, OllamaAiService ollamaService) {
        this.claudeService = claudeService;
        this.openAiService = openAiService;
        this.ollamaService = ollamaService;
    }

    /**
     * Generates an AI response for the given conversation history using the
     * service corresponding to the selected model ID.
     *
     * @param selectedModel      the model ID from the selector (may be null)
     * @param conversationHistory the current conversation history
     * @return the AI-generated response string
     */
    public String getAiResponse(String selectedModel, List<String> conversationHistory) {
        if (selectedModel == null) {
            log.warn("No model selected, using default Anthropic model: {}", claudeService.getCurrentModel());
            return claudeService.generateResponse(conversationHistory);
        }

        log.info("Using model from dropdown: {}", selectedModel);

        if (selectedModel.startsWith("openai:")) {
            String openAiModelId = selectedModel.substring(7);
            log.info("Using OpenAI model: {}", openAiModelId);
            openAiService.setModel(openAiModelId);
            return openAiService.generateResponse(conversationHistory);
        } else if (selectedModel.startsWith("ollama:")) {
            String ollamaModelId = selectedModel.substring(7);
            log.info("Using Ollama model: {}", ollamaModelId);
            ollamaService.setModel(ollamaModelId);
            return ollamaService.generateResponse(conversationHistory);
        } else {
            log.info("Using Anthropic model: {}", selectedModel);
            claudeService.setModel(selectedModel);
            return claudeService.generateResponse(conversationHistory);
        }
    }

    /**
     * Generates a streaming AI response using the service corresponding to the selected model ID.
     *
     * @param selectedModel       the model ID from the selector
     * @param conversationHistory the current conversation history
     * @param tokenConsumer       callback for each token chunk
     * @param onComplete          callback for stream completion
     * @param onError             callback for stream error
     * @return a cancel handle as a Runnable
     */
    public Runnable generateStreamResponse(String selectedModel, List<String> conversationHistory, Consumer<String> tokenConsumer, Runnable onComplete, Consumer<Exception> onError) {
        if (selectedModel == null) {
            log.warn("No model selected, using default Anthropic model: {}", claudeService.getCurrentModel());
            return claudeService.generateStreamResponse(conversationHistory, claudeService.getCurrentModel(), tokenConsumer, onComplete, onError);
        }

        log.info("Using model from dropdown for stream: {}", selectedModel);
        if (selectedModel.startsWith("openai:")) {
            String openAiModelId = selectedModel.substring(7);
            return openAiService.generateStreamResponse(conversationHistory, openAiModelId, tokenConsumer, onComplete, onError);
        } else if (selectedModel.startsWith("ollama:")) {
            // Fallback for Ollama if streaming not implemented
            String ollamaModelId = selectedModel.substring(7);
            return ollamaService.generateStreamResponse(conversationHistory, ollamaModelId, tokenConsumer, onComplete, onError);
        } else {
            // Anthropic
            return claudeService.generateStreamResponse(conversationHistory, selectedModel, tokenConsumer, onComplete, onError);
        }
    }

    /**
     * Resolves the appropriate {@link AiService} based on the selected model ID prefix.
     *
     * @param selectedModel the model ID string from the model selector
     * @return the matching AiService
     */
    public AiService resolveAiService(String selectedModel) {
        if (selectedModel.startsWith("openai:")) {
            return openAiService;
        } else if (selectedModel.startsWith("ollama:")) {
            return ollamaService;
        } else {
            return claudeService;
        }
    }
}
