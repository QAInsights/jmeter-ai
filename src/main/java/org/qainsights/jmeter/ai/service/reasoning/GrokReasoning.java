package org.qainsights.jmeter.ai.service.reasoning;

import java.util.List;
import java.util.Optional;

import com.openai.models.ReasoningEffort;

/**
 * Maps the user's reasoning settings onto the {@code reasoning_effort}
 * parameter for xAI Grok models (OpenAI-compatible API). Only the grok-3-mini
 * family accepts an effort ({@code low}/{@code high}); it always reasons, so
 * there is no toggle and no {@code none} - an unset or unsupported choice
 * falls back to {@code high}.
 */
public final class GrokReasoning {

    private GrokReasoning() {
    }

    /**
     * @param settings the user's reasoning choices (may be null - defaults apply)
     * @param model    the bare Grok model id (no {@code grok:} prefix)
     * @return the effort to send, or empty for models without effort support
     */
    public static Optional<ReasoningEffort> effortFor(ReasoningSettings settings, String model) {
        List<String> levels = ReasoningCapabilities.effortLevels("grok:" + model);
        if (levels.isEmpty()) {
            return Optional.empty();
        }
        String effort = settings != null ? settings.getEffort() : "high";
        if (!levels.contains(effort)) {
            effort = "high";
        }
        return Optional.of(OpenAiReasoning.toEffort(effort));
    }
}
