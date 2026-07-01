package org.qainsights.jmeter.ai.agent.dev;

import org.junit.jupiter.api.Test;

import java.awt.event.ActionListener;

import static org.junit.jupiter.api.Assertions.*;

/** Minimal construction test for {@link AddElementDevMenuItem} (logic lives in the runner). */
class AddElementDevMenuItemTest {

    @Test
    void construct_setsLabelAndRegistersItselfAsListener() {
        AddElementDevMenuItem item = new AddElementDevMenuItem();
        assertEquals(AddElementDevMenuItem.LABEL, item.getText());

        ActionListener[] listeners = item.getActionListeners();
        assertEquals(1, listeners.length);
        assertSame(item, listeners[0]);
    }
}
