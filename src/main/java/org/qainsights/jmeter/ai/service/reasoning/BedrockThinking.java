package org.qainsights.jmeter.ai.service.reasoning;

import java.util.List;
import java.util.Optional;

import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;

/**
 * Bedrock-Converse specifics of reasoning configuration: the config rides in
 * {@code additionalModelRequestFields}, and the shape depends on the vendor
 * family of the model id:
 * <ul>
 *   <li><b>Anthropic Claude</b> - {@code {"thinking": {"type": "enabled",
 *       "budget_tokens": N}}} (budget family) or {@code {"type": "adaptive"}}
 *       (fable family); toggle-gated.</li>
 *   <li><b>OpenAI</b> (gpt-oss, gpt-5.x) - {@code {"reasoning_effort": level}};
 *       always-on unless the model accepts {@code "none"}.</li>
 *   <li><b>Amazon Nova 2</b> - {@code {"reasoningConfig": {"type": "enabled",
 *       "maxReasoningEffort": level}}}; toggle-gated (disabled by default).</li>
 * </ul>
 * Other reasoning-capable Bedrock families (deepseek, qwen, glm, kimi, ...)
 * send nothing - their reasoning still renders when they stream
 * reasoningContent deltas back.
 */
public final class BedrockThinking {

    private BedrockThinking() {
    }

    /** True when the user enabled thinking and the model has a toggleable shape. */
    public static boolean applies(ReasoningSettings settings, String model) {
        return settings != null
                && settings.isThinkingEnabled()
                && ReasoningCapabilities.supportsThinkingToggle("bedrock:" + model);
    }

    /**
     * The thinking budget for the current settings/model (budget-family Claude
     * only), or 0 for adaptive models and when thinking does not apply.
     */
    public static long budgetFor(ReasoningSettings settings, String model) {
        if (!applies(settings, model)
                || AnthropicThinking.isAdaptiveThinkingModel(
                        ReasoningCapabilities.stripBedrockNamespace(model))) {
            return 0;
        }
        return ReasoningCapabilities.anthropicBudgetTokens(settings.getEffort());
    }

    /**
     * True when the request must not set a custom temperature: any
     * thinking-enabled Claude request (budget or adaptive), and Nova 2 at
     * {@code high} effort (AWS requirement).
     */
    public static boolean dropsTemperature(ReasoningSettings settings, String model) {
        if ("anthropic".equals(ReasoningCapabilities.bedrockFamily(model))) {
            return applies(settings, model);
        }
        if ("amazon".equals(ReasoningCapabilities.bedrockFamily(model))) {
            return applies(settings, model) && settings != null
                    && "high".equals(settings.getEffort());
        }
        return false;
    }

    /** The additionalModelRequestFields document for the request, or null to send nothing. */
    public static Document additionalFieldsFor(ReasoningSettings settings, String model) {
        switch (ReasoningCapabilities.bedrockFamily(model)) {
            case "anthropic":
                return claudeFieldsFor(settings, model);
            case "openai":
                return openAiFieldsFor(settings, model);
            case "amazon":
                return novaFieldsFor(settings, model);
            default:
                return null;
        }
    }

    /** Anthropic Claude on Bedrock: enabled+budget or adaptive, toggle-gated. */
    private static Document claudeFieldsFor(ReasoningSettings settings, String model) {
        if (!applies(settings, model)) {
            return null;
        }
        if (AnthropicThinking.isAdaptiveThinkingModel(
                ReasoningCapabilities.stripBedrockNamespace(model))) {
            return Document.mapBuilder()
                    .putDocument("thinking", Document.mapBuilder()
                            .putString("type", "adaptive")
                            .build())
                    .build();
        }
        long budget = budgetFor(settings, model);
        if (budget <= 0) {
            return null;
        }
        return Document.mapBuilder()
                .putDocument("thinking", Document.mapBuilder()
                        .putString("type", "enabled")
                        .putNumber("budget_tokens", budget)
                        .build())
                .build();
    }

    /** OpenAI family on Bedrock: snake_case reasoning_effort. */
    private static Document openAiFieldsFor(ReasoningSettings settings, String model) {
        Optional<ModelCapabilityCatalog.CapabilityInfo> caps =
                ModelCapabilityCatalog.getInstance().capabilities("bedrock:" + model);
        if (caps.isEmpty() || !caps.get().isReasoning()) {
            return null;
        }
        List<String> levels = caps.get().getEffortLevels();
        if (levels.isEmpty()) {
            return null;
        }
        boolean toggleable = levels.contains("none");
        boolean thinkingOn = settings != null && settings.isThinkingEnabled();
        if (toggleable && !thinkingOn) {
            return effortDocument("none");
        }
        String effort = settings != null ? settings.getEffort() : ReasoningSettings.DEFAULT_EFFORT;
        if (!levels.contains(effort)) {
            effort = levels.contains(ReasoningSettings.DEFAULT_EFFORT)
                    ? ReasoningSettings.DEFAULT_EFFORT
                    : levels.get(levels.size() - 1);
        }
        return effortDocument(effort);
    }

    /** Amazon Nova 2: reasoningConfig with maxReasoningEffort, toggle-gated. */
    private static Document novaFieldsFor(ReasoningSettings settings, String model) {
        if (!applies(settings, model)) {
            // Disabled is Nova's default - nothing to send
            return null;
        }
        List<String> levels = ModelCapabilityCatalog.getInstance()
                .capabilities("bedrock:" + model)
                .map(ModelCapabilityCatalog.CapabilityInfo::getEffortLevels)
                .orElse(List.of("low", "medium", "high"));
        String effort = settings.getEffort();
        if (!levels.contains(effort)) {
            effort = ReasoningSettings.DEFAULT_EFFORT;
        }
        return Document.mapBuilder()
                .putDocument("reasoningConfig", Document.mapBuilder()
                        .putString("type", "enabled")
                        .putString("maxReasoningEffort", effort)
                        .build())
                .build();
    }

    private static Document effortDocument(String effort) {
        return Document.mapBuilder()
                .putString("reasoning_effort", effort)
                .build();
    }

    /** Concatenated reasoning text from a Converse response, or null when there was none. */
    public static String extractReasoning(ConverseResponse response) {
        if (response == null || response.output() == null || response.output().message() == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : response.output().message().content()) {
            if (block.reasoningContent() != null && block.reasoningContent().reasoningText() != null) {
                String text = block.reasoningContent().reasoningText().text();
                if (text != null) {
                    sb.append(text);
                }
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
