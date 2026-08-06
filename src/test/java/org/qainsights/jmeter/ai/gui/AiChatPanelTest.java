package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qainsights.jmeter.ai.service.prefs.ModelSelectorPreferences;
import org.qainsights.jmeter.ai.service.session.ConversationStore;
import org.qainsights.jmeter.ai.utils.AiConfig;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(MockitoExtension.class)
class AiChatPanelTest {

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path tempDir;

    private MockedStatic<AiConfig> aiConfigMockedStatic;

    @BeforeEach
    void setUp() {
        // keep the panel's model-selector preferences out of the real user home
        System.setProperty(ModelSelectorPreferences.PATH_PROPERTY,
                tempDir.resolve("preferences.json").toString());
        System.setProperty(ConversationStore.DIR_PROPERTY,
                tempDir.resolve("sessions").toString());
        restoreSessions = false;
        aiConfigMockedStatic = mockStatic(AiConfig.class);
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(anyString(), anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String defaultValue = invocation.getArgument(1);
            if (key.equals("jmeter.ai.service.type")) return "openai";
            if (key.equals("openai.api.key")) return "test-key";
            if (key.equals("openai.default.model")) return "gpt-4o";
            if (key.equals(ConversationStore.RESTORE_PROPERTY)) return String.valueOf(restoreSessions);
            return defaultValue;
        });
    }

    @AfterEach
    void tearDown() {
        if (aiConfigMockedStatic != null) {
            aiConfigMockedStatic.close();
        }
        System.clearProperty(ConversationStore.DIR_PROPERTY);
        System.clearProperty(ModelSelectorPreferences.PATH_PROPERTY);
    }

    private boolean restoreSessions;

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

    // --- F5: conversation persistence ----------------------------------------

    @Test
    void turnsAreAutosavedToSessionFile() throws Exception {
        AiChatPanel panel = new AiChatPanel();
        panel.addToConversationHistory("hello");
        panel.addToConversationHistory("hi there");

        java.nio.file.Path sessionFile = tempDir.resolve("sessions")
                .resolve(panel.currentSessionId() + ".json");
        assertTrue(java.nio.file.Files.exists(sessionFile));
        String json = java.nio.file.Files.readString(sessionFile);
        assertTrue(json.contains("hello"));
        assertTrue(json.contains("hi there"));
    }

    @Test
    void newConversationArchivesOldSessionAndStartsFresh() throws Exception {
        AiChatPanel panel = new AiChatPanel();
        panel.addToConversationHistory("old turn");
        String oldId = panel.currentSessionId();

        panel.startNewConversation();

        assertNotEquals(oldId, panel.currentSessionId());
        // the archived session file still exists with its content
        assertTrue(java.nio.file.Files.exists(
                tempDir.resolve("sessions").resolve(oldId + ".json")));
        // and no empty session file is created for the fresh conversation
        assertFalse(java.nio.file.Files.exists(
                tempDir.resolve("sessions").resolve(panel.currentSessionId() + ".json")));
    }

    @Test
    void restoreRepopulatesHistoryTranscriptAndAttachments() {
        restoreSessions = true;

        AiChatPanel first = new AiChatPanel();
        first.attachmentRegistry().register("jmeter.log", "ERROR boom",
                org.qainsights.jmeter.ai.service.attach.FileContentPreparer.Mode.SMART);
        first.attachmentRegistry().consumePending();
        first.addToConversationHistory("check this [file:f1]");
        first.addToConversationHistory("looks bad");
        String sessionId = first.currentSessionId();

        AiChatPanel restored = new AiChatPanel();

        assertEquals(sessionId, restored.currentSessionId());
        assertEquals(java.util.List.of("check this [file:f1]", "looks bad"),
                restored.getConversationHistory());
        // attachment markers resolve again after restart
        assertNotNull(restored.attachmentRegistry().find("f1"));
        assertEquals(0, restored.attachmentRegistry().pendingCount());
        String resolved = restored.resolveAttachmentMarkers(
                java.util.List.of("check this [file:f1]")).get(0);
        assertTrue(resolved.contains("ERROR boom"));
    }

    @Test
    void restoreCarriesSessionModelForReselection() throws Exception {
        restoreSessions = true;
        // plant a session file with a model set (the panel can't pick a model
        // in tests because no provider list loads)
        java.nio.file.Path dir = tempDir.resolve("sessions");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("s1.json"), """
                {"id":"s1","createdAt":1,"model":"openai:gpt-5.1",
                 "turns":[{"role":"user","text":"hi","ts":1}],"attachments":[]}
                """);

        AiChatPanel panel = new AiChatPanel();

        assertEquals("openai:gpt-5.1", panel.restoredSessionModel());
        assertEquals(java.util.List.of("hi"), panel.getConversationHistory());
    }

    @Test
    void restoreIsSkippedWhenPropertyOff() {
        AiChatPanel first = new AiChatPanel();
        first.addToConversationHistory("turn");

        AiChatPanel second = new AiChatPanel();
        assertTrue(second.getConversationHistory().isEmpty());
    }

    @Test
    void buildSessionCapturesModelHistoryAndAttachments() {
        AiChatPanel panel = new AiChatPanel();
        panel.attachmentRegistry().register("a.txt", "body",
                org.qainsights.jmeter.ai.service.attach.FileContentPreparer.Mode.RAW);
        panel.addToConversationHistory("hello");

        org.qainsights.jmeter.ai.service.session.ConversationSession session = panel.buildSession();
        assertEquals(panel.currentSessionId(), session.id());
        assertEquals(1, session.turns().size());
        assertEquals("user", session.turns().get(0).role());
        assertTrue(session.turns().get(0).timestamp() > 0);
        assertEquals(1, session.attachments().size());
        assertEquals("a.txt", session.attachments().get(0).fileName());
        assertEquals("raw", session.attachments().get(0).mode());
    }
}
