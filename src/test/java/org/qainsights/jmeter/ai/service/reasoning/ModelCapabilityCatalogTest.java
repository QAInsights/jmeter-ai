package org.qainsights.jmeter.ai.service.reasoning;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ModelCapabilityCatalog}: provider mapping, key
 * resolution (exact, Bedrock region suffix, longest-prefix for dated/variant
 * ids), the rich {@link ModelCapabilityCatalog.CapabilityInfo} fields, and the
 * classpath-loaded vendored models.dev file.
 */
class ModelCapabilityCatalogTest {

    @AfterEach
    void tearDown() {
        ModelCapabilityCatalog.resetInstanceForTest();
    }

    private static ModelCapabilityCatalog.CapabilityInfo caps(
            boolean reasoning, boolean toggleable, List<String> effort,
            long budgetMin, long budgetMax, boolean vision, boolean pdf) {
        return new ModelCapabilityCatalog.CapabilityInfo(
                reasoning, toggleable, effort, budgetMin, budgetMax, vision, pdf, 0, 0, 0);
    }

    private static ModelCapabilityCatalog synthetic() {
        return new ModelCapabilityCatalog(Map.of(
                "anthropic", Map.of(
                        "claude-opus-4-8", caps(true, false,
                                List.of("low", "medium", "high", "xhigh", "max"), 0, 0, true, true)),
                "openai", Map.of(
                        "gpt-5.1", caps(true, false, List.of("none", "low", "medium", "high"),
                                0, 0, true, false),
                        "gpt-4o", caps(false, false, List.of(), 0, 0, true, true)),
                "xai", Map.of(
                        "grok-4.5", caps(true, false, List.of("low", "medium", "high"),
                                0, 0, true, true)),
                "amazon-bedrock", Map.of(
                        "global.anthropic.claude-haiku-4-5-20251001-v1:0",
                        caps(true, false, List.of(), 1024, 0, true, true))));
    }

    @Test
    void providerPrefixMapping() {
        assertEquals("anthropic", ModelCapabilityCatalog.catalogProviderOf("claude-opus-4-8"));
        assertEquals("openai", ModelCapabilityCatalog.catalogProviderOf("openai:gpt-5"));
        assertEquals("google", ModelCapabilityCatalog.catalogProviderOf("google:gemini-2.5-pro"));
        assertEquals("xai", ModelCapabilityCatalog.catalogProviderOf("grok:grok-4.5"));
        assertEquals("deepseek", ModelCapabilityCatalog.catalogProviderOf("deepseek:deepseek-reasoner"));
        assertEquals("amazon-bedrock", ModelCapabilityCatalog.catalogProviderOf("bedrock:anthropic.claude-opus-4-8"));
        assertEquals("meta", ModelCapabilityCatalog.catalogProviderOf("meta:muse-spark-1.1"));
        assertNull(ModelCapabilityCatalog.catalogProviderOf("ollama:qwen3:8b"));
        assertNull(ModelCapabilityCatalog.catalogProviderOf(null));
    }

    @Test
    void exactLookupReturnsRichCapabilities() {
        Optional<ModelCapabilityCatalog.CapabilityInfo> caps =
                synthetic().capabilities("claude-opus-4-8");
        assertTrue(caps.isPresent());
        assertTrue(caps.get().isReasoning());
        assertFalse(caps.get().isToggleable());
        assertEquals(List.of("low", "medium", "high", "xhigh", "max"), caps.get().getEffortLevels());
        assertFalse(caps.get().hasBudget());
        assertTrue(caps.get().isVision());
        assertTrue(caps.get().isPdf());
    }

    @Test
    void budgetRangeIsExposed() {
        ModelCapabilityCatalog.CapabilityInfo caps = synthetic()
                .capabilities("bedrock:anthropic.claude-haiku-4-5-20251001-v1:0").orElseThrow();
        assertTrue(caps.hasBudget());
        assertEquals(1024, caps.getBudgetMin());
        assertEquals(0, caps.getBudgetMax());
    }

