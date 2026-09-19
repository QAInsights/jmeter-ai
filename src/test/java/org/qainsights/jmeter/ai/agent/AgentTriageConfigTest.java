package org.qainsights.jmeter.ai.agent;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.qainsights.jmeter.ai.utils.AiConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

class AgentTriageConfigTest {

    @Test
    void triageDefaultsOffAndCreatesNothing() {
        try (MockedStatic<AiConfig> config = defaults()) {
            assertFalse(AgentTriageConfig.enabled());
            assertNull(AgentTriageConfig.createTriage());
        }
    }

    @Test
    void triageFlagAloneDoesNotEnableWithoutMasterSwitch() {
        try (MockedStatic<AiConfig> config = defaults()) {
            config.when(() -> AiConfig.getProperty(AgentTriageConfig.TRIAGE_ENABLED_KEY, "false"))
                    .thenReturn("true");

            assertFalse(AgentTriageConfig.enabled());
            assertNull(AgentTriageConfig.createTriage());
        }
    }

    @Test
    void bothFlagsEnableButMissingKeyCreatesNoTriage() {
        try (MockedStatic<AiConfig> config = defaults()) {
            config.when(() -> AiConfig.getProperty(AgentRoutingConfig.TYPESAFE_ENABLED_KEY, "false"))
                    .thenReturn("true");
            config.when(() -> AiConfig.getProperty(AgentTriageConfig.TRIAGE_ENABLED_KEY, "false"))
                    .thenReturn("true");

            assertTrue(AgentTriageConfig.enabled());
            assertNull(AgentTriageConfig.createTriage());

            config.when(() -> AiConfig.getProperty(AgentRoutingConfig.API_KEY, ""))
                    .thenReturn("ts-key");
            assertNotNull(AgentTriageConfig.createTriage());
        }
    }

    @Test
    void maxFailuresUsesOverrideOrDefaultOnBadInput() {
        try (MockedStatic<AiConfig> config = defaults()) {
            assertEquals(AgentTriageConfig.DEFAULT_TRIAGE_MAX_FAILURES, AgentTriageConfig.maxFailures());

            config.when(() -> AiConfig.getProperty(AgentTriageConfig.TRIAGE_MAX_FAILURES_KEY, "5"))
                    .thenReturn("3");
            assertEquals(3, AgentTriageConfig.maxFailures());

            config.when(() -> AiConfig.getProperty(AgentTriageConfig.TRIAGE_MAX_FAILURES_KEY, "5"))
                    .thenReturn("bad");
            assertEquals(AgentTriageConfig.DEFAULT_TRIAGE_MAX_FAILURES, AgentTriageConfig.maxFailures());

            config.when(() -> AiConfig.getProperty(AgentTriageConfig.TRIAGE_MAX_FAILURES_KEY, "5"))
                    .thenReturn("-2");
            assertEquals(0, AgentTriageConfig.maxFailures());
        }
    }

    private static MockedStatic<AiConfig> defaults() {
        MockedStatic<AiConfig> config = mockStatic(AiConfig.class);
        config.when(() -> AiConfig.getProperty(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        return config;
    }
}
