package org.qainsights.jmeter.ai.agent;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.qainsights.jmeter.ai.service.OpenAiDecisionsJudgmentProvider;
import org.qainsights.jmeter.ai.service.TypeSafeJudgmentProvider;
import org.qainsights.jmeter.ai.utils.AiConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

class AgentJudgmentConfigTest {

    @Test
    void defaultsToTypeSafeJev() {
        try (MockedStatic<AiConfig> config = defaults()) {
            assertEquals(AgentJudgmentConfig.TYPESAFE, AgentJudgmentConfig.provider());
            assertEquals("Jev", AgentJudgmentConfig.brand());
            assertNull(AgentJudgmentConfig.createProvider());

            config.when(() -> AiConfig.getProperty(AgentRoutingConfig.API_KEY, "")).thenReturn("ts-key");
            assertInstanceOf(TypeSafeJudgmentProvider.class, AgentJudgmentConfig.createProvider());
        }
    }

    @Test
    void unknownProviderValueFallsBackToTypeSafe() {
        try (MockedStatic<AiConfig> config = defaults()) {
            config.when(() -> AiConfig.getProperty(AgentJudgmentConfig.PROVIDER_KEY, AgentJudgmentConfig.TYPESAFE))
                    .thenReturn("something-else");
            assertEquals(AgentJudgmentConfig.TYPESAFE, AgentJudgmentConfig.provider());
        }
    }

    @Test
    void openAiBackendNeedsUsableOpenAiKey() {
        try (MockedStatic<AiConfig> config = openAi()) {
            assertEquals(AgentJudgmentConfig.OPENAI, AgentJudgmentConfig.provider());
            assertEquals("OpenAI Decisions", AgentJudgmentConfig.brand());
            assertNull(AgentJudgmentConfig.createProvider());

            config.when(() -> AiConfig.getProperty(AgentJudgmentConfig.OPENAI_API_KEY, ""))
                    .thenReturn("YOUR_OPENAI_API_KEY");
            assertNull(AgentJudgmentConfig.createProvider());

            config.when(() -> AiConfig.getProperty(AgentRoutingConfig.API_KEY, "")).thenReturn("ts-key");
            assertNull(AgentJudgmentConfig.createProvider(), "a TypeSafe key must not enable the OpenAI backend");

            config.when(() -> AiConfig.getProperty(AgentJudgmentConfig.OPENAI_API_KEY, "")).thenReturn("sk-test");
            assertInstanceOf(OpenAiDecisionsJudgmentProvider.class, AgentJudgmentConfig.createProvider());
        }
    }

    @Test
    void openAiBackendDrivesRoutingAndTriageOnlyWhenFlagsAreOn() {
        try (MockedStatic<AiConfig> config = openAi()) {
            config.when(() -> AiConfig.getProperty(AgentJudgmentConfig.OPENAI_API_KEY, "")).thenReturn("sk-test");
            assertNull(AgentRoutingConfig.createRouter());
            assertNull(AgentTriageConfig.createTriage());

            config.when(() -> AiConfig.getProperty(AgentRoutingConfig.TYPESAFE_ENABLED_KEY, "false"))
                    .thenReturn("true");
            config.when(() -> AiConfig.getProperty(AgentRoutingConfig.ROUTING_ENABLED_KEY, "false"))
                    .thenReturn("true");
            config.when(() -> AiConfig.getProperty(AgentTriageConfig.TRIAGE_ENABLED_KEY, "false"))
                    .thenReturn("true");
            assertInstanceOf(TypeSafeAgentRequestRouter.class, AgentRoutingConfig.createRouter());
            assertNotNull(AgentTriageConfig.createTriage());

            config.when(() -> AiConfig.getProperty(AgentJudgmentConfig.OPENAI_API_KEY, "")).thenReturn("");
            assertNull(AgentRoutingConfig.createRouter());
            assertNull(AgentTriageConfig.createTriage());
        }
    }

    private static MockedStatic<AiConfig> openAi() {
        MockedStatic<AiConfig> config = defaults();
        config.when(() -> AiConfig.getProperty(AgentJudgmentConfig.PROVIDER_KEY, AgentJudgmentConfig.TYPESAFE))
                .thenReturn(" OpenAI ");
        return config;
    }

    private static MockedStatic<AiConfig> defaults() {
        MockedStatic<AiConfig> config = mockStatic(AiConfig.class, CALLS_REAL_METHODS);
        config.when(() -> AiConfig.getProperty(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        return config;
    }
}
