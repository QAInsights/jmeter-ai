package org.qainsights.jmeter.ai.service.reasoning;

import java.util.Locale;

import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ThinkingConfigEnabled;

/**
 * Anthropic-SDK specifics of extended thinking, extracted so {@code ClaudeService}
 * stays small: when thinking applies, how to configure it on a request, and how
 * to read thinking blocks back out of a response.
 * <p>
 * API constraints honoured here and by the caller: a thinking request must not
 * set {@code temperature} (the service skips it), and {@code max_tokens} must
 * exceed the thinking budget (the service bumps it).
 */
public final class AnthropicThinking {

    /** Headroom added on top of the thinking budget when bumping max_tokens. */
    public static final long MAX_TOKENS_HEADROOM = 1024;

    private AnthropicThinking() {
    }

    /** True when the user enabled thinking and the model supports the toggle. */
    public static boolean applies(ReasoningSettings settings, String model) {
        return settings != null
                && settings.isThinkingEnabled()
                && ReasoningCapabilities.supportsThinkingToggle(model);
    }

    /**
     * True for models that use adaptive thinking (the claude 5 "fable" family):
     * the request carries no budget - the model decides how deeply to think.
     */
    public static boolean isAdaptiveThinkingModel(String model) {
        return ReasoningCapabilities.isAdaptiveClaude(model);
    }

    /**
     * Applies thinking to the request builder: adaptive config (summarized
     * thoughts) plus the chosen effort via {@code output_config} for
     * fable-family models, enabled-thinking with the budget for the current
     * effort level for the budget-based models.
     *
     * @return the thinking budget used, or 0 for adaptive models and when
     *         thinking was not applied (adaptive models need no max_tokens bump)
     */
    public static long applyTo(MessageCreateParams.Builder builder, ReasoningSettings settings, String model) {
        if (!applies(settings, model)) {
            return 0;
        }
        if (isAdaptiveThinkingModel(model) && !hasCatalogBudget(model)) {
            builder.thinking(ThinkingConfigAdaptive.builder()
                    .display(ThinkingConfigAdaptive.Display.SUMMARIZED)
                    .build());
            OutputConfig.Effort effort = toOutputEffort(settings.getEffort());
            if (effort != null) {
                builder.outputConfig(OutputConfig.builder().effort(effort).build());
            }
            return 0;
        }
        long budget = ReasoningCapabilities.anthropicBudgetTokens(settings.getEffort());
        builder.thinking(ThinkingConfigEnabled.builder().budgetTokens(budget).build());
        return budget;
    }

    /**
     * True when the catalog carries a budget range for the model - data wins
     * over the fable-family regex: a 5-family model that lists
     * {@code budget_tokens} options uses the budget shape after all.
     */
    private static boolean hasCatalogBudget(String model) {
        return ModelCapabilityCatalog.getInstance().capabilities(model)
                .map(ModelCapabilityCatalog.CapabilityInfo::hasBudget)
                .orElse(false);
    }

    /** Maps a lowercase effort level onto the SDK enum; unknown levels yield null. */
    public static OutputConfig.Effort toOutputEffort(String level) {
        String normalized = level == null ? "" : level.trim().toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "low":
                return OutputConfig.Effort.LOW;
            case "medium":
                return OutputConfig.Effort.MEDIUM;
            case "high":
                return OutputConfig.Effort.HIGH;
            case "xhigh":
                return OutputConfig.Effort.XHIGH;
            case "max":
                return OutputConfig.Effort.MAX;
            default:
                return null;
        }
    }

    /**
     * The max_tokens value to send: at least budget + headroom when thinking is
     * on (the API rejects max_tokens &lt;= budget), otherwise the configured value.
     */
    public static long effectiveMaxTokens(long configuredMaxTokens, long thinkingBudget) {
        if (thinkingBudget <= 0) {
            return configuredMaxTokens;
        }
        return Math.max(configuredMaxTokens, thinkingBudget + MAX_TOKENS_HEADROOM);
    }

    /** Concatenated thinking text from a response message, or null when there was none. */
    public static String extractThinking(Message message) {
        return message == null ? null : extractThinking(message.content());
    }

    /** Concatenated thinking text from content blocks, or null when there was none. */
    public static String extractThinking(java.util.List<ContentBlock> content) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : content) {
            block.thinking().ifPresent(thinking -> sb.append(thinking.thinking()));
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Concatenated plain-text blocks from a response message (skips thinking blocks). */
    public static String extractText(Message message) {
        return extractText(message.content());
    }

    /** Concatenated plain-text blocks (skips thinking blocks). */
    public static String extractText(java.util.List<ContentBlock> content) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : content) {
            block.text().ifPresent(text -> sb.append(text.text()));
        }
        return sb.toString();
    }
}
