package org.qainsights.jmeter.ai.agent.dev;

import org.junit.jupiter.api.Test;

import java.awt.event.ActionListener;

import static org.junit.jupiter.api.Assertions.*;

/** Minimal construction test for {@link UpdateElementPropertyDevMenuItem} (logic lives in the runner). */
class UpdateElementPropertyDevMenuItemTest {

    @Test
    void construct_setsLabelAndRegistersItselfAsListener() {
        UpdateElementPropertyDevMenuItem item = new UpdateElementPropertyDevMenuItem();
        assertEquals(UpdateElementPropertyDevMenuItem.LABEL, item.getText());

        ActionListener[] listeners = item.getActionListeners();
        assertEquals(1, listeners.length);
        assertSame(item, listeners[0]);
    }
}
