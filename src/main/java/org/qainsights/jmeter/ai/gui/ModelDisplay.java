package org.qainsights.jmeter.ai.gui;

/**
 * Pure display-name utilities for prefixed model ids: splits a raw id into
 * friendly name and provider ({@code "openai:gpt-4o" -> ["gpt-4o", "OpenAI"]})
 * and formats the standard label ({@code "gpt-4o  ·  OpenAI"}). Only the
 * display changes - model routing keys off the original id strings
 * everywhere. (Successor of the old {@code ModelDisplayRenderer}; the Swing
 * cell-renderer half died with the combo box.)
 */
final class ModelDisplay {

    /** Prefix-to-provider display names, ordered longest-first for matching. */
    private static final String[][] PROVIDERS = {
        { "openai:", "OpenAI" },
        { "ollama:", "Ollama" },
        { "deepseek:", "DeepSeek" },
        { "google:", "Google" },
        { "grok:", "Grok" },
        { "meta:", "Meta" },
        { "bedrock:", "AWS Bedrock" },
        { "codex:", "ChatGPT / Codex" },
        { "claude-code:", "Claude Code" },
    };

    /** Unprefixed model ids route to Claude (see CommandDispatcher). */
    private static final String DEFAULT_PROVIDER = "Anthropic";

    private ModelDisplay() {
    }

    /**
     * Splits a raw model id into display name and provider, e.g.
     * {@code "openai:gpt-4o" -> ["gpt-4o", "OpenAI"]} and
     * {@code "claude-sonnet" -> ["claude-sonnet", "Anthropic"]}.
     */
    static String[] parse(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            return new String[] { "Loading available models\u2026", "" };
        }
        for (String[] entry : PROVIDERS) {
            if (modelId.startsWith(entry[0])) {
                return new String[] {
                    modelId.substring(entry[0].length()),
                    entry[1],
                };
            }
        }
        return new String[] { modelId, DEFAULT_PROVIDER };
    }

    /** The standard one-line label: {@code "gpt-4o  ·  OpenAI"}. */
    static String formatLabel(String modelId) {
        String[] parts = parse(modelId);
        return parts[1].isEmpty() ? parts[0] : parts[0] + "  ·  " + parts[1];
    }
}
