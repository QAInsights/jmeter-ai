package org.qainsights.jmeter.ai.gui;

import javax.swing.JLabel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThinkingRowTest {

    @Test
    void rowShowsThinkingLabelAndSupportsThemeAndDisposal() {
        ThinkingRow row = new ThinkingRow();
        try {
            assertEquals(1, row.getComponentCount());
            assertTrue(((JLabel) row.getComponent(0)).getText().startsWith("Feather Wand is thinking"));
            assertDoesNotThrow(row::applyTheme);
        } finally {
            row.dispose();
        }
    }
}
