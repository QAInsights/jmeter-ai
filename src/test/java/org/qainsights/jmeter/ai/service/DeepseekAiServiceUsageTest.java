package org.qainsights.jmeter.ai.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for usage-stats wiring in {@link DeepseekAiService}.
 * Kept in its own file because DeepseekAiServiceStreamingTest is at the
 * project line limit.
 */
class DeepseekAiServiceUsageTest {

    private DeepseekAiService newService() {
        return new DeepseekAiService(null, null, false, "http://127.0.0.1:1",
                "deepseek-chat", 0.7f, 10, 4096L, "You are a test assistant.");
    }

    @Test
    void testSetUsageStats_storesValue() throws Exception {
        DeepseekAiService service = newService();
        org.qainsights.jmeter.ai.service.usage.UsageStats stats =
                new org.qainsights.jmeter.ai.service.usage.UsageStats();
        service.setUsageStats(stats);

        Field field = DeepseekAiService.class.getDeclaredField("usageStats");
        field.setAccessible(true);
        assertSame(stats, field.get(service),
                "setUsageStats should store the UsageStats instance");
    }

    @Test
    void testSetUsageStats_storesValue_onAnthropicFormatService() throws Exception {
        DeepseekAiService service = new DeepseekAiService(null, null, true,
                "http://127.0.0.1:1", "deepseek-chat", 0.7f, 10, 4096L,
                "You are a test assistant.");
        org.qainsights.jmeter.ai.service.usage.UsageStats stats =
                new org.qainsights.jmeter.ai.service.usage.UsageStats();
        service.setUsageStats(stats);

        Field field = DeepseekAiService.class.getDeclaredField("usageStats");
        field.setAccessible(true);
        assertSame(stats, field.get(service),
                "setUsageStats should store the UsageStats instance");
    }
}
