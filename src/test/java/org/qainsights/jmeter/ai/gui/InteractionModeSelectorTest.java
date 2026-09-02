package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractionModeSelectorTest {

    @Test
    void defaultsToChatAndSwitchesImmediately() {
        InteractionModeSelector selector = new InteractionModeSelector(true);

        assertTrue(selector.chatButton().isSelected());
        assertFalse(selector.isAgentSelected());

        selector.agentButton().doClick();

        assertFalse(selector.chatButton().isSelected());
        assertTrue(selector.isAgentSelected());

        selector.chatButton().doClick();

        assertTrue(selector.chatButton().isSelected());
        assertFalse(selector.isAgentSelected());
        assertEquals("Interaction mode",
                selector.getAccessibleContext().getAccessibleName());
    }

    @Test
    void agentChoiceIsDisabledWhenFeatureIsOff() {
        InteractionModeSelector selector = new InteractionModeSelector(false);

        assertFalse(selector.agentButton().isEnabled());
        selector.agentButton().doClick();
        assertFalse(selector.isAgentSelected());
    }
}
