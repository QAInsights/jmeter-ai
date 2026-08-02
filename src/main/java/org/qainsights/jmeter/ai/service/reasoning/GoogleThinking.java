package org.qainsights.jmeter.ai.service.reasoning;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.google.genai.types.ThinkingConfig;

/**
 * Gemini-SDK specifics of thinking configuration, extracted so
 * {@code GoogleAiService} stays small: when to attach a {@link ThinkingConfig},
 * and how to split streamed parts into thought vs answer text.
 * <p>
 * The request shape comes from the vendored catalog data: models with named
 * effort values (Gemini 3) get {@code thinkingLevel}, budget-shaped models
 * (Gemini 2.5) get {@code thinkingBudget}. Toggleable models (Gemini Flash)
 * get an explicit {@code thinkingBudget(0)} when the user switched thinking
 * off; non-toggleable reasoning models (Gemini Pro) always think, so the
 * chosen effort applies without a toggle.
 */
public final class GoogleThinking {

    private GoogleThinking() {
    }

    /**
     * @param settings the user's reasoning choices (may be null - defaults apply)
     * @param model    the bare Gemini model id (no {@code google:} prefix)
     * @return the thinking config to attach to the request, or empty to leave
     *         the model's default behavior
     */
    public static Optional<ThinkingConfig> configFor(ReasoningSettings settings, String model) {
        Optional<ModelCapabilityCatalog.CapabilityInfo> caps =
                ModelCapabilityCatalog.getInstance().capabilities("google:" + model);
        if (caps.isEmpty() || !caps.get().isReasoning()) {
            return Optional.empty();
        }
        ModelCapabilityCatalog.CapabilityInfo info = caps.get();
        boolean thinkingOn = settings != null && settings.isThinkingEnabled();

        if (info.isToggleable() && !thinkingOn) {
            // Explicit off-switch (Gemini Flash): disable thinking entirely
            return Optional.of(ThinkingConfig.builder()
                    .includeThoughts(false)
                    .thinkingBudget(0)
                    .build());
        }

        String effort = settings != null ? settings.getEffort() : ReasoningSettings.DEFAULT_EFFORT;
        List<String> values = info.getEffortLevels();
        if (!values.isEmpty()) {
            // Named levels (Gemini 3): validate against the catalog values
            String level = values.contains(effort) ? effort : values.get(values.size() - 1);
            return Optional.of(ThinkingConfig.builder()
                    .includeThoughts(true)
                    .thinkingLevel(level)
                    .build());
        }
        if (info.hasBudget()) {
            return Optional.of(ThinkingConfig.builder()
                    .includeThoughts(true)
                    .thinkingBudget(ReasoningCapabilities.googleThinkingBudget(effort))
                    .build());
        }
        return Optional.empty();
    }

    /**
     * Routes the parts of one streamed chunk: thought parts go to the reasoning
     * consumer, everything else to the token consumer.
     */
    public static void routeChunkParts(GenerateContentResponse chunk,
            Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer) {
        chunk.candidates().ifPresent(candidates -> candidates.stream()
                .filter(candidate -> candidate.content().isPresent())
                .flatMap(candidate -> candidate.content().get().parts().orElse(List.of()).stream())
                .forEach(part -> routePart(part, tokenConsumer, reasoningConsumer)));
    }

    /** Routes a single part by its thought flag. */
    static void routePart(Part part, Consumer<String> tokenConsumer, Consumer<String> reasoningConsumer) {
        String text = part.text().orElse(null);
        if (text == null || text.isEmpty()) {
            return;
        }
        if (part.thought().orElse(false)) {
            reasoningConsumer.accept(text);
        } else {
            tokenConsumer.accept(text);
        }
    }
}
