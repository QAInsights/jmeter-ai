package org.qainsights.jmeter.ai.agent;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.qainsights.jmeter.ai.service.LocalEmbeddingModel;
import org.qainsights.jmeter.ai.service.OllamaEmbeddingClient;
import org.qainsights.jmeter.ai.service.TypeSafeJudgmentProvider;
import org.qainsights.jmeter.ai.utils.AiConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

class AgentRoutingConfigTest {

    @Test
    void flagsDefaultOffAndCreateNoRouter() {
        try (MockedStatic<AiConfig> config = defaults()) {
            assertFalse(AgentRoutingConfig.enabled());
            assertNull(AgentRoutingConfig.createRouter());
        }
    }

    @Test
    void bothFlagsWithMissingOrPlaceholderKeyCreateNoRouter() {
        try (MockedStatic<AiConfig> config = defaults()) {
            config.when(() -> AiConfig.getProperty(AgentRoutingConfig.TYPESAFE_ENABLED_KEY, "false"))
                    .thenReturn("true");
            config.when(() -> AiConfig.getProperty(AgentRoutingConfig.ROUTING_ENABLED_KEY, "false"))
                    .thenReturn("true");

            assertTrue(AgentRoutingConfig.enabled());
            assertNull(AgentRoutingConfig.createRouter());

            config.when(() -> AiConfig.getProperty(AgentRoutingConfig.API_KEY, ""))
                    .thenReturn("YOUR_TYPESAFE_API_KEY");
            assertNull(AgentRoutingConfig.createRouter());
        }
    }

    @Test
    void validKeyCreatesTypeSafeRouterAndInvalidUrlFallsBackToUnavailable() {
        try (MockedStatic<AiConfig> config = defaults()) {
            config.when(() -> AiConfig.getProperty(AgentRoutingConfig.TYPESAFE_ENABLED_KEY, "false"))
                    .thenReturn("true");
            config.when(() -> AiConfig.getProperty(AgentRoutingConfig.ROUTING_ENABLED_KEY, "false"))
                    .thenReturn("true");
            config.when(() -> AiConfig.getProperty(AgentRoutingConfig.API_KEY, ""))
                    .thenReturn("ts-key");

            assertTrue(AgentRoutingConfig.createRouter() instanceof TypeSafeAgentRequestRouter);

            config.when(() -> AiConfig.getProperty(AgentRoutingConfig.BASE_URL_KEY,
                    TypeSafeJudgmentProvider.DEFAULT_BASE_URL)).thenReturn(":bad-url");
            assertEquals(AgentRequestRouter.Outcome.UNAVAILABLE,
                    AgentRoutingConfig.createRouter().route("hello").outcome());
        }
    }

    @Test
    void confidenceAndTimeoutUseValidOverridesOrDefaults() {
        try (MockedStatic<AiConfig> config = defaults()) {
            config.when(() -> AiConfig.getProperty(AgentRoutingConfig.MIN_CONFIDENCE_KEY, "0.75"))
                    .thenReturn("0.82");
            config.when(() -> AiConfig.getProperty(AgentRoutingConfig.TIMEOUT_SECONDS_KEY, "15"))
                    .thenReturn("7");
            assertEquals(0.82, AgentRoutingConfig.minConfidence());
            assertEquals(Duration.ofSeconds(7), AgentRoutingConfig.timeout());

            config.when(() -> AiConfig.getProperty(AgentRoutingConfig.MIN_CONFIDENCE_KEY, "0.75"))
                    .thenReturn("2");
            config.when(() -> AiConfig.getProperty(AgentRoutingConfig.TIMEOUT_SECONDS_KEY, "15"))
                    .thenReturn("bad");
            assertEquals(AgentRoutingConfig.DEFAULT_MIN_CONFIDENCE, AgentRoutingConfig.minConfidence());
            assertEquals(TypeSafeJudgmentProvider.DEFAULT_TIMEOUT, AgentRoutingConfig.timeout());
        }
    }

    @Test
    void expansionDefaultsOffAndHonoursFlagAndMax() {
        try (MockedStatic<AiConfig> config = defaults()) {
            assertFalse(AgentRoutingConfig.expansionEnabled());
            assertEquals(AgentRoutingConfig.DEFAULT_EXPANSION_MAX, AgentRoutingConfig.expansionMax());

            config.when(() -> AiConfig.getProperty(AgentRoutingConfig.EXPANSION_ENABLED_KEY, "false"))
                    .thenReturn("true");
            config.when(() -> AiConfig.getProperty(AgentRoutingConfig.EXPANSION_MAX_KEY, "1"))
                    .thenReturn("3");
            assertTrue(AgentRoutingConfig.expansionEnabled());
            assertEquals(3, AgentRoutingConfig.expansionMax());

            config.when(() -> AiConfig.getProperty(AgentRoutingConfig.EXPANSION_MAX_KEY, "1"))
                    .thenReturn("bad");
            assertEquals(AgentRoutingConfig.DEFAULT_EXPANSION_MAX, AgentRoutingConfig.expansionMax());
            config.when(() -> AiConfig.getProperty(AgentRoutingConfig.EXPANSION_MAX_KEY, "1"))
                    .thenReturn("-2");
            assertEquals(0, AgentRoutingConfig.expansionMax());
        }
    }

