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
 * Unit tests for the reasoning surface of {@link GrokAiService}: settings
 * injection, the non-streaming reasoning consume contract, and the 6-arg
 * streaming overload.
 */
class GrokAiServiceReasoningTest {

    private MockedStatic<AiConfig> aiConfigMockedStatic;
    private GrokAiService service;

    @BeforeEach
    void setUp() {
        aiConfigMockedStatic = mockStatic(AiConfig.class);
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0);
                    if (key.equals("grok.api.key")) return "";
                    return invocation.getArgument(1);
                });
        service = new GrokAiService();
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
                new ReasoningSettings(false, "low")));
    }

    @Test
    void sixArgStreamOverloadReturnsCancelHandleWithoutClient() {
        // No API key -> null client -> no-op handle, never throws
        Runnable cancel = service.generateStreamResponse(
                Collections.singletonList("hi"), "grok-3-mini",
                token -> {}, reasoning -> {}, () -> {}, e -> {});
        assertNotNull(cancel);
        assertDoesNotThrow(cancel::run);
    }
}
