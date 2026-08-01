package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolActivityGroupTest {

    @Test
    void startsRunningAndExpanded() {
        ToolActivityGroup group = new ToolActivityGroup();
        try {
            assertTrue(group.isRunning());
            assertFalse(group.isCollapsed());
            assertEquals(0, group.getLineCount());
        } finally {
            group.dispose();
        }
    }

    @Test
    void addLineAccumulatesText() {
        ToolActivityGroup group = new ToolActivityGroup();
        try {
            group.addLine("add_element 'HTTP Request'");
            group.addLine("done in 12ms");

            assertEquals(2, group.getLineCount());
            assertEquals("add_element 'HTTP Request'\ndone in 12ms", group.getText());
        } finally {
            group.dispose();
        }
    }

    @Test
    void finishStopsSpinnerAndAutoCollapses() {
        ToolActivityGroup group = new ToolActivityGroup();
        try {
            group.addLine("line");
            group.finish();

            assertFalse(group.isRunning());
            assertTrue(group.isCollapsed());

            // Finishing twice is safe
            group.finish();
            assertFalse(group.isRunning());
        } finally {
            group.dispose();
        }
    }

    @Test
    void collapseTogglesBodyVisibility() {
        ToolActivityGroup group = new ToolActivityGroup();
        try {
            group.setCollapsed(true);
            assertTrue(group.isCollapsed());
            group.setCollapsed(false);
            assertFalse(group.isCollapsed());
        } finally {
            group.dispose();
        }
    }
}
