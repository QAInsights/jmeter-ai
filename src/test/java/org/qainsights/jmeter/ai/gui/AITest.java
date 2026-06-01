package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.event.ActionEvent;

import static org.junit.jupiter.api.Assertions.*;

class AITest {

    @Test
    void testAIActionProperties() {
        AI aiAction = new AI();

        assertEquals("AI", aiAction.getValue(Action.NAME));
        assertEquals("ai", aiAction.getValue(Action.ACTION_COMMAND_KEY));
        assertEquals(AI.AI, aiAction.getValue(Action.ACCELERATOR_KEY));
        assertNotNull(aiAction.getValue(Action.SMALL_ICON));
    }

    @Test
    void testActionPerformed() {
        AI aiAction = new AI();
        ActionEvent event = new ActionEvent(this, ActionEvent.ACTION_PERFORMED, "ai");
        
        // This should run without throwing any exceptions
        assertDoesNotThrow(() -> aiAction.actionPerformed(event));
    }
}
