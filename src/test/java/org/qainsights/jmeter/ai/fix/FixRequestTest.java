package org.qainsights.jmeter.ai.fix;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.qainsights.jmeter.ai.utils.AiConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mockStatic;

class FixRequestTest {
    @Test
    void configValuesAreClampedToSupportedRanges() {
        try (MockedStatic<AiConfig> config = mockStatic(AiConfig.class)) {
            config.when(() -> AiConfig.getProperty(FixRequest.MAX_ROUNDS_KEY, "5")).thenReturn("99");
            config.when(() -> AiConfig.getProperty(FixRequest.TIMEOUT_SECONDS_KEY, "120")).thenReturn("1");

            FixRequest request = FixRequest.fromConfig();

            assertEquals(10, request.maxRounds());
            assertEquals(10, request.timeoutSeconds());
        }
    }

    @Test
    void invalidConfigValuesUseDefaults() {
        try (MockedStatic<AiConfig> config = mockStatic(AiConfig.class)) {
            config.when(() -> AiConfig.getProperty(FixRequest.MAX_ROUNDS_KEY, "5")).thenReturn("invalid");
            config.when(() -> AiConfig.getProperty(FixRequest.TIMEOUT_SECONDS_KEY, "120")).thenReturn("invalid");

            FixRequest request = FixRequest.fromConfig();

            assertEquals(5, request.maxRounds());
            assertEquals(120, request.timeoutSeconds());
        }
    }
}