    @Test
    void usableSecretRejectsBlanksAndSamplePlaceholders() {
        assertFalse(AgentRoutingConfig.usableSecret(null));
        assertFalse(AgentRoutingConfig.usableSecret("  "));
        assertFalse(AgentRoutingConfig.usableSecret("YOUR_TYPESAFE_API_KEY"));
        assertTrue(AgentRoutingConfig.usableSecret("ts-real"));
    }

    @Test
    void ollamaBackendCreatesLocalEmbeddingRouterWithoutAnyKey() {
        try (MockedStatic<AiConfig> config = defaults()) {
            config.when(() -> AiConfig.getProperty(AgentJudgmentConfig.PROVIDER_KEY, AgentJudgmentConfig.TYPESAFE))
                    .thenReturn(" Ollama ");

            assertNull(AgentRoutingConfig.createRouter());

            config.when(() -> AiConfig.getProperty(AgentRoutingConfig.TYPESAFE_ENABLED_KEY, "false")).thenReturn("true");
            config.when(() -> AiConfig.getProperty(AgentRoutingConfig.ROUTING_ENABLED_KEY, "false")).thenReturn("true");
            AgentRequestRouter first = AgentRoutingConfig.createRouter();
            assertTrue(first instanceof EmbeddingAgentRequestRouter);
            assertSame(first, AgentRoutingConfig.createRouter());

            config.when(() -> AiConfig.getProperty(OllamaEmbeddingClient.MODEL_KEY, OllamaEmbeddingClient.DEFAULT_MODEL))
                    .thenReturn("nomic-embed-text");
            assertNotSame(first, AgentRoutingConfig.createRouter());
        }
    }

    @Test
    void localBackendCreatesSharedOnnxRouterWithoutAnyKeyOrDownload() {
        try (MockedStatic<AiConfig> config = defaults()) {
            config.when(() -> AiConfig.getProperty(AgentJudgmentConfig.PROVIDER_KEY, AgentJudgmentConfig.TYPESAFE))
                    .thenReturn("local");
            config.when(() -> AiConfig.getProperty(LocalEmbeddingModel.DIR_KEY, ""))
                    .thenReturn(System.getProperty("java.io.tmpdir") + "/fw-no-model");
            config.when(() -> AiConfig.getProperty(LocalEmbeddingModel.DOWNLOAD_KEY, "true")).thenReturn("false");

            assertNull(AgentRoutingConfig.createRouter());

            config.when(() -> AiConfig.getProperty(AgentRoutingConfig.TYPESAFE_ENABLED_KEY, "false")).thenReturn("true");
            config.when(() -> AiConfig.getProperty(AgentRoutingConfig.ROUTING_ENABLED_KEY, "false")).thenReturn("true");
            AgentRequestRouter first = AgentRoutingConfig.createRouter();
            assertTrue(first instanceof EmbeddingAgentRequestRouter);
            assertSame(first, AgentRoutingConfig.createRouter());
            assertEquals(AgentRequestRouter.Outcome.UNAVAILABLE, first.route("save the plan").outcome());

            config.when(() -> AiConfig.getProperty(LocalEmbeddingModel.MODEL_KEY, LocalEmbeddingModel.DEFAULT_MODEL))
                    .thenReturn("onnx-community/embeddinggemma-2-ONNX");
            assertNotSame(first, AgentRoutingConfig.createRouter());
        }
    }

    @Test
    void routerCreationReflectsPropertyChangesForTheNextAgentMessage() {
        try (MockedStatic<AiConfig> config = mockStatic(AiConfig.class)) {
            Map<String, String> properties = new HashMap<>();
            config.when(() -> AiConfig.getProperty(anyString(), anyString())).thenAnswer(invocation ->
                    properties.getOrDefault(invocation.getArgument(0), invocation.getArgument(1)));
            properties.put(AgentRoutingConfig.TYPESAFE_ENABLED_KEY, "true");
            properties.put(AgentRoutingConfig.ROUTING_ENABLED_KEY, "true");
            properties.put(AgentRoutingConfig.API_KEY, "configured-test-key");
            properties.put(AgentJudgmentConfig.PROVIDER_KEY, AgentJudgmentConfig.TYPESAFE);

            AgentRequestRouter beforeSave = AgentRoutingConfig.createRouter();
            assertTrue(beforeSave instanceof TypeSafeAgentRequestRouter);

            properties.put(AgentJudgmentConfig.PROVIDER_KEY, AgentJudgmentConfig.LOCAL);
            properties.put(LocalEmbeddingModel.DIR_KEY, System.getProperty("java.io.tmpdir") + "/fw-no-model");
            properties.put(LocalEmbeddingModel.DOWNLOAD_KEY, "false");
            AgentRequestRouter afterSave = AgentRoutingConfig.createRouter();
            assertTrue(afterSave instanceof EmbeddingAgentRequestRouter);
            assertNotSame(beforeSave, afterSave);
        }
    }

    private static MockedStatic<AiConfig> defaults() {
        MockedStatic<AiConfig> config = mockStatic(AiConfig.class);
        config.when(() -> AiConfig.getProperty(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        return config;
    }
}
