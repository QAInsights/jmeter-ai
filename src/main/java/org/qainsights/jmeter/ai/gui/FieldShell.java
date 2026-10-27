package org.qainsights.jmeter.ai.gui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.KeyboardFocusManager;
import java.awt.RenderingHints;
import java.beans.PropertyChangeListener;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.UIManager;

import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.gui.theme.UiTokens;

final class FieldShell extends JPanel {

    private final JComponent control;
    private final JComponent trailing;
    private final int controlHeight;
    private final PropertyChangeListener focusListener = event -> refreshFocus();
    private boolean focused;

    FieldShell(JComponent control) {
        this(control, null, UiTokens.CONTROL_HEIGHT);
    }

    FieldShell(JComponent control, String suffix) {
        this(control, suffix == null || suffix.isBlank() ? null : suffixLabel(suffix),
                UiTokens.CONTROL_HEIGHT);
    }

    FieldShell(JComponent control, JComponent trailing) {
        this(control, trailing, UiTokens.CONTROL_HEIGHT);
    }

    FieldShell(JComponent control, JComponent trailing, int controlHeight) {
        super(new BorderLayout(UiTokens.SPACE_2, 0));
        this.control = control;
        this.trailing = trailing;
        this.controlHeight = Math.max(UiTokens.CONTROL_HEIGHT, controlHeight);
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(0, UiTokens.SPACE_2, 0, UiTokens.SPACE_2));
        prepareControl(control);
        add(control, BorderLayout.CENTER);
        if (trailing != null) {
            JPanel trailingSlot = new CenteredSlot(trailing);
            add(trailingSlot, BorderLayout.EAST);
        }
    }

    private static final class CenteredSlot extends JPanel {

        private final JComponent content;

        private CenteredSlot(JComponent content) {
            super(new GridBagLayout());
            this.content = content;
            content.setOpaque(false);
            setOpaque(false);
            GridBagConstraints constraints = new GridBagConstraints();
            constraints.anchor = GridBagConstraints.CENTER;
            add(content, constraints);
        }

        @Override
        public Dimension getMinimumSize() {
            return content.getPreferredSize();
        }

        @Override
        public Dimension getPreferredSize() {
            return content.getPreferredSize();
        }
    }

    private static JLabel suffixLabel(String suffix) {
        JLabel label = new JLabel(suffix);
        label.setFont(UiTokens.caption(UIManager.getFont("Label.font")));
        label.setForeground(ThemeColors.secondaryText());
        return label;
    }

    private static void prepareControl(JComponent control) {
        if (control instanceof JTextField field) {
            field.setBorder(BorderFactory.createEmptyBorder());
            field.setOpaque(false);
        } else if (control instanceof JTextArea area) {
            area.setBorder(BorderFactory.createEmptyBorder());
            area.setOpaque(false);
        } else if (control instanceof JComboBox<?> combo) {
            combo.setBorder(BorderFactory.createEmptyBorder());
            combo.setOpaque(false);
        }
    }

    void refreshTheme() {
        prepareControl(control);
        if (trailing instanceof JLabel label) {
            label.setFont(UiTokens.caption(UIManager.getFont("Label.font")));
            label.setForeground(ThemeColors.secondaryText());
        }
        refreshFocus();
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension preferred = super.getPreferredSize();
        return new Dimension(preferred.width, Math.max(controlHeight, preferred.height));
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (control != null) {
            control.setEnabled(enabled);
        }
        if (trailing != null) {
            trailing.setEnabled(enabled);
        }
        repaint();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addPropertyChangeListener("focusOwner", focusListener);
        refreshFocus();
    }

    @Override
    public void removeNotify() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .removePropertyChangeListener("focusOwner", focusListener);
        super.removeNotify();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int width = getWidth() - 1;
            int height = getHeight() - 1;
            if (width <= 0 || height <= 0) {
                return;
            }
            Color background = ThemeColors.surface();
            if (!isEnabled()) {
                background = ThemeColors.blend(background, ThemeColors.canvas(), 0.55f);
            }
            g.setColor(background);
            g.fillRoundRect(0, 0, width, height, UiTokens.RADIUS_SMALL * 2,
                    UiTokens.RADIUS_SMALL * 2);
            g.setColor(focused ? ThemeColors.focusRing() : ThemeColors.separator());
            g.setStroke(new BasicStroke(focused ? 1.6f : 1f));
            g.drawRoundRect(focused ? 1 : 0, focused ? 1 : 0,
                    width - (focused ? 2 : 0), height - (focused ? 2 : 0),
                    UiTokens.RADIUS_SMALL * 2, UiTokens.RADIUS_SMALL * 2);
        } finally {
            g.dispose();
        }
    }

    private void refreshFocus() {
        java.awt.Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        boolean within = focusOwner != null
                && (focusOwner == this || javax.swing.SwingUtilities.isDescendingFrom(focusOwner, this));
        if (focused != within) {
            focused = within;
            repaint();
        }
    }
}
