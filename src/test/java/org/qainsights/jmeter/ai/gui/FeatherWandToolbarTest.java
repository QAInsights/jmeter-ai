package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;

import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class FeatherWandToolbarTest {

    @Test
    void toggleTooltip_containsBrandName() {
        // OS variance: the shortcut suffix branches on isMac() (⌘⇧A on macOS,
        // Ctrl+Shift+A elsewhere); only the platform-independent prefix is asserted.
        String tip = FeatherWandToolbar.toggleTooltip();
        assertTrue(tip.startsWith("Feather Wand"));
        assertFalse(tip.contains("FeatherWand"));
    }

    @Test
    void isMac_matchesOsNameProperty() {
        boolean expected = System.getProperty("os.name", "").toLowerCase().contains("mac");
        assertEquals(expected, FeatherWandToolbar.isMac());
    }

    @Test
    void installToggleBinding_registersStrokeAndInvokesToggle() throws Exception {
        AtomicBoolean toggled = new AtomicBoolean(false);
        SwingUtilities.invokeAndWait(() -> {
            JRootPane root = new JRootPane();
            FeatherWandToolbar.installToggleBinding(root, () -> toggled.set(true));

            Object key = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                    .get(FeatherWandToolbar.toggleKeyStroke());
            assertNotNull(key, "toggle keystroke must be bound on the root pane");

            Action action = root.getActionMap().get(key);
            assertNotNull(action, "bound key must map to an action");
            action.actionPerformed(new ActionEvent(root, ActionEvent.ACTION_PERFORMED, "toggle"));
        });
        assertTrue(toggled.get(), "invoking the bound action must run the toggle");
    }

    @Test
    void installToggleBinding_nullSafe() {
        assertDoesNotThrow(() -> FeatherWandToolbar.installToggleBinding(null, () -> { }));
        assertDoesNotThrow(() -> FeatherWandToolbar.installToggleBinding(new JRootPane(), null));
    }

    @Test
    void toggleKeyStroke_isShiftAWithMenuShortcut() {
        KeyStroke ks = FeatherWandToolbar.toggleKeyStroke();
        assertEquals(KeyEvent.VK_A, ks.getKeyCode());
        assertTrue((ks.getModifiers() & InputEvent.SHIFT_DOWN_MASK) != 0
                || (ks.getModifiers() & InputEvent.SHIFT_MASK) != 0);
    }

    @Test
    void isFeatherWandButton_matchesActionCommand() {
        JButton button = new JButton();
        button.setActionCommand(FeatherWandToolbar.TOGGLE_ACTION);
        assertTrue(FeatherWandToolbar.isFeatherWandButton(button));
    }

    @Test
    void isFeatherWandButton_matchesNewAndLegacyTooltip() {
        JButton modern = new JButton();
        modern.setToolTipText("Feather Wand (Ctrl+Shift+A)");
        assertTrue(FeatherWandToolbar.isFeatherWandButton(modern));

        JButton legacy = new JButton();
        legacy.setToolTipText("Toggle FeatherWand Panel");
        assertTrue(FeatherWandToolbar.isFeatherWandButton(legacy));
    }

    @Test
    void isFeatherWandButton_rejectsUnrelated() {
        JButton other = new JButton();
        other.setToolTipText("Toggle Claude Code Terminal");
        other.setActionCommand("toggle_claude_code_panel");
        assertFalse(FeatherWandToolbar.isFeatherWandButton(other));
        assertFalse(FeatherWandToolbar.isFeatherWandButton(null));
    }
}
