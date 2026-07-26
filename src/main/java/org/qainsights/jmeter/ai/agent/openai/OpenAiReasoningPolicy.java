package org.qainsights.jmeter.ai.agent.openai;

import java.util.Locale;
import java.util.Optional;

import com.openai.models.ReasoningEffort;

/**
 * Decides the {@code reasoning_effort} an agent (tool-calling) request must send
 * for a given OpenAI model.
 * <p>
 * The gpt-5.1-and-later reasoning models reject function tools on
 * {@code /v1/chat/completions} unless reasoning is explicitly switched off:
 * <blockquote>
 * 400: Function tools with reasoning_effort are not supported for gpt-5.6-terra in
 * /v1/chat/completions. To use function tools, use /v1/responses or set
 * reasoning_effort to 'none'.
 * </blockquote>
 * Those models are identified by their dotted minor version ({@code gpt-5.1},
 * {@code gpt-5.6-terra}, {@code gpt-5.6-sol}, ...); {@code none} is only a valid
 * effort from gpt-5.1 onwards, so the original {@code gpt-5}/{@code gpt-5-mini}
 * line and the o-series are left untouched (they accept function tools as-is).
 */
public final class OpenAiReasoningPolicy {

    private OpenAiReasoningPolicy() {
    }

    /**
     * @param model the bare OpenAI model id (no {@code openai:} prefix)
     * @return the effort to send with a tool-calling request, or empty to leave the
     *         parameter off entirely (the model's default applies)
     */
    public static Optional<ReasoningEffort> forToolCalling(String model) {
        return requiresDisabledReasoning(model) ? Optional.of(ReasoningEffort.NONE) : Optional.empty();
    }

    /** True for the gpt-5.1+ models that refuse function tools while reasoning. */
    public static boolean requiresDisabledReasoning(String model) {
        if (model == null) {
            return false;
        }
        return model.trim().toLowerCase(Locale.ROOT).startsWith("gpt-5.");
    }
}
