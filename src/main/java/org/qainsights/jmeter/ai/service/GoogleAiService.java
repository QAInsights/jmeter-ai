package org.qainsights.jmeter.ai.service;

import com.google.genai.Client;
import com.google.genai.types.*;
import org.qainsights.jmeter.ai.service.reasoning.GoogleThinking;
import org.qainsights.jmeter.ai.service.reasoning.ReasoningSettings;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.qainsights.jmeter.ai.utils.Constants;
import org.qainsights.jmeter.ai.utils.ModelUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class GoogleAiService implements AiService {
    private static final Logger log = LoggerFactory.getLogger(GoogleAiService.class);
    private final Client googleClient;
    private final float temperature;
    private final int maxOutputTokens;
    private final String systemPrompt;
    private final int maxHistorySize;
    private final boolean streamingEnabled;
    private String model;
    private ReasoningSettings reasoningSettings;
    private String lastReasoning;
    private org.qainsights.jmeter.ai.service.usage.UsageStats usageStats;

    public GoogleAiService(Client googleClient) {
        this.model = AiConfig.getProperty("google.default.model", "gemini-2.5-flash");
        this.temperature = parseTemperature(AiConfig.getProperty("google.temperature", "0.7"));
        this.maxHistorySize = Integer.parseInt(AiConfig.getProperty("google.max.history.size", "10"));
        this.maxOutputTokens = (int) Long.parseLong(AiConfig.getProperty("google.max.tokens", "4096"));

        String configuredPrompt = AiConfig.getProperty("google.system.prompt", "");
        this.systemPrompt = (configuredPrompt != null && !configuredPrompt.isEmpty())
                ? configuredPrompt : Constants.DEFAULT_JMETER_SYSTEM_PROMPT;

        this.streamingEnabled = Boolean.parseBoolean(AiConfig.getProperty("google.streaming.enabled", "true"));
        this.googleClient = googleClient;

        log.info("Initialized Google Gemini service with model: {}, temperature: {}", this.model, temperature);
    }

    @Override
    public void setReasoningSettings(ReasoningSettings settings) {
        this.reasoningSettings = settings;
    }

    /** The reasoning settings injected via {@link #setReasoningSettings} (may be null). */
    public ReasoningSettings getReasoningSettings() {
        return reasoningSettings;
    }

    /** The underlying Google Gen AI client, e.g. for wiring agent mode ({@code JMeterAgent}). */
    public Client getClient() {
        return googleClient;
    }

    @Override
    public String consumeLastReasoning() {
        String reasoning = lastReasoning;
        lastReasoning = null;
        return reasoning;
    }

    /** Receives the session usage accumulator (context-stats label). */
    @Override
    public void setUsageStats(org.qainsights.jmeter.ai.service.usage.UsageStats stats) {
        this.usageStats = stats;
    }

    /**
     * Builds the per-request config: static parts (temperature, max tokens,
     * system instruction) plus a thinking config when the user's reasoning
     * settings call for one on this model.
     */
    private GenerateContentConfig buildConfig(String modelId) {
        GenerateContentConfig.Builder builder = GenerateContentConfig.builder()
                .temperature(temperature)
                .maxOutputTokens(maxOutputTokens)
                .systemInstruction(Content.builder()
                        .parts(List.of(Part.builder().text(systemPrompt).build()))
                        .build());
        GoogleThinking.configFor(reasoningSettings, modelId).ifPresent(config -> {
            builder.thinkingConfig(config);
            log.info("Thinking config applied for model {}: {}", modelId, config);
        });
        return builder.build();
    }

    private static float parseTemperature(String value) {
        return ModelUtils.parseTemperature(value);
    }

    private static boolean isChatModel(String modelId) {
        if (!modelId.startsWith("gemini-") && !modelId.startsWith("gemma-")) {
            return false;
        }
        String lower = modelId.toLowerCase();
        return !lower.contains("tts")
                && !lower.contains("embedding")
                && !lower.contains("-image")
                && !lower.contains("live")
                && !lower.contains("native-audio")
                && !lower.contains("computer-use")
                && !lower.contains("robotics");
    }

    public void setModel(String modelId) {
        this.model = modelId;
        log.info("Google Gemini model set to: {}", modelId);
    }

    public String getCurrentModel() {
        return model;
    }

    @Override
    public String generateResponse(List<String> conversation) {
        return generateResponse(conversation, this.model);
    }

    @Override
    public String generateResponse(List<String> conversation, String model) {
        try {
            if (googleClient == null) {
                log.error("Google client not initialized");
                return "Error: Google client not initialized";
            }

            String modelToUse = (model != null && !model.isEmpty()) ? model : this.model;
            List<Content> contents = buildContents(conversation);

            GenerateContentResponse response = googleClient.models.generateContent(
                    modelToUse,
                    contents,
                    buildConfig(modelToUse));

            if (usageStats != null) {
                response.usageMetadata().ifPresent(usage ->
                        usageStats.record("google:" + modelToUse,
                                usage.promptTokenCount().orElse(0),
                                usage.candidatesTokenCount().orElse(0)));
            }

            lastReasoning = extractThoughts(response);
            return response.text();

        } catch (Exception e) {
            log.error("Error generating response from Google Gemini", e);
            return "Error: " + e.getMessage();
        }
    }

    @Override
    public String getName() {
        return "Google Gemini";
    }

    @Override
    public Runnable generateStreamResponse(List<String> conversation, String model, Consumer<String> tokenConsumer, Runnable onComplete, Consumer<Exception> onError) {
        return generateStreamResponse(conversation, model, tokenConsumer, reasoning -> {}, onComplete, onError);
    }

    @Override
    public Runnable generateStreamResponse(List<String> conversation, String model, Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer, Runnable onComplete, Consumer<Exception> onError) {
        if (googleClient == null || !streamingEnabled) {
            return () -> {
            };
        }

        String modelToUse = (model != null && !model.isEmpty()) ? model : this.model;
        List<Content> contents = buildContents(conversation);
        GenerateContentConfig config = buildConfig(modelToUse);

        Thread streamThread = new Thread(() -> {
            try {
                Iterable<GenerateContentResponse> stream = googleClient.models.generateContentStream(
                        modelToUse,
                        contents,
                        config);

                GenerateContentResponseUsageMetadata[] lastUsage = new GenerateContentResponseUsageMetadata[1];
                for (GenerateContentResponse chunk : stream) {
                    chunk.usageMetadata().ifPresent(usage -> lastUsage[0] = usage);
                    GoogleThinking.routeChunkParts(chunk,
                            text -> javax.swing.SwingUtilities.invokeLater(() -> tokenConsumer.accept(text)),
                            thought -> javax.swing.SwingUtilities.invokeLater(() -> reasoningConsumer.accept(thought)));
                }

                if (usageStats != null && lastUsage[0] != null) {
                    usageStats.record("google:" + modelToUse,
                            lastUsage[0].promptTokenCount().orElse(0),
                            lastUsage[0].candidatesTokenCount().orElse(0));
                }

                javax.swing.SwingUtilities.invokeLater(onComplete);
            } catch (Exception e) {
                log.error("Error in Google Gemini streaming response", e);
                javax.swing.SwingUtilities.invokeLater(() -> onError.accept(e));
            }
        });
        streamThread.setDaemon(true);
        streamThread.start();

        return () -> {
            log.info("Cancelling Google Gemini stream");
            if (streamThread.isAlive()) {
                streamThread.interrupt();
            }
        };
    }

    /** Concatenated thought-part text from a non-streaming response, or null. */
    private static String extractThoughts(GenerateContentResponse response) {
        StringBuilder sb = new StringBuilder();
        response.candidates().ifPresent(candidates -> candidates.stream()
                .filter(candidate -> candidate.content().isPresent())
                .flatMap(candidate -> candidate.content().get().parts().orElse(List.of()).stream())
                .filter(part -> part.thought().orElse(false))
                .forEach(part -> part.text().ifPresent(sb::append)));
        return sb.length() == 0 ? null : sb.toString();
    }

    private List<Content> buildContents(List<String> conversation) {
        List<String> limitedHistory = buildLimitedHistory(conversation);
        List<String> cleanHistory = filterErrorMessages(limitedHistory);

        List<Content> contents = new ArrayList<>();
        if (cleanHistory.isEmpty()) {
            contents.add(Content.builder()
                    .role("user")
                    .parts(List.of(Part.builder().text("Hello, how can you help me with JMeter?").build()))
                    .build());
        } else {
            for (int i = 0; i < cleanHistory.size(); i++) {
                String msg = cleanHistory.get(i);
                if (msg == null || msg.isEmpty()) {
                    continue;
                }
                String role = (i % 2 == 0) ? "user" : "model";
                contents.add(Content.builder()
                        .role(role)
                        .parts(List.of(Part.builder().text(msg).build()))
                        .build());
            }
        }
        return contents;
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
        List<String> models = null;
        try {
            models = new ArrayList<>();
            Iterable<Model> googleModels = googleClient.models.list(null);
            for (Model model : googleModels) {
                String modelId = String.valueOf(model.name());
                if (modelId.startsWith("Optional[")) {
                    modelId = modelId.substring(9, modelId.length() - 1);
                }
                if (modelId.startsWith("models/")) {
                    modelId = modelId.substring(7);
                }
                if (!isChatModel(modelId)) {
                    continue;
                }
                log.info("Google model: {}", modelId);
                models.add(modelId);
            }
        } catch (Exception e) {
            log.error("Error listing Google models", e);
        }
        return models;
    }
}
