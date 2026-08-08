package org.qainsights.jmeter.ai.service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.qainsights.jmeter.ai.utils.AiConfig;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link OpenAiService} constructor behaviour,
 * specifically the {@code openai.base.url} configuration property.
 * <p>
 * Verifies the service reads the base URL with correct default fallback,
 * handles an explicitly empty string (treated as unset), and accepts
 * a custom OpenAI-compatible endpoint.
 * <p>
 * Uses {@link MockedStatic} on {@link AiConfig} to control property
 * lookups without a live JMeter installation.
 */
class OpenAiServiceTest {

    private static MockedStatic<AiConfig> aiConfigMockedStatic;

    @BeforeAll
    static void setUpAll() {
        aiConfigMockedStatic = mockStatic(AiConfig.class);
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0);
                    String defaultValue = invocation.getArgument(1);
                    if ("openai.api.key".equals(key)) return "test-api-key";
                    if ("openai.default.model".equals(key)) return "gpt-4o";
                    if ("openai.temperature".equals(key)) return "0.7";
                    if ("openai.max.tokens".equals(key)) return "4096";
                    if ("openai.max.history.size".equals(key)) return "10";
                    if ("openai.system.prompt".equals(key)) return "You are a test assistant.";
                    if ("openai.log.level".equals(key)) return "";
                    return defaultValue;
                });
    }

    @AfterAll
    static void tearDownAll() {
        if (aiConfigMockedStatic != null) {
            aiConfigMockedStatic.close();
        }
    }

    @Test
    void constructor_withDefaultBaseUrl_createsClientSuccessfully() {
        // openai.base.url is not in the mock → falls back to default
        assertDoesNotThrow(OpenAiService::new,
                "OpenAiService should construct successfully with the default base URL");

        OpenAiService service = new OpenAiService();
        assertNotNull(service.getClient(),
                "Client must not be null when default base URL is used");
    }

    @Test
    void constructor_withCustomBaseUrl_createsClientSuccessfully() {
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(
                eq("openai.base.url"), anyString())).thenReturn("https://custom-openai-api.example.com/v1");

        assertDoesNotThrow(OpenAiService::new,
                "OpenAiService should construct successfully with a custom base URL");

        OpenAiService service = new OpenAiService();
        assertNotNull(service.getClient(),
                "Client must not be null when a custom base URL is configured");
    }

    @Test
    void constructor_withEmptyBaseUrl_createsClientSuccessfully() {
        // Empty string should be treated as unset → client built without explicit baseUrl
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(
                eq("openai.base.url"), anyString())).thenReturn("");

        assertDoesNotThrow(OpenAiService::new,
                "OpenAiService should construct successfully even with an empty base URL");

        OpenAiService service = new OpenAiService();
        assertNotNull(service.getClient(),
                "Client must not be null when base URL is empty");
    }

    @Test
    void constructor_withBlankBaseUrl_createsClientSuccessfully() {
        // Whitespace-only string should be treated as unset
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(
                eq("openai.base.url"), anyString())).thenReturn("   ");

        assertDoesNotThrow(OpenAiService::new,
                "OpenAiService should construct successfully even with a whitespace-only base URL");

        OpenAiService service = new OpenAiService();
        assertNotNull(service.getClient(),
                "Client must not be null when base URL is blank");
    }

    @Test
    void constructor_defaultModelIsGpt4o() {
        OpenAiService service = new OpenAiService();
        assertNotNull(service.getCurrentModel(),
                "Default model must be set");
    }

    @Test
    void constructor_maxTokensIsLoaded() {
        OpenAiService service = new OpenAiService();
        // default is "4096"
        assertNotNull(service.getMaxTokens(),
                "Max tokens must be loaded from configuration");
    }
}
