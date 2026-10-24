package org.qainsights.jmeter.ai.gui;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderSetupDialogTest {
    @Test
    void keepsAnEditedCustomModelWhenTheModelListRefreshes() {
        assertEquals("custom-model",
                ProviderSetupDialog.modelAfterRefresh(
                        "custom-model", "llama3.1", List.of("llama3.1", "qwen2.5")));
    }

    @Test
    void keepsTheConfiguredModelOrUsesTheFirstAvailableModel() {
        assertEquals("llama3.1",
                ProviderSetupDialog.modelAfterRefresh(
                        "llama3.1", "llama3.1", List.of("llama3.1", "qwen2.5")));
        assertEquals("qwen2.5",
                ProviderSetupDialog.modelAfterRefresh(
                        "llama3.1", "llama3.1", List.of("qwen2.5")));
        assertEquals("llama3.1",
                ProviderSetupDialog.modelAfterRefresh("llama3.1", "llama3.1", List.of()));
    }
}
