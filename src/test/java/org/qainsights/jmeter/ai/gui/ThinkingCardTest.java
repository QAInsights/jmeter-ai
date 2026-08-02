package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThinkingCardTest {

    @Test
    void startsRunningAndExpanded() {
        ThinkingCard card = new ThinkingCard();
        try {
            assertTrue(card.isRunning());
            assertFalse(card.isCollapsed());
            assertEquals("", card.getText());
        } finally {
            card.dispose();
        }
    }

    @Test
    void appendTextAccumulatesReasoning() {
        ThinkingCard card = new ThinkingCard();
        try {
            card.appendText("Let me think");
            card.appendText(" about this…");
            assertEquals("Let me think about this…", card.getText());
        } finally {
            card.dispose();
        }
    }

    @Test
    void finishStopsSpinnerAndAutoCollapses() {
        ThinkingCard card = new ThinkingCard();
        try {
            card.appendText("reasoning");
            card.finish();

            assertFalse(card.isRunning());
            assertTrue(card.isCollapsed());

            // Finishing twice is safe
            card.finish();
            assertFalse(card.isRunning());
        } finally {
            card.dispose();
        }
    }

    @Test
    void collapseTogglesBodyVisibility() {
        ThinkingCard card = new ThinkingCard();
        try {
            card.setCollapsed(true);
            assertTrue(card.isCollapsed());
            card.setCollapsed(false);
            assertFalse(card.isCollapsed());
        } finally {
            card.dispose();
        }
    }
}
