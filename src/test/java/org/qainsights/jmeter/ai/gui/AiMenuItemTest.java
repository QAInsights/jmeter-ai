package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qainsights.jmeter.ai.service.AiService;
import org.qainsights.jmeter.ai.service.ClaudeService;
import org.qainsights.jmeter.ai.service.GoogleAiService;
import org.qainsights.jmeter.ai.utils.AiConfig;

import javax.swing.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(MockitoExtension.class)
class AiMenuItemTest {

    private MockedStatic<AiConfig> aiConfigMockedStatic;
    private String configuredServiceType = "openai";
    private String configuredApiKey = "test-key";
    private String configuredModel = "gpt-4o";

    @BeforeEach
    void setUp() {
        aiConfigMockedStatic = mockStatic(AiConfig.class);
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(anyString(), anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String defaultValue = invocation.getArgument(1);
            if (key.equals("jmeter.ai.service.type")) return configuredServiceType;
            if (key.equals("openai.api.key")) return configuredApiKey;
            if (key.equals("openai.default.model")) return configuredModel;
            if (key.equals("anthropic.api.key")) return configuredApiKey;
            if (key.equals("anthropic.default.model")) return configuredModel;
            if (key.equals("claude.default.model")) return configuredModel;
            if (key.equals("anthropic.model")) return configuredModel;
            if (key.equals("ollama.default.model")) return configuredModel;
            if (key.equals("deepseek.api.key")) return configuredApiKey;
            if (key.equals("deepseek.default.model")) return configuredModel;
            if (key.equals("google.api.key")) return configuredApiKey;
            if (key.equals("google.default.model")) return configuredModel;
            return defaultValue;
        });
    }

    @AfterEach
    void tearDown() {
        if (aiConfigMockedStatic != null) {
            aiConfigMockedStatic.close();
        }
    }

    @Test
    void testConstructorWithOpenAi() {
        configuredServiceType = "openai";
        configuredApiKey = "test-key";
        configuredModel = "gpt-4o";

        JPanel parent = new JPanel();
        AiMenuItem item = new AiMenuItem(parent);

        assertNotNull(item);
        assertEquals("AI", item.getText());
    }

    @Test
    void testConstructorWithAnthropic() {
        configuredServiceType = "anthropic";
        configuredApiKey = "test-key";
        configuredModel = "claude-3-5-sonnet";

        JPanel parent = new JPanel();
        AiMenuItem item = new AiMenuItem(parent);

        assertNotNull(item);
    }

    @Test
    void testConstructorWithOllama() {
        configuredServiceType = "ollama";
        configuredModel = "llama3.1";

        JPanel parent = new JPanel();
        AiMenuItem item = new AiMenuItem(parent);

        assertNotNull(item);
    }

    @Test
    void testConstructorWithDeepseek() {
        configuredServiceType = "deepseek";
        configuredApiKey = "test-key";
        configuredModel = "deepseek-chat";

        JPanel parent = new JPanel();
        AiMenuItem item = new AiMenuItem(parent);

        assertNotNull(item);
    }

    @Test
    void testConstructorWithUnknownService() {
        configuredServiceType = "unknown";

        JPanel parent = new JPanel();
        AiMenuItem item = new AiMenuItem(parent);

        assertNotNull(item);
    }

    @Test
    void createAiServiceUsesDocumentedAnthropicDefaultModelKey() {
        configuredServiceType = "anthropic";
        configuredApiKey = "test-key";
        configuredModel = "claude-sonnet-4-6";

        AiService service = AiMenuItem.createAiService(configuredServiceType);

        assertInstanceOf(ClaudeService.class, service);
    }

    @Test
    void createAiServiceSupportsGoogleServiceType() {
        configuredServiceType = "google";
        configuredApiKey = "test-google-key";
        configuredModel = "gemini-2.5-flash";

        AiService service = AiMenuItem.createAiService(configuredServiceType);

        assertInstanceOf(GoogleAiService.class, service);
    }
}
