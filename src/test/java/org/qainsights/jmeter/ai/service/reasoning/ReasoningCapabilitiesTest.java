package org.qainsights.jmeter.ai.service.reasoning;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.qainsights.jmeter.ai.utils.AiConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link ReasoningCapabilities}: provider prefix parsing,
 * data-driven thinking-toggle and effort-level detection (backed by the real
 * vendored models.dev file on the test classpath), the Ollama probe, and the
 * Anthropic budget mapping (including property overrides).
 */
class ReasoningCapabilitiesTest {

    private static MockedStatic<AiConfig> aiConfigMockedStatic;

    @BeforeAll
    static void setUpAll() {
        aiConfigMockedStatic = mockStatic(AiConfig.class);
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    @AfterAll
    static void tearDownAll() {
        if (aiConfigMockedStatic != null) {
            aiConfigMockedStatic.close();
        }
        ReasoningCapabilities.setOllamaThinkingProbe(null);
    }

    // ==================== Thinking toggle ====================

    @ParameterizedTest
    @ValueSource(strings = {
            "claude-sonnet-4-6",
            "claude-opus-4-8",
            "claude-haiku-4-5",
            "claude-fable-5",
            "claude-opus-5"
    })
    void testSupportsThinkingToggle_anthropicThinkingModels(String model) {
        assertTrue(ReasoningCapabilities.supportsThinkingToggle(model),
                "expected thinking toggle for " + model);
    }

    @Test
    void testSupportsThinkingToggle_unknownModelsHidden() {
        // Absent from the vendored catalog with no family prefix -> hidden.
        // (models.dev tracks the current lineup only: retired models like
        // claude-3-5/3-7 are absent and hide their controls.)
        assertFalse(ReasoningCapabilities.supportsThinkingToggle("claude-3-7-sonnet-20250219"));
        assertFalse(ReasoningCapabilities.supportsThinkingToggle("claude-3-5-sonnet-20241022"));
        assertFalse(ReasoningCapabilities.supportsThinkingToggle("openai:gpt-9-turbo"));
        assertFalse(ReasoningCapabilities.supportsThinkingToggle("google:gemini-9-ultra"));
        assertFalse(ReasoningCapabilities.supportsThinkingToggle(null));
        assertFalse(ReasoningCapabilities.supportsThinkingToggle(""));
    }

    @Test
    void testVariantsInheritFamilyCapability() {
        // Dated/variant ids from live APIs resolve via longest-prefix
        assertTrue(ReasoningCapabilities.supportsThinkingToggle("claude-opus-4-8-20251101"));
        // a future gpt-5.9 inherits gpt-5's data: reasoning without a "none"
        // level -> effort dropdown but no toggle
        assertFalse(ReasoningCapabilities.supportsThinkingToggle("openai:gpt-5.9-quasar"));
        assertEquals(List.of("minimal", "low", "medium", "high"),
                ReasoningCapabilities.effortLevels("openai:gpt-5.9-quasar"));
    }

    @Test
    void testSupportsThinkingToggle_openAiToggleableOnlyWithNoneEffort() {
        // gpt-5.x accepts "none" -> toggleable; o-series and gpt-5 always reason
        assertTrue(ReasoningCapabilities.supportsThinkingToggle("openai:gpt-5.1"));
        assertFalse(ReasoningCapabilities.supportsThinkingToggle("openai:gpt-5"));
        assertFalse(ReasoningCapabilities.supportsThinkingToggle("openai:o3"));
        assertFalse(ReasoningCapabilities.supportsThinkingToggle("openai:o4-mini"));
        assertFalse(ReasoningCapabilities.supportsThinkingToggle("openai:gpt-4o"));
    }

    @Test
    void testSupportsThinkingToggle_googleToggleFlagFromData() {
        // Gemini Flash carries an explicit toggle; Pro thinks unconditionally
        assertTrue(ReasoningCapabilities.supportsThinkingToggle("google:gemini-2.5-flash"));
        assertFalse(ReasoningCapabilities.supportsThinkingToggle("google:gemini-2.5-pro"));
        assertFalse(ReasoningCapabilities.supportsThinkingToggle("google:gemini-3-pro-preview"));
        assertFalse(ReasoningCapabilities.supportsThinkingToggle("google:gemma-3-27b-it"));
    }

    @Test
    void testSupportsThinkingToggle_ollamaOptimisticDefault() {
        assertTrue(ReasoningCapabilities.supportsThinkingToggle("ollama:deepseek-r1:1.5b"));
        assertTrue(ReasoningCapabilities.supportsThinkingToggle("ollama:qwen3:8b"));
    }

    @Test
    void testOllamaProbeOverridesOptimisticDefault() {
        try {
            // Probe says the local model cannot think -> controls hide
            ReasoningCapabilities.setOllamaThinkingProbe(model -> java.util.Optional.of(false));
            assertFalse(ReasoningCapabilities.supportsThinkingToggle("ollama:llama3.1"));
            assertTrue(ReasoningCapabilities.effortLevels("ollama:llama3.1").isEmpty());

            // Probe says it can think -> controls show
            ReasoningCapabilities.setOllamaThinkingProbe(model -> java.util.Optional.of(true));
            assertTrue(ReasoningCapabilities.supportsThinkingToggle("ollama:qwen3:8b"));
            assertEquals(List.of("low", "medium", "high"),
                    ReasoningCapabilities.effortLevels("ollama:qwen3:8b"));

            // Probe has no answer yet -> optimistic default (shown)
            ReasoningCapabilities.setOllamaThinkingProbe(model -> java.util.Optional.empty());
            assertTrue(ReasoningCapabilities.supportsThinkingToggle("ollama:qwen3:8b"));
        } finally {
            ReasoningCapabilities.setOllamaThinkingProbe(null);
        }
    }

    @Test
    void testSupportsThinkingToggle_bedrockClaude() {
        assertTrue(ReasoningCapabilities.supportsThinkingToggle("bedrock:anthropic.claude-opus-4-6-v1"));
        assertTrue(ReasoningCapabilities.supportsThinkingToggle("bedrock:anthropic.claude-fable-5"));
        assertFalse(ReasoningCapabilities.supportsThinkingToggle("bedrock:amazon.nova-pro-v1:0"));
        assertFalse(ReasoningCapabilities.supportsThinkingToggle("bedrock:anthropic.claude-3-5-sonnet-20241022-v2:0"));
    }

    @Test
    void testSupportsThinkingToggle_bedrockNovaAndOpenAiFamilies() {
        // Nova 2 carries an explicit toggle; gpt-5.x on Bedrock accepts "none"
        assertTrue(ReasoningCapabilities.supportsThinkingToggle("bedrock:amazon.nova-2-lite-v1:0"));
        assertTrue(ReasoningCapabilities.supportsThinkingToggle("bedrock:global.amazon.nova-2-lite-v1:0"));
        assertTrue(ReasoningCapabilities.supportsThinkingToggle("bedrock:openai.gpt-5.4"));
        // gpt-oss always reasons (no "none") - no toggle
        assertFalse(ReasoningCapabilities.supportsThinkingToggle("bedrock:openai.gpt-oss-120b-1:0"));
        // display-only families
        assertFalse(ReasoningCapabilities.supportsThinkingToggle("bedrock:deepseek.v3.2"));
    }

    @Test
    void testEffortLevels_bedrockFamilies() {
        assertEquals(List.of("low", "medium", "high"),
                ReasoningCapabilities.effortLevels("bedrock:openai.gpt-oss-120b-1:0"));
        assertEquals(List.of("low", "medium", "high"),
                ReasoningCapabilities.effortLevels("bedrock:amazon.nova-2-lite-v1:0"));
        assertEquals(List.of("none", "low", "medium", "high", "xhigh"),
                ReasoningCapabilities.effortLevels("bedrock:openai.gpt-5.4"));
        assertEquals(List.of("low", "medium", "high", "max"),
                ReasoningCapabilities.effortLevels("bedrock:anthropic.claude-sonnet-4-6"));
        assertTrue(ReasoningCapabilities.effortLevels("bedrock:deepseek.v3.2").isEmpty());
    }

    @Test
    void testBedrockRegionStrippingAndFamily() {
        assertEquals("anthropic.claude-opus-4-8",
                ReasoningCapabilities.stripBedrockRegion("us.anthropic.claude-opus-4-8"));
        assertEquals("amazon.nova-2-lite-v1:0",
                ReasoningCapabilities.stripBedrockRegion("global.amazon.nova-2-lite-v1:0"));
        assertEquals("anthropic", ReasoningCapabilities.bedrockFamily("anthropic.claude-fable-5"));
        assertEquals("amazon", ReasoningCapabilities.bedrockFamily("global.amazon.nova-2-lite-v1:0"));
        assertEquals("openai", ReasoningCapabilities.bedrockFamily("openai.gpt-oss-20b-1:0"));
        assertEquals("deepseek", ReasoningCapabilities.bedrockFamily("deepseek.v3.2"));
    }

    @Test
    void testSupportsThinkingToggle_grokDeepseekMetaNeverToggleable() {
        // reasoning-capable but always on (or display-only) -> no toggle
        assertFalse(ReasoningCapabilities.supportsThinkingToggle("grok:grok-4.5"));
        assertFalse(ReasoningCapabilities.supportsThinkingToggle("deepseek:deepseek-reasoner"));
        assertFalse(ReasoningCapabilities.supportsThinkingToggle("meta:muse-large"));
    }

    // ==================== Effort levels (straight from the catalog) ====================

    @Test
    void testEffortLevels_anthropic() {
        assertEquals(List.of("low", "medium", "high", "max"),
                ReasoningCapabilities.effortLevels("claude-sonnet-4-6"));
        assertEquals(List.of("low", "medium", "high", "xhigh", "max"),
                ReasoningCapabilities.effortLevels("claude-opus-4-8"));
        assertEquals(List.of("low", "medium", "high", "xhigh", "max"),
                ReasoningCapabilities.effortLevels("claude-fable-5"));
        assertTrue(ReasoningCapabilities.effortLevels("claude-3-5-haiku-20241022").isEmpty());
    }

    @Test
    void testEffortLevels_openAi() {
        assertEquals(List.of("none", "low", "medium", "high"),
                ReasoningCapabilities.effortLevels("openai:gpt-5.1"));
        assertEquals(List.of("minimal", "low", "medium", "high"),
                ReasoningCapabilities.effortLevels("openai:gpt-5"));
        assertEquals(List.of("low", "medium", "high"),
                ReasoningCapabilities.effortLevels("openai:o3"));
        assertTrue(ReasoningCapabilities.effortLevels("openai:gpt-4o").isEmpty());
    }

    @Test
    void testEffortLevels_googleOllamaGrok() {
        // Budget-shaped Gemini 2.5 gets the synthetic LMH labels
        assertEquals(List.of("low", "medium", "high"),
                ReasoningCapabilities.effortLevels("google:gemini-2.5-pro"));
        // Gemini 3 has named levels
        assertEquals(List.of("low", "high"),
                ReasoningCapabilities.effortLevels("google:gemini-3-pro-preview"));
        assertEquals(List.of("low", "medium", "high"),
                ReasoningCapabilities.effortLevels("ollama:qwen3:8b"));
        assertEquals(List.of("low", "medium", "high"),
                ReasoningCapabilities.effortLevels("grok:grok-4.5"));
        assertTrue(ReasoningCapabilities.effortLevels("grok:grok-4").isEmpty());
    }

    @Test
    void testEffortLevels_bedrockAndDisplayOnly() {
        assertEquals(List.of("low", "medium", "high", "xhigh", "max"),
                ReasoningCapabilities.effortLevels("bedrock:anthropic.claude-opus-4-8"));
        assertTrue(ReasoningCapabilities.effortLevels("deepseek:deepseek-reasoner").isEmpty());
        assertTrue(ReasoningCapabilities.effortLevels(null).isEmpty());
        assertTrue(ReasoningCapabilities.effortLevels("meta:muse-large").isEmpty());
    }

    // ==================== Budgets ====================

    @Test
    void testAnthropicBudgetTokens_defaults() {
        assertEquals(2048, ReasoningCapabilities.anthropicBudgetTokens("low"));
        assertEquals(8192, ReasoningCapabilities.anthropicBudgetTokens("medium"));
        assertEquals(16384, ReasoningCapabilities.anthropicBudgetTokens("high"));
        assertEquals(24576, ReasoningCapabilities.anthropicBudgetTokens("xhigh"));
        assertEquals(32768, ReasoningCapabilities.anthropicBudgetTokens("max"));
    }

    @Test
    void testAnthropicBudgetTokens_unknownFallsBackToMedium() {
        assertEquals(8192, ReasoningCapabilities.anthropicBudgetTokens("extreme"));
        assertEquals(8192, ReasoningCapabilities.anthropicBudgetTokens(null));
        assertEquals(8192, ReasoningCapabilities.anthropicBudgetTokens(""));
    }

    @Test
    void testAnthropicBudgetTokens_propertyOverride() {
        aiConfigMockedStatic.when(() -> AiConfig.getProperty("anthropic.thinking.budget.high", "16384"))
                .thenReturn("32000");
        assertEquals(32000, ReasoningCapabilities.anthropicBudgetTokens("high"));
        // invalid override falls back to the default for that level
        aiConfigMockedStatic.when(() -> AiConfig.getProperty("anthropic.thinking.budget.low", "2048"))
                .thenReturn("not-a-number");
        assertEquals(2048, ReasoningCapabilities.anthropicBudgetTokens("low"));
    }

    @Test
    void testGoogleThinkingBudget() {
        assertEquals(1024, ReasoningCapabilities.googleThinkingBudget("low"));
        assertEquals(8192, ReasoningCapabilities.googleThinkingBudget("medium"));
        assertEquals(24576, ReasoningCapabilities.googleThinkingBudget("high"));
        assertEquals(8192, ReasoningCapabilities.googleThinkingBudget("bogus"));
        assertEquals(8192, ReasoningCapabilities.googleThinkingBudget(null));
    }

    // ==================== Prefix parsing & shape helpers ====================

    @Test
    void testProviderAndBareModelParsing() {
        assertEquals("openai", ReasoningCapabilities.providerOf("openai:gpt-5"));
        assertEquals("anthropic", ReasoningCapabilities.providerOf("claude-sonnet-4-6"));
        assertEquals("", ReasoningCapabilities.providerOf(null));
        assertEquals("gpt-5", ReasoningCapabilities.bareModelId("openai:gpt-5"));
        assertEquals("claude-sonnet-4-6", ReasoningCapabilities.bareModelId("claude-sonnet-4-6"));
        assertEquals("", ReasoningCapabilities.bareModelId(null));
    }

    @Test
    void testCaseInsensitiveMatching() {
        assertTrue(ReasoningCapabilities.supportsThinkingToggle("OpenAI:gpt-5.1"));
        assertEquals(List.of("low", "medium", "high", "max"),
                ReasoningCapabilities.effortLevels("Claude-Sonnet-4-6"));
    }

    @Test
    void testAdaptiveClaudeDetection() {
        assertTrue(ReasoningCapabilities.isAdaptiveClaude("claude-fable-5"));
        assertTrue(ReasoningCapabilities.isAdaptiveClaude("claude-sonnet-5"));
        assertFalse(ReasoningCapabilities.isAdaptiveClaude("claude-opus-4-8"));
        assertFalse(ReasoningCapabilities.isAdaptiveClaude(null));
    }
}
