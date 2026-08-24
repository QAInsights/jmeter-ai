package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.qainsights.jmeter.ai.service.attach.AttachmentRegistry;
import org.qainsights.jmeter.ai.utils.AiConfig;

import javax.swing.JButton;
import javax.swing.JLabel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link InputOptionsRow}: the paperclip lands in the options
 * strip, the hint label is present, and future options can be added.
 */
class InputOptionsRowTest {

    private MockedStatic<AiConfig> aiConfigMockedStatic;
    private AttachmentBar attachmentBar;

    @BeforeEach
    void setUp() {
        aiConfigMockedStatic = mockStatic(AiConfig.class);
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        attachmentBar = new AttachmentBar(new AttachmentRegistry(), message -> {});
    }

    @AfterEach
    void tearDown() {
        aiConfigMockedStatic.close();
    }

    @Test
    void paperclipIsConfiguredAndPlaced() {
        JButton attachButton = new JButton("");
        InputOptionsRow row = new InputOptionsRow(null, attachmentBar, attachButton);

        assertNotNull(attachButton.getIcon(), "paperclip icon must be set");
        assertEquals("Attach a file (jmeter.log, results, or any text file)",
                attachButton.getToolTipText());
        assertEquals(1, attachButton.getActionListeners().length);
        assertEquals(1, row.getOptionCount());
    }

    @Test
    void hintLabelPresent() {
        InputOptionsRow row = new InputOptionsRow(null, attachmentBar, new JButton());
        assertTrue(containsLabel(row, "Enter to send"),
                "the keyboard hint label must be present");
    }

    @Test
    void futureOptionsCanBeAdded() {
        InputOptionsRow row = new InputOptionsRow(null, attachmentBar, new JButton());
        assertEquals(1, row.getOptionCount());
        row.addOption(new JButton("mic"));
        assertEquals(2, row.getOptionCount());
    }

    @Test
    void statsComponentSurvivesStopButtonSwap() {
        InputOptionsRow row = new InputOptionsRow(null, attachmentBar, new JButton());
        assertNull(row.statsComponent());

        ContextStatsLabel stats = new ContextStatsLabel();
        row.setStatsComponent(stats);
        assertSame(stats, row.statsComponent());

        // the stop-button swap wipes only the hint panel, not the stats slot
        row.showStop(() -> { });
        assertSame(stats, row.statsComponent());
        row.hideStop();
        assertSame(stats, row.statsComponent());
    }

    @Test
    void sendActionLivesInComposerAndRunsCallback() {
        java.util.concurrent.atomic.AtomicBoolean sent = new java.util.concurrent.atomic.AtomicBoolean(false);
        InputOptionsRow row = new InputOptionsRow(
                null, attachmentBar, new JButton(), () -> sent.set(true));

        assertTrue(row.sendButton() instanceof QuietButton);
        assertEquals(QuietButton.Kind.PRIMARY, ((QuietButton) row.sendButton()).kind());
        assertEquals("Send message", row.sendButton().getToolTipText());
        row.sendButton().doClick();
        assertTrue(sent.get());
    }

    @Test
    void keyboardHintYieldsSpaceOnNarrowPanels() {
        InputOptionsRow row = new InputOptionsRow(null, attachmentBar, new JButton());
        row.updateHintVisibility(360);
        assertFalse(row.isHintVisible());

        row.updateHintVisibility(500);
        assertTrue(row.isHintVisible());
    }

    @Test
    void modelRowIsInstalledInsideComposerOptions() {
        InputOptionsRow row = new InputOptionsRow(null, attachmentBar, new JButton());
        javax.swing.JPanel modelRow = new javax.swing.JPanel();
        row.setModelRow(modelRow);
        assertTrue(javax.swing.SwingUtilities.isDescendingFrom(modelRow, row));
    }

    @Test
    void stopButtonSwapsWithSendAction() {
        InputOptionsRow row = new InputOptionsRow(null, attachmentBar, new JButton());
        assertFalse(row.isStopShowing(), "send action shows by default");

        java.util.concurrent.atomic.AtomicBoolean stopped = new java.util.concurrent.atomic.AtomicBoolean(false);
        row.showStop(() -> stopped.set(true));
        assertTrue(row.isStopShowing(), "stop replaces send while processing");

        row.hideStop();
        assertFalse(row.isStopShowing(), "send returns after processing");

        // showing again reuses the same stop button instance with the same action
        row.showStop(() -> stopped.set(true));
        assertTrue(row.isStopShowing());
    }

    private static boolean containsLabel(java.awt.Container root, String text) {
        for (java.awt.Component component : root.getComponents()) {
            if (component instanceof JLabel label && label.getText().contains(text)) {
                return true;
            }
            if (component instanceof java.awt.Container container
                    && containsLabel(container, text)) {
                return true;
            }
        }
        return false;
    }
}
