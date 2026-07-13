package org.qainsights.jmeter.ai.agent.dev;

import org.junit.jupiter.api.Test;

import java.awt.event.ActionListener;

import static org.junit.jupiter.api.Assertions.*;

/** Minimal construction test for {@link DeleteElementDevMenuItem} (logic lives in the runner). */
class DeleteElementDevMenuItemTest {

    @Test
    void construct_setsLabelAndRegistersItselfAsListener() {
        DeleteElementDevMenuItem item = new DeleteElementDevMenuItem();
        assertEquals(DeleteElementDevMenuItem.LABEL, item.getText());

        ActionListener[] listeners = item.getActionListeners();
        assertEquals(1, listeners.length);
        assertSame(item, listeners[0]);
    }
}
