package org.qainsights.jmeter.ai.service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.qainsights.jmeter.ai.service.reasoning.ReasoningSettings;
import org.qainsights.jmeter.ai.utils.AiConfig;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for the reasoning surface of {@link ClaudeService}: settings
 * injection and the non-streaming reasoning consume contract.
 */
class ClaudeServiceReasoningTest {

    private static MockedStatic<AiConfig> aiConfigMockedStatic;
    private ClaudeService claudeService;

    @BeforeAll
    static void setUpAll() {
        aiConfigMockedStatic = mockStatic(AiConfig.class);
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0);
                    if (key.equals("anthropic.api.key")) return "test-api-key";
                    if (key.equals("anthropic.log.level")) return "";
                    return invocation.getArgument(1);
                });
    }

    @AfterAll
    static void tearDownAll() {
        if (aiConfigMockedStatic != null) {
            aiConfigMockedStatic.close();
        }
    }

    @BeforeEach
    void setUp() {
        claudeService = new ClaudeService();
    }

    @Test
    void consumeLastReasoningIsNullBeforeAnyResponse() {
        assertNull(claudeService.consumeLastReasoning());
    }

    @Test
    void setReasoningSettingsIsAccepted() {
        assertDoesNotThrow(() -> claudeService.setReasoningSettings(
                new ReasoningSettings(true, "high")));
    }

    @Test
    void setReasoningSettingsAcceptsNull() {
        claudeService.setReasoningSettings(new ReasoningSettings(true, "high"));
        assertDoesNotThrow(() -> claudeService.setReasoningSettings(null));
    }
}
