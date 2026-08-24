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
        assertEquals(org.qainsights.jmeter.ai.gui.theme.UiTokens.HEADER_TEXT_BUTTON_WIDTH,
                button.getPreferredSize().width);
        assertEquals(org.qainsights.jmeter.ai.gui.theme.UiTokens.HEADER_CONTROL_HEIGHT,
                button.getPreferredSize().height);
        assertEquals(DisabledRecordChip.TOOLTIP, button.getToolTipText());
        assertTrue(button.getToolTipText().contains("jmeter.ai.record.enabled=true"));
    }

    @Test
    void create_returnsFreshInstances() {
        assertNotSame(DisabledRecordChip.create(), DisabledRecordChip.create());
    }
}
