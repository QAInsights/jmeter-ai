package org.qainsights.jmeter.ai.telemetry;

import org.qainsights.jmeter.ai.utils.AiConfig;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns the model picked in the chat panel into telemetry-safe values. Only well-known
 * public model ids are reported; fine-tunes, gateway aliases and anything else become
 * {@code custom} so no organisation-specific name ever leaves the machine.
 */
final class TelemetryModels {
    private static final String[] PREFIXES = {
        "openai", "ollama", "deepseek", "google", "grok", "meta", "bedrock", "apple", "codex", "claude-code",
    };
    private static final String ID = "[a-z0-9.-]";
    private static final Pattern WELL_KNOWN = Pattern.compile(
            "^(?:(?:us|eu|apac|global)\\.)?(?:anthropic\\.|meta\\.|amazon\\.|mistral\\.|cohere\\.|deepseek\\.)?"
            + "(?:gpt-" + ID + "+|chatgpt-" + ID + "+|o[1-9](?:-(?:mini|pro|preview|deep-research))*(?:-\\d{4}-\\d{2}-\\d{2})?"
            + "|codex(?:-" + ID + "+)?|claude(?:-" + ID + "+)?|gemini-" + ID + "+|deepseek-" + ID + "+"
            + "|grok-" + ID + "+|muse-" + ID + "+|nova-" + ID + "+|titan-" + ID + "+|command-" + ID + "+|on-device"
            + "|(?:llama|codellama|qwen|mistral|mixtral|gemma|phi|devstral|codestral)" + ID + "*)"
            + "(?::" + ID + "+)?$");
    private static final int MAX_LENGTH = 64;

    private TelemetryModels() {
    }

    /** The model id without its provider prefix when well known, {@code custom} otherwise, {@code unknown} when unset. */
    static String publicName(String selectedModel) {
        if (selectedModel == null || selectedModel.isBlank()) {
            return "unknown";
        }
        String id = selectedModel.trim().toLowerCase(Locale.ROOT);
        for (String prefix : PREFIXES) {
            if (id.startsWith(prefix + ":")) {
                id = id.substring(prefix.length() + 1);
                break;
            }
        }
        return id.length() <= MAX_LENGTH && WELL_KNOWN.matcher(id).matches() ? id : "custom";
    }

    /** Provider behind a chat-panel model id; unprefixed ids are Claude, unset falls back to the configured service. */
    static String provider(String selectedModel) {
        if (selectedModel == null || selectedModel.isBlank()) {
            return AiConfig.getProperty("jmeter.ai.service.type", "openai");
        }
        String id = selectedModel.trim().toLowerCase(Locale.ROOT);
        for (String prefix : PREFIXES) {
            if (id.startsWith(prefix + ":")) {
                return prefix;
            }
        }
        return "anthropic";
    }

    /** The configured default model for the configured service, used before any message is sent. */
    static String configuredModel() {
        String type = AiConfig.getProperty("jmeter.ai.service.type", "openai").trim().toLowerCase(Locale.ROOT);
        String key = "anthropic".equals(type) ? "claude.default.model" : type + ".default.model";
        String model = AiConfig.getProperty(key, "");
        if (model == null || model.isBlank()) {
            return null;
        }
        return "anthropic".equals(type) || model.contains(":") ? model : type + ":" + model;
    }
}
