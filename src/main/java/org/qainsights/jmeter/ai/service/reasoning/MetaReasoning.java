package org.qainsights.jmeter.ai.service.reasoning;

import java.util.List;
import java.util.Optional;

import com.openai.models.ReasoningEffort;

/**
 * Maps the user's reasoning settings onto the {@code reasoning_effort}
 * parameter for Meta Muse models (OpenAI-compatible API). Muse Spark is an
 * always-on reasoning model - there is no toggle - with the effort values the
 * vendored catalog lists for it (minimal / low / medium / high / xhigh). An
 * unset or unsupported choice falls back to medium.
 */
public final class MetaReasoning {

    private MetaReasoning() {
    }

    /**
     * @param settings the user's reasoning choices (may be null - defaults apply)
     * @param model    the bare Meta Muse model id (no {@code meta:} prefix)
     * @return the effort to send, or empty for models without effort support
     */
    public static Optional<ReasoningEffort> effortFor(ReasoningSettings settings, String model) {
        List<String> levels = ReasoningCapabilities.effortLevels("meta:" + model);
        if (levels.isEmpty()) {
            return Optional.empty();
        }
        String effort = settings != null ? settings.getEffort() : ReasoningSettings.DEFAULT_EFFORT;
        if (!levels.contains(effort)) {
            effort = levels.contains(ReasoningSettings.DEFAULT_EFFORT)
                    ? ReasoningSettings.DEFAULT_EFFORT
                    : levels.get(levels.size() - 1);
        }
        return Optional.of(OpenAiReasoning.toEffort(effort));
    }
}
