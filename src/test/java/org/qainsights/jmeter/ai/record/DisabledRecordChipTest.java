package org.qainsights.jmeter.ai.record;

import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JToggleButton;

import static org.junit.jupiter.api.Assertions.*;

class DisabledRecordChipTest {

    @Test
    void create_isDisabledRecordToggleWithPropertyTooltip() {
        JComponent chip = DisabledRecordChip.create();
        assertInstanceOf(JToggleButton.class, chip);
        JToggleButton button = (JToggleButton) chip;
        assertEquals("Record", button.getText());
        assertFalse(button.isEnabled());
        assertFalse(button.isSelected());
        assertTrue(button.getPreferredSize().width
                >= org.qainsights.jmeter.ai.gui.theme.UiTokens.HEADER_TEXT_BUTTON_WIDTH);
        assertEquals(org.qainsights.jmeter.ai.gui.theme.UiTokens.HEADER_CONTROL_HEIGHT,
                button.getPreferredSize().height);
        assertEquals(DisabledRecordChip.TOOLTIP, button.getToolTipText());
        assertTrue(button.getToolTipText().contains("jmeter.ai.record.enabled=true"));
    }

    @Test
    void preferredWidthExpandsToFitLargeFont() {
        JToggleButton button = (JToggleButton) DisabledRecordChip.create();
        button.setFont(button.getFont().deriveFont(30f));

        int textWidthWithInsets = button.getFontMetrics(button.getFont()).stringWidth(button.getText())
                + button.getInsets().left + button.getInsets().right;
        assertTrue(button.getPreferredSize().width
                > org.qainsights.jmeter.ai.gui.theme.UiTokens.HEADER_TEXT_BUTTON_WIDTH);
        assertTrue(button.getPreferredSize().width >= textWidthWithInsets);
        assertEquals(org.qainsights.jmeter.ai.gui.theme.UiTokens.HEADER_CONTROL_HEIGHT,
                button.getPreferredSize().height);
    }

    @Test
    void create_returnsFreshInstances() {
        assertNotSame(DisabledRecordChip.create(), DisabledRecordChip.create());
    }
}
