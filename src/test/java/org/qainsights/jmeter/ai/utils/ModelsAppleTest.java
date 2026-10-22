package org.qainsights.jmeter.ai.utils;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.qainsights.jmeter.ai.apple.AppleFoundationHelper;
import org.qainsights.jmeter.ai.service.AiServiceHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelsAppleTest {
    @Test
    void listsAppleModelOnlyWhenEnabledAndStatusAvailable() {
        AppleFoundationHelper.Status available = mock(AppleFoundationHelper.Status.class);
        when(available.isAvailable()).thenReturn(true);
        try (MockedStatic<AiConfig> config = mockStatic(AiConfig.class);
             MockedStatic<AppleFoundationHelper> helper = mockStatic(AppleFoundationHelper.class)) {
            config.when(() -> AiConfig.getProperty("apple.enabled", "true")).thenReturn("true");
            helper.when(AppleFoundationHelper::status).thenReturn(available);

            List<String> models = Models.loadAllModels(new AiServiceHolder());

            assertTrue(models.contains("apple:on-device"));
        }
    }

    @Test
    void omitsAppleModelWhenUnavailableOrDisabled() {
        AppleFoundationHelper.Status unavailable = mock(AppleFoundationHelper.Status.class);
        when(unavailable.isAvailable()).thenReturn(false);
        when(unavailable.getReason()).thenReturn("deviceNotEligible");
        try (MockedStatic<AiConfig> config = mockStatic(AiConfig.class);
             MockedStatic<AppleFoundationHelper> helper = mockStatic(AppleFoundationHelper.class)) {
            config.when(() -> AiConfig.getProperty("apple.enabled", "true")).thenReturn("true");
            helper.when(AppleFoundationHelper::status).thenReturn(unavailable);
            assertFalse(Models.loadAllModels(new AiServiceHolder()).contains("apple:on-device"));

            config.when(() -> AiConfig.getProperty("apple.enabled", "true")).thenReturn("false");
            assertFalse(Models.loadAllModels(new AiServiceHolder()).contains("apple:on-device"));
            helper.verify(AppleFoundationHelper::status, times(1));
        }
    }
}
