package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CommandDispatcher#isAgentCapableModel(String)}.
 * Agent mode supports providers with tool-calling adapters - Anthropic Claude
 * (non-prefixed ids), OpenAI ({@code openai:} prefix), Google Gemini
 * ({@code google:} prefix), DeepSeek, Grok, and Meta Muse; everything else
 * falls back to the plain chat path.
 */
class CommandDispatcherIsAgentCapableModelTest {

    @Test
    void testNullModel_returnsTrue() {
        assertTrue(CommandDispatcher.isAgentCapableModel(null),
                "Null model defaults to Claude, which is agent-capable");
    }

    @Test
    void testEmptyModel_returnsTrue() {
        assertTrue(CommandDispatcher.isAgentCapableModel(""),
                "Empty model defaults to Claude, which is agent-capable");
    }

    @Test
    void testAnthropicModel_returnsTrue() {
        assertTrue(CommandDispatcher.isAgentCapableModel("claude-sonnet-4-20250514"));
    }

    @Test
    void testOpenAiModel_returnsTrue() {
        assertTrue(CommandDispatcher.isAgentCapableModel("openai:gpt-4o"),
                "OpenAI now has a tool-calling adapter");
    }

    @Test
    void testOpenAiReasoningModel_returnsTrue() {
        assertTrue(CommandDispatcher.isAgentCapableModel("openai:o3-mini"));
    }

    @Test
    void testOllamaModel_returnsFalse() {
        assertFalse(CommandDispatcher.isAgentCapableModel("ollama:llama3.1"));
    }

    @Test
    void testDeepseekModel_returnsTrue() {
        assertTrue(CommandDispatcher.isAgentCapableModel("deepseek:deepseek-chat"));
    }

    @Test
    void testGoogleModel_returnsTrue() {
        assertTrue(CommandDispatcher.isAgentCapableModel("google:gemini-2.5-flash"),
                "Google Gemini now has a tool-calling adapter");
    }

    @Test
    void testGrokModel_returnsTrue() {
        assertTrue(CommandDispatcher.isAgentCapableModel("grok:grok-2"));
    }

    @Test
    void testMetaModel_returnsTrue() {
        assertTrue(CommandDispatcher.isAgentCapableModel("meta:muse-spark-1.1"));
    }

    @Test
    void testBedrockModel_returnsFalse() {
        assertFalse(CommandDispatcher.isAgentCapableModel("bedrock:anthropic.claude-3-5-sonnet"),
                "Bedrock has no tool-calling adapter yet");
    }

    @Test
    void chatSelectionNeverUsesAgent() {
        assertFalse(CommandDispatcher.shouldUseAgent(true, false, "openai:gpt-4o"));
    }

    @Test
    void agentSelectionUsesAgentForCapableModel() {
        assertTrue(CommandDispatcher.shouldUseAgent(true, true, "openai:gpt-4o"));
    }

    @Test
    void disabledFeatureAndUnsupportedModelsStayInChat() {
        assertFalse(CommandDispatcher.shouldUseAgent(false, true, "openai:gpt-4o"));
        assertFalse(CommandDispatcher.shouldUseAgent(true, true, "ollama:llama3.1"));
    }
}
