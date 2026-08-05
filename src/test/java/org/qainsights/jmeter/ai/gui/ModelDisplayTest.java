package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link ModelDisplay}: prefix parsing (incl. the Anthropic
 * default and the loading placeholder) and the standard label format.
 */
class ModelDisplayTest {

    @Test
    void parsesPrefixedModels() {
        assertArrayEquals(new String[] { "gpt-4o", "OpenAI" },
            ModelDisplay.parse("openai:gpt-4o"));
        assertArrayEquals(new String[] { "llama3.1", "Ollama" },
            ModelDisplay.parse("ollama:llama3.1"));
        assertArrayEquals(new String[] { "deepseek-chat", "DeepSeek" },
            ModelDisplay.parse("deepseek:deepseek-chat"));
        assertArrayEquals(new String[] { "gemini-2.5-flash", "Google" },
            ModelDisplay.parse("google:gemini-2.5-flash"));
        assertArrayEquals(new String[] { "grok-2", "Grok" },
            ModelDisplay.parse("grok:grok-2"));
        assertArrayEquals(new String[] { "llama-3", "Meta" },
            ModelDisplay.parse("meta:llama-3"));
        assertArrayEquals(new String[] { "claude-3-7", "AWS Bedrock" },
            ModelDisplay.parse("bedrock:claude-3-7"));
    }

    @Test
    void unprefixedModelsDefaultToAnthropic() {
        assertArrayEquals(new String[] { "claude-sonnet-4", "Anthropic" },
            ModelDisplay.parse("claude-sonnet-4"));
    }

    @Test
    void nullAndEmptyModelsShowLoadingPlaceholder() {
        assertArrayEquals(new String[] { "Loading available models\u2026", "" },
            ModelDisplay.parse(null));
        assertArrayEquals(new String[] { "Loading available models\u2026", "" },
            ModelDisplay.parse(""));
    }

    @Test
    void formatLabelJoinsNameAndProvider() {
        assertEquals("gpt-4o  ·  OpenAI", ModelDisplay.formatLabel("openai:gpt-4o"));
        assertEquals("claude-sonnet-4  ·  Anthropic", ModelDisplay.formatLabel("claude-sonnet-4"));
    }

    @Test
    void formatLabelFallsBackToNameOnly() {
        assertEquals("Loading available models\u2026", ModelDisplay.formatLabel(null));
    }
}
