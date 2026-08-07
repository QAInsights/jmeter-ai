package org.qainsights.jmeter.ai.gui;

import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JRootPane;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import javax.swing.KeyStroke;

/**
 * Shared toolbar identity for the Feather Wand panel toggle button.
 * Kept small so tooltip text and placement matchers stay in lockstep;
 * {@code ClaudeCodeMenuItem} docks next to this button via
 * {@link #isFeatherWandButton(JButton)}.
 */
public final class FeatherWandToolbar {

    /** Action command on the JMeter toolbar toggle button. */
    public static final String TOGGLE_ACTION = "toggle_ai_panel";

    private FeatherWandToolbar() {
    }

    /**
     * Platform-aware tooltip: Ctrl+Shift+A on Windows/Linux, ⌘⇧A on macOS.
     */
    public static String toggleTooltip() {
        if (isMac()) {
            return "Feather Wand (\u2318\u21E7A)";
        }
        return "Feather Wand (Ctrl+Shift+A)";
    }

    /**
     * Key stroke that toggles the chat panel (Ctrl/Meta + Shift + A).
     */
    public static KeyStroke toggleKeyStroke() {
        int modifiers = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()
                | InputEvent.SHIFT_DOWN_MASK;
        return KeyStroke.getKeyStroke(KeyEvent.VK_A, modifiers);
    }

    /**
     * Binds {@link #toggleKeyStroke()} on the given root pane so the panel can
     * be toggled from anywhere in the window. This is the single binding
     * mechanism for the shortcut; the menu item keeps its legacy Alt+V
     * accelerator from the {@code AI} action. Safe no-op when either argument
     * is null.
     */
    static void installToggleBinding(JRootPane root, Runnable toggle) {
        if (root == null || toggle == null) {
            return;
        }
        String key = "feather-wand-toggle-panel";
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(toggleKeyStroke(), key);
        root.getActionMap().put(key, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                toggle.run();
            }
        });
    }

    /**
     * True when this toolbar button is the Feather Wand panel toggle.
     * Matches by action command (preferred) and by tooltip for both the
     * current "Feather Wand …" text and the legacy "FeatherWand" spelling.
     */
    public static boolean isFeatherWandButton(JButton button) {
        if (button == null) {
            return false;
        }
        String actionCommand = button.getModel().getActionCommand();
        if (TOGGLE_ACTION.equals(actionCommand)) {
            return true;
        }
        String tooltip = button.getToolTipText();
        if (tooltip == null) {
            return false;
        }
        return tooltip.contains("Feather Wand") || tooltip.contains("FeatherWand");
    }

    static boolean isMac() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase().contains("mac");
    }
}
