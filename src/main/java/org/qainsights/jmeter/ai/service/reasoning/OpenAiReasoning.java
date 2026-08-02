package org.qainsights.jmeter.ai.service.reasoning;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.openai.models.ReasoningEffort;

/**
 * Maps the user's reasoning settings onto OpenAI's {@code reasoning_effort}
 * parameter for plain-chat requests. Rules:
 * <ul>
 *   <li>Models with no effort support (gpt-4o, ...) send nothing.</li>
 *   <li>Always-reasoning models (o-series, non-dotted gpt-5) always send the
 *       user's chosen effort.</li>
 *   <li>Dotted gpt-5.x models are toggleable: thinking on sends the chosen
 *       effort, thinking off sends {@code none}.</li>
 * </ul>
 * (Agent tool-calling requests go through {@code OpenAiReasoningPolicy} instead,
 * which forces {@code none} for gpt-5.x due to the chat-completions restriction.)
 */
public final class OpenAiReasoning {

    private OpenAiReasoning() {
    }

    /**
     * @param settings the user's reasoning choices (may be null - defaults apply)
     * @param model    the bare OpenAI model id (no {@code openai:} prefix)
     * @return the effort to send with the request, or empty to leave the
     *         parameter off entirely
     */
    public static Optional<ReasoningEffort> effortFor(ReasoningSettings settings, String model) {
        if (model == null || model.isEmpty()) {
            return Optional.empty();
        }
        List<String> levels = ReasoningCapabilities.effortLevels("openai:" + model);
        if (levels.isEmpty()) {
            return Optional.empty();
        }

        // Toggleable = the model accepts "none" as an effort (catalog data)
        boolean toggleable = levels.contains("none");
        boolean thinkingOn = settings != null && settings.isThinkingEnabled();
        if (toggleable) {
            return Optional.of(thinkingOn ? toEffort(settings.getEffort()) : ReasoningEffort.NONE);
        }

        String effort = settings != null ? settings.getEffort() : ReasoningSettings.DEFAULT_EFFORT;
        if (!levels.contains(effort)) {
            effort = levels.contains(ReasoningSettings.DEFAULT_EFFORT)
                    ? ReasoningSettings.DEFAULT_EFFORT
                    : levels.get(levels.size() - 1);
        }
        return Optional.of(toEffort(effort));
    }

    /** Maps a lowercase level name onto the SDK enum; unknown names become MEDIUM. */
    public static ReasoningEffort toEffort(String level) {
        String normalized = level == null ? ReasoningSettings.DEFAULT_EFFORT
                : level.trim().toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "none":
                return ReasoningEffort.NONE;
            case "minimal":
                return ReasoningEffort.MINIMAL;
            case "low":
                return ReasoningEffort.LOW;
            case "high":
                return ReasoningEffort.HIGH;
            case "xhigh":
                return ReasoningEffort.XHIGH;
            default:
                return ReasoningEffort.MEDIUM;
        }
    }
}
