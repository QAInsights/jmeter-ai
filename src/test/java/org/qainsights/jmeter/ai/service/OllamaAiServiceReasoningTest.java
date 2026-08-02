package org.qainsights.jmeter.ai.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.qainsights.jmeter.ai.service.reasoning.ReasoningSettings;
import org.qainsights.jmeter.ai.utils.AiConfig;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for the reasoning surface of {@link OllamaAiService}: settings
 * injection (UI overrides property defaults), the non-streaming reasoning
 * consume contract, and the 6-arg streaming overload.
 */
class OllamaAiServiceReasoningTest {

    private MockedStatic<AiConfig> aiConfigMockedStatic;
    private OllamaAiService service;

    @BeforeEach
    void setUp() {
        aiConfigMockedStatic = mockStatic(AiConfig.class);
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0);
                    if (key.equals("ollama.thinking.mode")) return "ENABLED";
                    if (key.equals("ollama.thinking.level")) return "LOW";
                    return invocation.getArgument(1);
                });
        service = new OllamaAiService();
    }

    @AfterEach
    void tearDown() {
        aiConfigMockedStatic.close();
    }

    @Test
    void consumeLastReasoningIsNullBeforeAnyResponse() {
        assertNull(service.consumeLastReasoning());
    }

    @Test
    void setReasoningSettingsIsAccepted() {
        assertDoesNotThrow(() -> service.setReasoningSettings(
                new ReasoningSettings(true, "high")));
    }

    @Test
    void propertyDefaultsApplyWhenUiUntouched() {
        // constructor mocked ollama.thinking.mode=ENABLED, level=LOW
        service.setReasoningSettings(new ReasoningSettings(false, "high"));
        assertTrue(service.isThinkingEnabled(),
                "untouched UI must fall back to the ollama.thinking.mode property");
        assertEquals(io.github.ollama4j.models.request.ThinkMode.LOW, service.effectiveThinkingMode(),
                "untouched UI must fall back to the ollama.thinking.level property");
    }

    @Test
    void uiChoicesWinOnceTouched() {
        ReasoningSettings settings = new ReasoningSettings(false, "high");
        settings.userSetThinkingEnabled(false);
        settings.userSetEffort("high");
        service.setReasoningSettings(settings);
        assertFalse(service.isThinkingEnabled(),
                "a deliberate toggle-off must override ollama.thinking.mode=ENABLED");
        assertEquals(io.github.ollama4j.models.request.ThinkMode.HIGH, service.effectiveThinkingMode());
    }

    @Test
    void noSettingsUsesPropertyDefaults() {
        assertTrue(service.isThinkingEnabled());
        assertEquals(io.github.ollama4j.models.request.ThinkMode.LOW, service.effectiveThinkingMode());
    }

    @Test
    void sixArgStreamOverloadReturnsCancelHandle() {
        // No Ollama server is running; the call must still return a handle
        // instead of throwing (the worker thread errors asynchronously).
        Runnable cancel = service.generateStreamResponse(
                Collections.singletonList("hi"), "deepseek-r1:1.5b",
                token -> {}, reasoning -> {}, () -> {}, e -> {});
        assertNotNull(cancel);
        cancel.run();
    }

    // ==================== Live capability probe ====================

    /** Service with a stubbed /api/show seam (no server needed). */
    private OllamaAiService serviceWithCapabilities(String[] capabilities) {
        return new OllamaAiService() {
            @Override
            String[] fetchCapabilities(String model) {
                return capabilities;
            }
        };
    }

    @Test
    void probeIsEmptyBeforeResolution() {
        assertTrue(service.probeThinkingCapability("qwen3:8b").isEmpty());
        assertTrue(service.probeThinkingCapability(null).isEmpty());
    }

    @Test
    void resolveDetectsThinkingCapability() throws Exception {
        OllamaAiService probing = serviceWithCapabilities(
                new String[]{"completion", "tools", "thinking"});
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        probing.resolveThinkingCapability("qwen3:8b", latch::countDown);

        assertTrue(latch.await(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(java.util.Optional.of(true), probing.probeThinkingCapability("qwen3:8b"));
    }

    @Test
    void resolveDetectsMissingThinkingCapability() throws Exception {
        OllamaAiService probing = serviceWithCapabilities(new String[]{"completion"});
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        probing.resolveThinkingCapability("llama3.1", latch::countDown);

        assertTrue(latch.await(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(java.util.Optional.of(false), probing.probeThinkingCapability("llama3.1"));
    }

    @Test
    void resolveCachesFailureAsUnsupported() throws Exception {
        OllamaAiService probing = new OllamaAiService() {
            @Override
            String[] fetchCapabilities(String model) {
                throw new RuntimeException("server down");
            }
        };
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        probing.resolveThinkingCapability("gone", latch::countDown);

        assertTrue(latch.await(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(java.util.Optional.of(false), probing.probeThinkingCapability("gone"));
    }
}
