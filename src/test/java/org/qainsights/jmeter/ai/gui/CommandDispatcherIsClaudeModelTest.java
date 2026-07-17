package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CommandDispatcher#isClaudeModel(String)}.
 * Verifies that only Anthropic Claude models (non-prefixed) return true,
 * and all prefixed providers (including bedrock:) return false.
 */
class CommandDispatcherIsClaudeModelTest {

    @Test
    void testNullModel_returnsTrue() {
        assertTrue(CommandDispatcher.isClaudeModel(null),
                "Null model should default to Claude (agent-capable)");
    }

    @Test
    void testEmptyModel_returnsTrue() {
        assertTrue(CommandDispatcher.isClaudeModel(""),
                "Empty model should default to Claude (agent-capable)");
    }

    @Test
    void testAnthropicModel_returnsTrue() {
        assertTrue(CommandDispatcher.isClaudeModel("claude-sonnet-4-20250514"),
                "Non-prefixed Anthropic model should be treated as Claude");
    }

    @Test
    void testOpenAiModel_returnsFalse() {
        assertFalse(CommandDispatcher.isClaudeModel("openai:gpt-4o"),
                "OpenAI model should not be treated as Claude");
    }

    @Test
    void testOllamaModel_returnsFalse() {
        assertFalse(CommandDispatcher.isClaudeModel("ollama:llama3.1"),
                "Ollama model should not be treated as Claude");
    }

    @Test
    void testDeepseekModel_returnsFalse() {
        assertFalse(CommandDispatcher.isClaudeModel("deepseek:deepseek-chat"),
                "DeepSeek model should not be treated as Claude");
    }

    @Test
    void testGoogleModel_returnsFalse() {
        assertFalse(CommandDispatcher.isClaudeModel("google:gemini-1.5"),
                "Google model should not be treated as Claude");
    }

    @Test
    void testGrokModel_returnsFalse() {
        assertFalse(CommandDispatcher.isClaudeModel("grok:grok-2"),
                "Grok model should not be treated as Claude");
    }

    @Test
    void testMetaModel_returnsFalse() {
        assertFalse(CommandDispatcher.isClaudeModel("meta:muse-spark-1.1"),
                "Meta model should not be treated as Claude");
    }

    @Test
    void testBedrockModel_returnsFalse() {
        assertFalse(CommandDispatcher.isClaudeModel("bedrock:anthropic.claude-3-5-sonnet-20241022-v2:0"),
                "Bedrock model should not be treated as Claude (agent mode deferred)");
    }
}
