package org.qainsights.jmeter.ai.utils;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

class GatewayConfigTest {

    @Test
    void baseUrlsUseDefaultsWhenUnsetOrBlank() {
        try (MockedStatic<AiConfig> ignored = mockStatic(AiConfig.class)) {
            assertEquals(GatewayConfig.OPENAI_DEFAULT_BASE_URL, GatewayConfig.openAiBaseUrl());
            assertEquals(GatewayConfig.ANTHROPIC_DEFAULT_BASE_URL, GatewayConfig.anthropicBaseUrl());

            ignored.when(() -> AiConfig.getProperty("openai.base.url", GatewayConfig.OPENAI_DEFAULT_BASE_URL))
                    .thenReturn("   ");
            ignored.when(() -> AiConfig.getProperty("anthropic.base.url", GatewayConfig.ANTHROPIC_DEFAULT_BASE_URL))
                    .thenReturn("");
            assertEquals(GatewayConfig.OPENAI_DEFAULT_BASE_URL, GatewayConfig.openAiBaseUrl());
            assertEquals(GatewayConfig.ANTHROPIC_DEFAULT_BASE_URL, GatewayConfig.anthropicBaseUrl());
        }
    }

    @Test
    void customBaseUrlsAreTrimmedAndIdentifyGateways() {
        try (MockedStatic<AiConfig> ignored = mockStatic(AiConfig.class)) {
            ignored.when(() -> AiConfig.getProperty("openai.base.url", GatewayConfig.OPENAI_DEFAULT_BASE_URL))
                    .thenReturn("  https://openai.example/v1  ");
            ignored.when(() -> AiConfig.getProperty("anthropic.base.url", GatewayConfig.ANTHROPIC_DEFAULT_BASE_URL))
                    .thenReturn("https://anthropic.example");

            assertEquals("https://openai.example/v1", GatewayConfig.openAiBaseUrl());
            assertEquals("https://anthropic.example", GatewayConfig.anthropicBaseUrl());
            assertTrue(GatewayConfig.isOpenAiGateway());
            assertTrue(GatewayConfig.isAnthropicGateway());
        }
    }

    @Test
    void vendorDefaultsAreNotGateways() {
        try (MockedStatic<AiConfig> ignored = mockStatic(AiConfig.class)) {
            ignored.when(() -> AiConfig.getProperty("openai.base.url", GatewayConfig.OPENAI_DEFAULT_BASE_URL))
                    .thenReturn(GatewayConfig.OPENAI_DEFAULT_BASE_URL);
            ignored.when(() -> AiConfig.getProperty("anthropic.base.url", GatewayConfig.ANTHROPIC_DEFAULT_BASE_URL))
                    .thenReturn("  " + GatewayConfig.ANTHROPIC_DEFAULT_BASE_URL + " ");

            assertFalse(GatewayConfig.isOpenAiGateway());
            assertFalse(GatewayConfig.isAnthropicGateway());
        }
    }

    @Test
    void parseHeadersHandlesValuesWhitespaceMalformedEntriesAndTrailingSeparator() {
        assertEquals(Map.of("A", "1", "B", "2"), GatewayConfig.parseHeaders("A=1;B=2"));
        assertEquals(Map.of("X-Tok", "abc=def"), GatewayConfig.parseHeaders("X-Tok=abc=def"));
        assertEquals(Map.of("A", "1", "B", "two"), GatewayConfig.parseHeaders(" A = 1 ; B = two ;"));
        assertEquals(Map.of("C", "3"), GatewayConfig.parseHeaders("=novalue;justname;C=3"));
        assertEquals(Map.of(), GatewayConfig.parseHeaders(""));
    }

    @Test
    void parseModelsTrimsDropsBlanksAndPreservesOrder() {
        assertEquals(List.of("a", "b", "c"), GatewayConfig.parseModels("a, b ,,c"));
        assertEquals(List.of(), GatewayConfig.parseModels(""));
    }
}
