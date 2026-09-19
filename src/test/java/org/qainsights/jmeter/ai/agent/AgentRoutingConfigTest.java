package org.qainsights.jmeter.ai.agent;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.qainsights.jmeter.ai.service.TypeSafeJudgmentProvider;
import org.qainsights.jmeter.ai.utils.AiConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void bothFlagsEnableRoutingAndMissingKeyProducesUnavailableRouter() {
        try (MockedStatic<AiConfig> config = defaults()) {
            config.when(() -> AiConfig.getProperty(AgentRoutingConfig.TYPESAFE_ENABLED_KEY, "false"))
                    .thenReturn("true");
            config.when(() -> AiConfig.getProperty(AgentRoutingConfig.ROUTING_ENABLED_KEY, "false"))
                    .thenReturn("true");

            AgentRequestRouter router = AgentRoutingConfig.createRouter();

            assertTrue(AgentRoutingConfig.enabled());
            assertEquals(AgentRequestRouter.Outcome.UNAVAILABLE, router.route("hello").outcome());
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

    private static MockedStatic<AiConfig> defaults() {
        MockedStatic<AiConfig> config = mockStatic(AiConfig.class);
        config.when(() -> AiConfig.getProperty(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        return config;
    }
}
