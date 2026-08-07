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
        boolean found = false;
        for (java.awt.Component area : row.getComponents()) {
            if (area instanceof javax.swing.JPanel) {
                for (java.awt.Component inner : ((javax.swing.JPanel) area).getComponents()) {
                    if (inner instanceof JLabel
                            && ((JLabel) inner).getText().contains("Enter to send")) {
                        found = true;
                    }
                }
            }
        }
        assertTrue(found, "the keyboard hint label must be present");
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
    void stopButtonSwapsWithHint() {
        InputOptionsRow row = new InputOptionsRow(null, attachmentBar, new JButton());
        assertFalse(row.isStopShowing(), "hint shows by default");

        java.util.concurrent.atomic.AtomicBoolean stopped = new java.util.concurrent.atomic.AtomicBoolean(false);
        row.showStop(() -> stopped.set(true));
        assertTrue(row.isStopShowing(), "stop replaces the hint while processing");

        row.hideStop();
        assertFalse(row.isStopShowing(), "the hint returns after processing");

        // showing again reuses the same stop button instance with the same action
        row.showStop(() -> stopped.set(true));
        assertTrue(row.isStopShowing());
    }
}
