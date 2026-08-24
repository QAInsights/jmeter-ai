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

    // --- F9: context stats ---------------------------------------------------

    @Test
    void modelSelectorAndSendActionStayInsideComposer() {
        AiChatPanel panel = new AiChatPanel();
        GeminiBorderPanel composer = findComponent(panel, GeminiBorderPanel.class);
        ModelSelectorPanel selector = findComponent(panel, ModelSelectorPanel.class);
        InputOptionsRow options = findComponent(panel, InputOptionsRow.class);

        assertNotNull(composer);
        assertNotNull(selector);
        assertNotNull(options);
        assertTrue(javax.swing.SwingUtilities.isDescendingFrom(selector, composer));
        assertTrue(javax.swing.SwingUtilities.isDescendingFrom(options.sendButton(), composer));
    }

    @Test
    void modelThinkingEffortAndFavoriteShareOneRow() throws Exception {
        AiChatPanel panel = new AiChatPanel();
        ModelSelectorPanel selector = findComponent(panel, ModelSelectorPanel.class);
        ReasoningControls reasoning = findComponent(panel, ReasoningControls.class);
        assertNotNull(selector);
        assertNotNull(reasoning);
        selector.setModels(java.util.List.of("claude-sonnet-4-6"), "claude-sonnet-4-6");
        flushEdt();
        panel.setSize(500, 760);
        layoutRecursively(panel);

        javax.swing.JToggleButton favorite = selector.favoriteButton();
        java.awt.Point selectorPoint = javax.swing.SwingUtilities.convertPoint(selector, 0, 0, panel);
        java.awt.Point reasoningPoint = javax.swing.SwingUtilities.convertPoint(reasoning, 0, 0, panel);
        java.awt.Point favoritePoint = javax.swing.SwingUtilities.convertPoint(favorite, 0, 0, panel);
        int selectorCenter = selectorPoint.y + selector.getHeight() / 2;
        int reasoningCenter = reasoningPoint.y + reasoning.getHeight() / 2;
        int favoriteCenter = favoritePoint.y + favorite.getHeight() / 2;

        assertTrue(reasoning.getThinkingToggle().isVisible());
        assertTrue(reasoning.getEffortCombo().isVisible());
        assertTrue(Math.abs(selectorCenter - reasoningCenter) <= 2);
        assertTrue(Math.abs(selectorCenter - favoriteCenter) <= 2);
        assertTrue(favoritePoint.x > reasoningPoint.x);
    }

    @Test
    void headerControlsShareHeightAlignmentAndSpacing() throws Exception {
        AiChatPanel panel = new AiChatPanel();
        panel.setSize(500, 760);
        layoutRecursively(panel);
        flushEdt();
        layoutRecursively(panel);

        javax.swing.AbstractButton record = findButtonByText(panel, "Record");
        javax.swing.AbstractButton donate = findButtonByText(panel, "Donate");
        javax.swing.JButton overflow = findButton(panel, "Conversation actions and export");
        javax.swing.JButton newConversation = findButton(panel, "Start a new conversation");
        assertNotNull(record);
        assertNotNull(donate);
        assertNotNull(overflow);
        assertNotNull(newConversation);

        for (javax.swing.AbstractButton button : java.util.List.of(
                record, donate, overflow, newConversation)) {
            assertEquals(org.qainsights.jmeter.ai.gui.theme.UiTokens.HEADER_CONTROL_HEIGHT,
                    button.getHeight());
        }
        assertEquals(record.getWidth(), donate.getWidth());

        java.awt.Point recordPoint = javax.swing.SwingUtilities.convertPoint(record, 0, 0, panel);
        java.awt.Point donatePoint = javax.swing.SwingUtilities.convertPoint(donate, 0, 0, panel);
        java.awt.Point overflowPoint = javax.swing.SwingUtilities.convertPoint(overflow, 0, 0, panel);
        java.awt.Point newPoint = javax.swing.SwingUtilities.convertPoint(newConversation, 0, 0, panel);
        int recordCenter = recordPoint.y + record.getHeight() / 2;
        assertEquals(recordCenter, donatePoint.y + donate.getHeight() / 2);
        assertEquals(recordCenter, overflowPoint.y + overflow.getHeight() / 2);
        assertEquals(recordCenter, newPoint.y + newConversation.getHeight() / 2);
        assertEquals(org.qainsights.jmeter.ai.gui.theme.UiTokens.HEADER_ACTION_GAP,
                overflowPoint.x - donatePoint.x - donate.getWidth());
        assertEquals(org.qainsights.jmeter.ai.gui.theme.UiTokens.HEADER_ACTION_GAP,
                newPoint.x - overflowPoint.x - overflow.getWidth());
    }

    @Test
    void narrowHeaderKeepsNewConversationActionVisible() throws Exception {
        AiChatPanel panel = new AiChatPanel();
        panel.setSize(350, 700);
        layoutRecursively(panel);
        flushEdt();
        layoutRecursively(panel);
        javax.swing.JButton newConversation = findButton(
                panel, "Start a new conversation");

        assertNotNull(newConversation);
        java.awt.Point location = javax.swing.SwingUtilities.convertPoint(
                newConversation, 0, 0, panel);
        assertTrue(location.x >= 0);
        assertTrue(location.x + newConversation.getWidth() <= panel.getWidth());
    }

    @Test
    void freshConversationUsesDedicatedWelcomeState() {
        AiChatPanel panel = new AiChatPanel();
        assertNotNull(findComponent(panel, WelcomePanel.class));
    }

    @Test
    void contextStatsLabelIsInstalledInOptionsRow() {
        AiChatPanel panel = new AiChatPanel();
        ContextStatsLabel found = findContextStatsLabel(panel);
        assertNotNull(found, "the input options row should carry the context-stats label");
    }

    @Test
    void contextStatsResetOnNewConversation() throws Exception {
        AiChatPanel panel = new AiChatPanel();
        panel.addToConversationHistory("hello");
        flushEdt();
        ContextStatsLabel label = findContextStatsLabel(panel);
        assertNotNull(label);
        assertTrue(label.getText().startsWith("ctx ~"), "estimate shows after a user turn: " + label.getText());

        panel.startNewConversation();
        flushEdt();
        assertEquals("", label.getText(), "label clears for the new conversation");
    }

    private static void layoutRecursively(java.awt.Container root) {
        root.doLayout();
        for (java.awt.Component component : root.getComponents()) {
            if (component instanceof java.awt.Container container) {
                layoutRecursively(container);
            }
        }
    }

    private static javax.swing.AbstractButton findButtonByText(
            java.awt.Container root, String text) {
        for (java.awt.Component component : root.getComponents()) {
            if (component instanceof javax.swing.AbstractButton button
                    && text.equals(button.getText())) {
                return button;
            }
            if (component instanceof java.awt.Container container) {
                javax.swing.AbstractButton found = findButtonByText(container, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static javax.swing.JButton findButton(
            java.awt.Container root, String tooltip) {
        for (java.awt.Component component : root.getComponents()) {
            if (component instanceof javax.swing.JButton button
                    && tooltip.equals(button.getToolTipText())) {
                return button;
            }
            if (component instanceof java.awt.Container container) {
                javax.swing.JButton found = findButton(container, tooltip);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static <T extends java.awt.Component> T findComponent(
            java.awt.Container root, Class<T> type) {
        for (java.awt.Component component : root.getComponents()) {
            if (type.isInstance(component)) {
                return type.cast(component);
            }
            if (component instanceof java.awt.Container container) {
                T found = findComponent(container, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static ContextStatsLabel findContextStatsLabel(java.awt.Container root) {
        for (java.awt.Component c : root.getComponents()) {
            if (c instanceof ContextStatsLabel) {
                return (ContextStatsLabel) c;
            }
            if (c instanceof java.awt.Container) {
                ContextStatsLabel found = findContextStatsLabel((java.awt.Container) c);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void flushEdt() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
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
