package org.qainsights.jmeter.ai.telemetry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TelemetryModelsTest {

    @Test
    void keepsWellKnownModelsWithoutPrefix() {
        assertEquals("gpt-4o-mini", TelemetryModels.publicName("openai:gpt-4o-mini"));
        assertEquals("claude-sonnet-4-5", TelemetryModels.publicName("claude-sonnet-4-5"));
        assertEquals("gemini-2.5-pro", TelemetryModels.publicName("google:gemini-2.5-pro"));
        assertEquals("llama3.1:8b", TelemetryModels.publicName("ollama:llama3.1:8b"));
        assertEquals("o3-mini", TelemetryModels.publicName("openai:o3-mini"));
        assertEquals("anthropic.claude-3-5-sonnet-20240620-v1:0",
                TelemetryModels.publicName("bedrock:anthropic.claude-3-5-sonnet-20240620-v1:0"));
        assertEquals("deepseek-chat", TelemetryModels.publicName("deepseek:deepseek-chat"));
        assertEquals("qwen/qwen3.8-flash:free",
                TelemetryModels.publicName("openrouter:qwen/qwen3.8-flash:free"));
        assertEquals("on-device", TelemetryModels.publicName("apple:on-device"));
    }

    @Test
    void reportsEverythingElseAsCustom() {
        assertEquals("custom", TelemetryModels.publicName("openai:ft:gpt-4o-mini:acme:abc123"));
        assertEquals("custom", TelemetryModels.publicName("openai:acme-internal-chat"));
        assertEquals("custom", TelemetryModels.publicName("ollama:hf.co/acme/private-model"));
        assertEquals("custom", TelemetryModels.publicName("openai:gpt-4o/" + "x".repeat(70)));
        assertEquals("custom", TelemetryModels.publicName("https://gateway.example.com/v1"));
    }

    @Test
    void blankIsUnknown() {
        assertEquals("unknown", TelemetryModels.publicName(null));
        assertEquals("unknown", TelemetryModels.publicName("  "));
    }

    @Test
    void providerFromPrefix() {
        assertEquals("openai", TelemetryModels.provider("openai:gpt-4o"));
        assertEquals("claude-code", TelemetryModels.provider("claude-code:sonnet"));
        assertEquals("anthropic", TelemetryModels.provider("claude-sonnet-4-5"));
        assertEquals("apple", TelemetryModels.provider("apple:on-device"));
        assertEquals("openrouter", TelemetryModels.provider("openrouter:anthropic/claude-sonnet-5.5"));
    }

    @Test
    void keysAreServerSafe() {
        assertEquals("tool_add_element", Telemetry.toolKey("add_element"));
        assertEquals("tool_bad_name_", Telemetry.toolKey("Bad Name!"));
        assertEquals("err_claudecode_auth", Telemetry.errorKey("claude-code", "auth"));
        assertEquals("err_unknown_other", Telemetry.errorKey(null, "other"));
        assertEquals(40, ("tool_" + "x".repeat(35)).length());
        assertEquals("tool_" + "x".repeat(35), Telemetry.toolKey("x".repeat(80)));
        assertEquals("toolfail_add_element", Telemetry.toolFailKey("add_element"));
        assertEquals("toolfail_bad_name_", Telemetry.toolFailKey("Bad Name!"));
        assertEquals("toolfail_" + "x".repeat(31), Telemetry.toolFailKey("x".repeat(80)));
    }
}
