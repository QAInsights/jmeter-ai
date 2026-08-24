package org.qainsights.jmeter.ai.gui;

import org.qainsights.jmeter.ai.claudecode.ClaudeCodeCliProvider;
import org.qainsights.jmeter.ai.cli.CliProviderException;
import org.qainsights.jmeter.ai.codex.CodexCliProvider;
import org.qainsights.jmeter.ai.service.AiService;
import org.qainsights.jmeter.ai.service.AiServiceHolder;
import org.qainsights.jmeter.ai.service.ClaudeCodeAiService;
import org.qainsights.jmeter.ai.service.ClaudeService;
import org.qainsights.jmeter.ai.service.CliSubscriptionAiService;
import org.qainsights.jmeter.ai.service.CodexAiService;
import org.qainsights.jmeter.ai.service.OllamaAiService;
import org.qainsights.jmeter.ai.service.OpenAiService;
import org.qainsights.jmeter.ai.service.DeepseekAiService;
import org.qainsights.jmeter.ai.service.GoogleAiService;
import org.qainsights.jmeter.ai.service.GrokAiService;
import org.qainsights.jmeter.ai.service.MetaMuseAiService;
import org.qainsights.jmeter.ai.service.BedrockAiService;
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
    private final DeepseekAiService deepseekService;
    private final GoogleAiService googleService;
    private final GrokAiService grokService;
    private final MetaMuseAiService metaMuseService;
    private final BedrockAiService bedrockService;
    private final CodexAiService codexService;
    private final ClaudeCodeAiService claudeCodeService;
    private org.qainsights.jmeter.ai.service.attach.AttachmentRegistry attachmentRegistry;

    public AiResponseRouter(AiServiceHolder serviceHolder) {
        this.claudeService = serviceHolder.getClaudeService();
        this.openAiService = serviceHolder.getOpenAiService();
        this.ollamaService = serviceHolder.getOllamaService();
        this.deepseekService = serviceHolder.getDeepseekService();
        this.googleService = serviceHolder.getGoogleService();
        this.grokService = serviceHolder.getGrokService();
        this.metaMuseService = serviceHolder.getMetaMuseService();
        this.bedrockService = serviceHolder.getBedrockService();
        this.codexService = serviceHolder.getCodexService();
        this.claudeCodeService = serviceHolder.getClaudeCodeService();
    }

    /**
     * Generates an AI response for the given conversation history using the
     * service corresponding to the selected model ID.
     *
     * @param selectedModel      the model ID from the selector (may be null)
     * @param conversationHistory the current conversation history
     * @return the AI-generated response string
     */
    /** Registers the attachment registry used to resolve {@code [file:<id>]} markers. */
    public void setAttachmentRegistry(org.qainsights.jmeter.ai.service.attach.AttachmentRegistry registry) {
        this.attachmentRegistry = registry;
    }

    /** Substitutes attachment markers with their prepared content (no-op when no registry). */
    private List<String> resolveAttachments(List<String> conversation) {
        if (attachmentRegistry == null || conversation == null) {
            return conversation;
        }
        return conversation.stream()
                .map(attachmentRegistry::resolveInlineMarkers)
                .collect(java.util.stream.Collectors.toList());
    }

    public String getAiResponse(String selectedModel, List<String> conversationHistory) {
        conversationHistory = resolveAttachments(conversationHistory);
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
        } else if (selectedModel.startsWith("deepseek:")) {
            String deepseekModelId = selectedModel.substring(9);
            log.info("Using DeepSeek model: {}", deepseekModelId);
            deepseekService.setModel(deepseekModelId);
            return deepseekService.generateResponse(conversationHistory);
        } else if (selectedModel.startsWith("google:")) {
            String googleModelId = selectedModel.substring(7);
            log.info("Using Google Gemini model: {}", googleModelId);
            if (googleService != null) {
                googleService.setModel(googleModelId);
                return googleService.generateResponse(conversationHistory);
            }
            return notConfiguredMessage("Google Gemini", "google.api.key");
        } else if (selectedModel.startsWith("grok:")) {
            String grokModelId = selectedModel.substring(5);
            log.info("Using Grok model: {}", grokModelId);
            if (grokService != null) {
                grokService.setModel(grokModelId);
                return grokService.generateResponse(conversationHistory);
            }
            return notConfiguredMessage("Grok", "grok.api.key");
        } else if (selectedModel.startsWith("meta:")) {
            String metaModelId = selectedModel.substring(5);
            log.info("Using Meta Muse model: {}", metaModelId);
            if (metaMuseService != null) {
                metaMuseService.setModel(metaModelId);
                return metaMuseService.generateResponse(conversationHistory);
            }
            return notConfiguredMessage("Meta Muse", "meta.api.key");
        } else if (selectedModel.startsWith("bedrock:")) {
            String bedrockModelId = selectedModel.substring(8);
            log.info("Using Bedrock model: {}", bedrockModelId);
            if (bedrockService != null) {
                bedrockService.setModel(bedrockModelId);
                return bedrockService.generateResponse(conversationHistory);
            }
            return "Error: Bedrock service not configured. Set bedrock.api.key or "
                    + "bedrock.aws.access.key and bedrock.aws.secret.key in user.properties "
                    + "(or jmeter.properties) and restart JMeter.";
        } else if (selectedModel.startsWith(CodexCliProvider.MODEL_PREFIX)) {
            String codexModelId = selectedModel.substring(CodexCliProvider.MODEL_PREFIX.length());
            log.info("Using Codex CLI model: {}", codexModelId);
            return generateWithCli(codexService, codexModelId, conversationHistory, "ChatGPT / Codex");
        } else if (selectedModel.startsWith(ClaudeCodeCliProvider.MODEL_PREFIX)) {
            String claudeCodeModelId = selectedModel.substring(ClaudeCodeCliProvider.MODEL_PREFIX.length());
            log.info("Using Claude Code CLI model: {}", claudeCodeModelId);
            return generateWithCli(claudeCodeService, claudeCodeModelId, conversationHistory, "Claude Code");
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
        return generateStreamResponse(selectedModel, conversationHistory, tokenConsumer, token -> {}, onComplete, onError);
    }

    /**
     * Generates a streaming AI response with a separate channel for reasoning
     * (thinking) tokens, using the service corresponding to the selected model ID.
     *
     * @param selectedModel       the model ID from the selector
     * @param conversationHistory the current conversation history
     * @param tokenConsumer       callback for each answer token chunk
     * @param reasoningConsumer   callback for each reasoning token chunk
     * @param onComplete          callback for stream completion
     * @param onError             callback for stream error
     * @return a cancel handle as a Runnable
     */
    public Runnable generateStreamResponse(String selectedModel, List<String> conversationHistory, Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer, Runnable onComplete, Consumer<Exception> onError) {
        conversationHistory = resolveAttachments(conversationHistory);
        if (selectedModel == null) {
            log.warn("No model selected, using default Anthropic model: {}", claudeService.getCurrentModel());
            return claudeService.generateStreamResponse(conversationHistory, claudeService.getCurrentModel(), tokenConsumer, reasoningConsumer, onComplete, onError);
        }

        log.info("Using model from dropdown for stream: {}", selectedModel);
        if (selectedModel.startsWith("openai:")) {
            String openAiModelId = selectedModel.substring(7);
            return openAiService.generateStreamResponse(conversationHistory, openAiModelId, tokenConsumer, reasoningConsumer, onComplete, onError);
        } else if (selectedModel.startsWith("ollama:")) {
            String ollamaModelId = selectedModel.substring(7);
            return ollamaService.generateStreamResponse(conversationHistory, ollamaModelId, tokenConsumer, reasoningConsumer, onComplete, onError);
        } else if (selectedModel.startsWith("deepseek:")) {
            String deepseekModelId = selectedModel.substring(9);
            return deepseekService.generateStreamResponse(conversationHistory, deepseekModelId, tokenConsumer, reasoningConsumer, onComplete, onError);
        } else if (selectedModel.startsWith("google:")) {
            String googleModelId = selectedModel.substring(7);
            if (googleService != null) {
                return googleService.generateStreamResponse(conversationHistory, googleModelId, tokenConsumer, reasoningConsumer, onComplete, onError);
            }
            return () -> {};
        } else if (selectedModel.startsWith("grok:")) {
            String grokModelId = selectedModel.substring(5);
            if (grokService != null) {
                return grokService.generateStreamResponse(conversationHistory, grokModelId, tokenConsumer, reasoningConsumer, onComplete, onError);
            }
            return () -> {};
        } else if (selectedModel.startsWith("meta:")) {
            String metaModelId = selectedModel.substring(5);
            if (metaMuseService != null) {
                return metaMuseService.generateStreamResponse(conversationHistory, metaModelId, tokenConsumer, reasoningConsumer, onComplete, onError);
            }
            return () -> {};
        } else if (selectedModel.startsWith("bedrock:")) {
            String bedrockModelId = selectedModel.substring(8);
            if (bedrockService != null) {
                return bedrockService.generateStreamResponse(conversationHistory, bedrockModelId, tokenConsumer, reasoningConsumer, onComplete, onError);
            }
            return () -> {};
        } else if (selectedModel.startsWith(CodexCliProvider.MODEL_PREFIX)) {
            String codexModelId = selectedModel.substring(CodexCliProvider.MODEL_PREFIX.length());
            if (codexService != null) {
                return codexService.generateStreamResponse(conversationHistory, codexModelId, tokenConsumer, reasoningConsumer, onComplete, onError);
            }
            return () -> {};
        } else if (selectedModel.startsWith(ClaudeCodeCliProvider.MODEL_PREFIX)) {
            String claudeCodeModelId = selectedModel.substring(ClaudeCodeCliProvider.MODEL_PREFIX.length());
            if (claudeCodeService != null) {
                return claudeCodeService.generateStreamResponse(conversationHistory, claudeCodeModelId, tokenConsumer, reasoningConsumer, onComplete, onError);
            }
            return () -> {};
        } else {
            // Anthropic
            return claudeService.generateStreamResponse(conversationHistory, selectedModel, tokenConsumer, reasoningConsumer, onComplete, onError);
        }
    }

    /**
     * User-facing message when a provider service was never constructed (usually a
     * missing API key). Points at {@code user.properties} first, the usual place
     * testers edit, and mentions restart so the service can pick the key up.
     */
    /**
     * Runs a CLI-backed provider, turning its user-facing failures (CLI missing,
     * not signed in, timeout) into chat text instead of a stack trace.
     */
    private String generateWithCli(CliSubscriptionAiService service, String modelId,
                                   List<String> conversationHistory, String providerDisplayName) {
        if (service == null) {
            return "Error: " + providerDisplayName + " is not available in this session.";
        }
        try {
            return service.generateResponse(conversationHistory, modelId);
        } catch (CliProviderException e) {
            log.warn("{} request failed: {}", providerDisplayName, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    static String notConfiguredMessage(String providerDisplayName, String propertyKey) {
        return "Error: " + providerDisplayName + " service not configured. Set "
                + propertyKey + " in user.properties (or jmeter.properties) and restart JMeter.";
    }

    /**
     * Resolves the appropriate {@link AiService} based on the selected model ID prefix.
     *
     * @param selectedModel the model ID string from the model selector
     * @return the matching AiService
     */
    public AiService resolveAiService(String selectedModel) {
        if (selectedModel == null || selectedModel.isEmpty()) {
            return claudeService;
        }
        if (selectedModel.startsWith("openai:")) {
            String openAiModelId = selectedModel.substring(7);
            openAiService.setModel(openAiModelId);
            return openAiService;
        } else if (selectedModel.startsWith("ollama:")) {
            String ollamaModelId = selectedModel.substring(7);
            ollamaService.setModel(ollamaModelId);
            return ollamaService;
        } else if (selectedModel.startsWith("deepseek:")) {
            String deepseekModelId = selectedModel.substring(9);
            deepseekService.setModel(deepseekModelId);
            return deepseekService;
        } else if (selectedModel.startsWith("google:")) {
            String googleModelId = selectedModel.substring(7);
            if (googleService != null) {
                googleService.setModel(googleModelId);
            }
            return googleService;
        } else if (selectedModel.startsWith("grok:")) {
            String grokModelId = selectedModel.substring(5);
            if (grokService != null) {
                grokService.setModel(grokModelId);
            }
            return grokService;
        } else if (selectedModel.startsWith("meta:")) {
            String metaModelId = selectedModel.substring(5);
            if (metaMuseService != null) {
                metaMuseService.setModel(metaModelId);
            }
            return metaMuseService;
        } else if (selectedModel.startsWith("bedrock:")) {
            String bedrockModelId = selectedModel.substring(8);
            if (bedrockService != null) {
                bedrockService.setModel(bedrockModelId);
            }
            return bedrockService;
        } else if (selectedModel.startsWith(CodexCliProvider.MODEL_PREFIX)) {
            if (codexService != null) {
                codexService.setModel(selectedModel.substring(CodexCliProvider.MODEL_PREFIX.length()));
            }
            return codexService;
        } else if (selectedModel.startsWith(ClaudeCodeCliProvider.MODEL_PREFIX)) {
            if (claudeCodeService != null) {
                claudeCodeService.setModel(selectedModel.substring(ClaudeCodeCliProvider.MODEL_PREFIX.length()));
            }
            return claudeCodeService;
        } else {
            claudeService.setModel(selectedModel);
            return claudeService;
        }
    }
}
