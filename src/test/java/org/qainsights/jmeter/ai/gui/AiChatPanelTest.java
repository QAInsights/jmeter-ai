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

    @Test
    void newConversationClearsAttachments() {
        AiChatPanel panel = new AiChatPanel();
        panel.attachmentRegistry().register("a.txt", "body",
                org.qainsights.jmeter.ai.service.attach.FileContentPreparer.Mode.SMART);
        assertEquals(1, panel.attachmentRegistry().size());
        assertEquals(1, panel.attachmentRegistry().pendingCount());

        panel.startNewConversation();

        // registry fully cleared - no stale markers can leak into the next conversation
        assertEquals(0, panel.attachmentRegistry().size());
        assertEquals(0, panel.attachmentRegistry().pendingCount());
        java.util.List<String> resolved = panel.resolveAttachmentMarkers(
                java.util.List.of("User: check [file:f1]"));
        assertTrue(resolved.get(0).contains("[attachment no longer available]"));
    }

    @Test
    void resolveAttachmentMarkersRoutesThroughRegistry() {
        AiChatPanel panel = new AiChatPanel();

        // marker-free turns pass through untouched
        java.util.List<String> plain = java.util.List.of("User: hello", "AI: hi");
        assertEquals(plain, panel.resolveAttachmentMarkers(plain));

        // with nothing attached, a dangling marker resolves to the fallback note
        // (proves the override actually delegates into the registry)
        java.util.List<String> resolved = panel.resolveAttachmentMarkers(
                java.util.List.of("User: check [file:f1]"));
        assertFalse(resolved.get(0).contains("[file:"));
        assertTrue(resolved.get(0).contains("[attachment no longer available]"));
    }
}
