package org.qainsights.jmeter.ai.service;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.Reasoning;
import com.openai.models.models.Model;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseStreamEvent;
import org.qainsights.jmeter.ai.service.reasoning.MetaReasoning;
import org.qainsights.jmeter.ai.service.reasoning.ReasoningSettings;
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
 * <p>
 * Uses the Responses API at {@code https://api.meta.ai/v1}. Chat Completions is
 * deliberately avoided: Muse Spark's chain of thought is private there
 * (redacted to empty for external callers), while the Responses API returns a
 * natural-language reasoning summary when {@code reasoning.summary} is set -
 * that summary is what renders in the Thoughts card. Reasoning effort
 * (minimal/low/medium/high/xhigh) is attached per request; Muse always
 * reasons, so there is no off-switch ({@code "none"} returns HTTP 400).
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
    private ReasoningSettings reasoningSettings;
    private String lastReasoning;

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
    public void setReasoningSettings(ReasoningSettings settings) {
        this.reasoningSettings = settings;
    }

    @Override
    public String consumeLastReasoning() {
        String reasoning = lastReasoning;
        lastReasoning = null;
        return reasoning;
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
            Response response = client.responses().create(buildResponsesParams(conversation, model));
            lastReasoning = extractReasoningSummary(response);
            return extractOutputText(response);
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
        return generateStreamResponse(conversation, model, tokenConsumer,
                reasoning -> {}, onComplete, onError);
    }

    @Override
    public Runnable generateStreamResponse(List<String> conversation, String model,
                                           Consumer<String> tokenConsumer,
                                           Consumer<String> reasoningConsumer,
                                           Runnable onComplete,
                                           Consumer<Exception> onError) {
        if (client == null) {
            return () -> {};
        }
        ResponseCreateParams params = buildResponsesParams(conversation, model);
        Thread streamThread = new Thread(() -> {
            try {
                try (com.openai.core.http.StreamResponse<ResponseStreamEvent> stream =
                             client.responses().createStreaming(params)) {
                    stream.stream().forEach(event -> {
                        event.reasoningSummaryTextDelta().ifPresent(delta ->
                                javax.swing.SwingUtilities.invokeLater(
                                        () -> reasoningConsumer.accept(delta.delta())));
                        event.outputTextDelta().ifPresent(delta ->
                                javax.swing.SwingUtilities.invokeLater(
                                        () -> tokenConsumer.accept(delta.delta())));
                    });
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

    private ResponseCreateParams buildResponsesParams(List<String> conversation, String targetModel) {
        String modelToUse = (targetModel != null && !targetModel.isEmpty()) ? targetModel : this.model;
        ResponseCreateParams.Builder builder = ResponseCreateParams.builder()
                .model(modelToUse)
                .maxOutputTokens(maxTokens)
                .temperature((double) temperature)
                .instructions(systemPrompt);

        // summary=auto is what makes Muse's reasoning visible at all - Chat
        // Completions redacts the chain of thought for external callers.
        MetaReasoning.effortFor(reasoningSettings, modelToUse).ifPresent(effort -> {
            Reasoning.Summary summaryLevel = summaryLevel();
            builder.reasoning(Reasoning.builder()
                    .effort(effort)
                    .summary(summaryLevel)
                    .build());
            log.info("Reasoning effort set to {} with summary={} for model {}",
                    effort, summaryLevel, modelToUse);
        });

        List<String> history = filterErrorMessages(buildLimitedHistory(conversation));
        List<ResponseInputItem> items = new ArrayList<>();
        if (history.isEmpty()) {
            items.add(inputItem(EasyInputMessage.Role.USER, "Hello, how can you help me with JMeter?"));
        } else {
            for (int i = 0; i < history.size(); i++) {
                String msg = history.get(i);
                if (msg != null && !msg.isEmpty()) {
                    items.add(inputItem(
                            i % 2 == 0 ? EasyInputMessage.Role.USER : EasyInputMessage.Role.ASSISTANT,
                            msg));
                }
            }
        }
        builder.input(ResponseCreateParams.Input.ofResponse(items));
        return builder.build();
    }

    private static ResponseInputItem inputItem(EasyInputMessage.Role role, String text) {
        return ResponseInputItem.ofEasyInputMessage(
                EasyInputMessage.builder().role(role).content(text).build());
    }

    /**
     * The reasoning-summary level to request: {@code meta.reasoning.summary} =
     * auto (default), concise, or detailed. Unknown values fall back to auto.
     */
    static Reasoning.Summary summaryLevel() {
        String level = AiConfig.getProperty("meta.reasoning.summary", "auto")
                .trim().toLowerCase(java.util.Locale.ROOT);
        switch (level) {
            case "concise":
                return Reasoning.Summary.CONCISE;
            case "detailed":
                return Reasoning.Summary.DETAILED;
            default:
                return Reasoning.Summary.AUTO;
        }
    }

    /** Concatenated output text from a Responses API response. */
    static String extractOutputText(Response response) {
        StringBuilder sb = new StringBuilder();
        for (ResponseOutputItem item : response.output()) {
            item.message().ifPresent(message -> message.content().forEach(content ->
                    content.outputText().ifPresent(text -> sb.append(text.text()))));
        }
        return sb.length() == 0 ? "No content available" : sb.toString();
    }

    /** Concatenated reasoning summary from a Responses API response, or null. */
    static String extractReasoningSummary(Response response) {
        StringBuilder sb = new StringBuilder();
        for (ResponseOutputItem item : response.output()) {
            item.reasoning().ifPresent(reasoning ->
                    reasoning.summary().forEach(part -> sb.append(part.text())));
        }
        return sb.length() == 0 ? null : sb.toString();
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
