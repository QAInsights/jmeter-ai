package org.qainsights.jmeter.ai.gui;

import java.awt.Component;

import javax.swing.JButton;
import javax.swing.JToolBar;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkloadDesignerMenuItemTest {

    @Test
    void toolbarPositionPrefersTerminalThenFeatherWandThenStart() {
        JButton wand = button(FeatherWandToolbar.TOGGLE_ACTION);
        JButton terminal = button("toggle_claude_code_panel");
        JButton start = button("start");

        assertEquals(2, WorkloadDesignerMenuItem.toolbarInsertPosition(
                new Component[] {wand, terminal, start}));
        assertEquals(1, WorkloadDesignerMenuItem.toolbarInsertPosition(
                new Component[] {wand, start}));
        assertEquals(1, WorkloadDesignerMenuItem.toolbarInsertPosition(
                new Component[] {button("other"), start}));
    }

    @Test
    void toolbarButtonInsertionDoesNotAddDuplicates() {
        JToolBar toolbar = new JToolBar();
        toolbar.add(button(FeatherWandToolbar.TOGGLE_ACTION));
        toolbar.add(button("toggle_claude_code_panel"));
        JButton loadButton = button(WorkloadDesignerMenuItem.TOOLBAR_ACTION_COMMAND);

        assertTrue(WorkloadDesignerMenuItem.insertToolbarButton(toolbar, loadButton));
        assertEquals(2, toolbar.getComponentIndex(loadButton));
        assertFalse(WorkloadDesignerMenuItem.insertToolbarButton(
                toolbar, button(WorkloadDesignerMenuItem.TOOLBAR_ACTION_COMMAND)));
        assertEquals(3, toolbar.getComponentCount());
    }

    @Test
    void loadIconIsAvailableAtToolbarAndMenuSizes() {
        for (int size : new int[] {16, 22, 32, 48}) {
            assertNotNull(WorkloadDesignerMenuItem.getLoadIcon(size));
            assertEquals(size, WorkloadDesignerMenuItem.getLoadIcon(size).getIconWidth());
            assertEquals(size, WorkloadDesignerMenuItem.getLoadIcon(size).getIconHeight());
        }
        assertEquals("Workload Designer: threads, ramp-up and throughput from a target load",
                WorkloadDesignerMenuItem.TOOLTIP);
    }

    private static JButton button(String actionCommand) {
        JButton button = new JButton();
        button.setActionCommand(actionCommand);
        return button;
    }
}
