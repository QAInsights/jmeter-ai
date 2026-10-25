package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractionModeSelectorTest {

    @Test
    void defaultsToAgentAndSwitchesImmediately() {
        InteractionModeSelector selector = new InteractionModeSelector(true);

        assertFalse(selector.chatButton().isSelected());
        assertTrue(selector.isAgentSelected());

        selector.chatButton().doClick();

        assertTrue(selector.chatButton().isSelected());
        assertFalse(selector.isAgentSelected());

        selector.agentButton().doClick();

        assertTrue(selector.isAgentSelected());
        assertEquals("Interaction mode",
                selector.getAccessibleContext().getAccessibleName());
    }

    @Test
    void agentChoiceIsDisabledWhenFeatureIsOff() {
        InteractionModeSelector selector = new InteractionModeSelector(false);

        assertFalse(selector.agentButton().isEnabled());
        assertTrue(selector.chatButton().isSelected());
        selector.agentButton().doClick();
        assertFalse(selector.isAgentSelected());

        selector.setModelSupportsAgent(true);

        assertFalse(selector.agentButton().isEnabled());
        assertEquals("Agent mode is off. Set jmeter.ai.agent.enabled=true in user.properties and restart JMeter",
                selector.agentButton().getToolTipText());
    }

    @Test
    void restoresAgentSelectionAfterAppleModelIsDeselected() {
        InteractionModeSelector selector = new InteractionModeSelector(true);
        assertTrue(selector.isAgentSelected());

        selector.setModelSupportsAgent(false);

        assertTrue(selector.chatButton().isSelected());
        assertFalse(selector.agentButton().isEnabled());
        assertEquals("Agent mode isn't available for Apple's on-device model. "
                + "Pick a cloud model, Codex, Claude Code or OpenCode to use Agent",
                selector.agentButton().getToolTipText());
        selector.agentButton().doClick();
        assertTrue(selector.chatButton().isSelected());

        selector.setModelSupportsAgent(true);

        assertTrue(selector.agentButton().isEnabled());
        assertTrue(selector.isAgentSelected());
        assertEquals("Allow the AI to use JMeter tools", selector.agentButton().getToolTipText());
    }

    @Test
    void keepsChatSelectedWhenUserChoseChatBeforeAppleModel() {
        InteractionModeSelector selector = new InteractionModeSelector(true);
        selector.chatButton().doClick();

        selector.setModelSupportsAgent(false);
        selector.setModelSupportsAgent(true);

        assertTrue(selector.chatButton().isSelected());
        assertFalse(selector.isAgentSelected());
    }
}