    @Test
    void costAndContextAreExposed() {
        ModelCapabilityCatalog catalog = new ModelCapabilityCatalog(Map.of(
                "openai", Map.of(
                        "gpt-5.1", new ModelCapabilityCatalog.CapabilityInfo(
                                true, false, List.of("low"), 0, 0, true, false,
                                400_000, 1.25, 10.0),
                        "gpt-3.5-turbo", new ModelCapabilityCatalog.CapabilityInfo(
                                false, false, List.of(), 0, 0, false, false,
                                16_385, 0.5, 1.5))));
        ModelCapabilityCatalog.CapabilityInfo gpt51 =
                catalog.capabilities("openai:gpt-5.1").orElseThrow();
        assertEquals(400_000, gpt51.getContextWindow());
        assertEquals(1.25, gpt51.getCostIn(), 0.0001);
        assertEquals(10.0, gpt51.getCostOut(), 0.0001);
        assertTrue(gpt51.hasCost());

        ModelCapabilityCatalog.CapabilityInfo legacy =
                catalog.capabilities("openai:gpt-3.5-turbo").orElseThrow();
        assertEquals(16_385, legacy.getContextWindow());
        assertTrue(legacy.hasCost());
    }

    @Test
    void entriesWithoutCostOrContextReadAsZero() {
        ModelCapabilityCatalog.CapabilityInfo caps = synthetic()
                .capabilities("claude-opus-4-8").orElseThrow();
        assertEquals(0, caps.getContextWindow());
        assertEquals(0, caps.getCostIn(), 0.0001);
        assertEquals(0, caps.getCostOut(), 0.0001);
        assertFalse(caps.hasCost());
    }

    @Test
    void visionIndependentOfReasoning() {
        ModelCapabilityCatalog catalog = synthetic();
        assertTrue(catalog.supportsVision("openai:gpt-4o"));
        assertFalse(catalog.supportsReasoning("openai:gpt-4o"));
    }

    @Test
    void datedVariantsMatchUndatedKeys() {
        ModelCapabilityCatalog catalog = synthetic();
        assertTrue(catalog.supportsReasoning("claude-opus-4-8-20251101"));
        assertTrue(catalog.supportsReasoning("openai:gpt-5.1-codex"));
    }

    @Test
    void longestPrefixWinsWithinProvider() {
        ModelCapabilityCatalog catalog = new ModelCapabilityCatalog(Map.of(
                "openai", Map.of(
                        "gpt-5", caps(true, false, List.of("minimal"), 0, 0, false, false),
                        "gpt-5.1", caps(false, false, List.of(), 0, 0, true, false))));
        // exact hit
        assertFalse(catalog.supportsReasoning("openai:gpt-5.1"));
        // variant resolves via the longest prefix (gpt-5.1, not gpt-5)
        assertFalse(catalog.supportsReasoning("openai:gpt-5.1-codex"));
    }

    @Test
    void unknownModelsReportEmpty() {
        ModelCapabilityCatalog catalog = synthetic();
        assertTrue(catalog.capabilities("openai:gpt-9-turbo").isEmpty());
        assertTrue(catalog.capabilities("ollama:qwen3:8b").isEmpty());
        assertTrue(catalog.capabilities(null).isEmpty());
        assertTrue(catalog.capabilities("").isEmpty());
        assertFalse(catalog.supportsReasoning("claude-2.1"));
    }

    @Test
    void vendoredFileLoadsFromClasspath() {
        ModelCapabilityCatalog catalog = ModelCapabilityCatalog.getInstance();
        // The real vendored models.dev file is on the test classpath
        assertTrue(catalog.size() > 150, "vendored catalog should contain over a hundred models");
        assertTrue(catalog.supportsReasoning("claude-fable-5"));
        assertTrue(catalog.supportsReasoning("openai:o3"));
        assertTrue(catalog.supportsReasoning("google:gemini-2.5-pro"));
        assertTrue(catalog.supportsReasoning("grok:grok-4.5"));
        assertTrue(catalog.supportsReasoning("deepseek:deepseek-reasoner"));
        assertFalse(catalog.supportsReasoning("openai:gpt-4o"));
        assertTrue(catalog.supportsVision("openai:gpt-4o"));
        // Rich fields from the real data
        ModelCapabilityCatalog.CapabilityInfo gpt51 =
                catalog.capabilities("openai:gpt-5.1").orElseThrow();
        assertEquals(List.of("none", "low", "medium", "high"), gpt51.getEffortLevels());
        ModelCapabilityCatalog.CapabilityInfo flash =
                catalog.capabilities("google:gemini-2.5-flash").orElseThrow();
        assertTrue(flash.isToggleable());
        assertTrue(flash.hasBudget());
        // Cost/context from the real data (regenerated 2026-08-03 trim)
        assertEquals(400_000, gpt51.getContextWindow());
        assertEquals(1.25, gpt51.getCostIn(), 0.0001);
        assertEquals(10.0, gpt51.getCostOut(), 0.0001);
        assertTrue(gpt51.hasCost());
        // Models without any capability are kept too (metadata-only entries)
        assertTrue(catalog.capabilities("google:gemini-2.5-flash-preview-tts").isPresent());
    }
}
