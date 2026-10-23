package org.qainsights.jmeter.ai.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiServiceModelQuirksTest {

    @ParameterizedTest
    @ValueSource(strings = {"o1", "o3-mini", "o4-mini", "gpt-5", "gpt-5.5", "O3", "GPT-5-mini",
            "chat-latest", "CHAT-LATEST", " chat-latest ", "gpt-5-chat-latest", "corp-chat-latest"})
    void fixedTemperatureModelsOmitTemperature(String id) {
        assertFalse(OpenAiService.supportsCustomTemperature(id), id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"gpt-4o", "gpt-4.1-mini", "corp-chat-latest-v2", "deepseek-v4-flash-free", ""})
    void otherModelsKeepCustomTemperature(String id) {
        assertTrue(OpenAiService.supportsCustomTemperature(id), id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat-latest", "CHAT-LATEST", "corp-chat-latest", "gpt-4o"})
    void chatLatestAndChatModelsAreNotTreatedAsReasoningModels(String id) {
        assertFalse(OpenAiService.isReasoningModel(id), id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"o1", "O3-mini", "o4-mini", "gpt-5", "GPT-5.5"})
    void reasoningModelsAreDetectedCaseInsensitively(String id) {
        assertTrue(OpenAiService.isReasoningModel(id), id);
    }
}
