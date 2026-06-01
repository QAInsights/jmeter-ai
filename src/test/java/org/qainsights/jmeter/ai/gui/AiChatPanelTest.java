package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qainsights.jmeter.ai.utils.AiConfig;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(MockitoExtension.class)
class AiChatPanelTest {

    private MockedStatic<AiConfig> aiConfigMockedStatic;

    @BeforeEach
    void setUp() {
        aiConfigMockedStatic = mockStatic(AiConfig.class);
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(anyString(), anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String defaultValue = invocation.getArgument(1);
            if (key.equals("jmeter.ai.service.type")) return "openai";
            if (key.equals("openai.api.key")) return "test-key";
            if (key.equals("openai.default.model")) return "gpt-4o";
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
    void testConstructorAndBasicMethods() {
        AiChatPanel panel = new AiChatPanel();

        assertNotNull(panel);
        assertNotNull(panel.getConversationHistory());
        assertNull(panel.getSelectedModel());
        
        // Test basic callback methods
        assertDoesNotThrow(() -> panel.setInputEnabled(true));
        assertDoesNotThrow(() -> panel.setInputEnabled(false));
        assertDoesNotThrow(panel::clearMessageField);
        assertDoesNotThrow(() -> panel.setLastCommandType("LINT"));
    }

    @Test
    void testConversationHistory() {
        AiChatPanel panel = new AiChatPanel();
        
        panel.addToConversationHistory("User: hello");
        panel.addToConversationHistory("AI: hi");

        assertEquals(2, panel.getConversationHistory().size());
        assertEquals("User: hello", panel.getConversationHistory().get(0));
        assertEquals("AI: hi", panel.getConversationHistory().get(1));
    }
}
