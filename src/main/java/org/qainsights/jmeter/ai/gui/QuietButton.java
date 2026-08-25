package org.qainsights.jmeter.ai.gui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import javax.swing.BorderFactory;
import javax.swing.ButtonModel;
import javax.swing.JButton;
import javax.swing.UIManager;
import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.gui.theme.UiTokens;

class QuietButton extends JButton {

    enum Kind {
        GHOST,
        OUTLINED,
        TONAL,
        PRIMARY
    }

    private final Kind kind;
    private boolean compact;
    private boolean bodyFont;
    private boolean iconOnly;
    private int iconButtonSize = UiTokens.ICON_BUTTON_SIZE;

    QuietButton(String text) {
        this(text, Kind.GHOST);
    }

    QuietButton(String text, Kind kind) {
        super(text);
        this.kind = kind == null ? Kind.GHOST : kind;
        configure();
    }

    QuietButton compact() {
        compact = true;
        configure();
        return this;
    }

    QuietButton bodyFont() {
        bodyFont = true;
        configure();
        return this;
    }

    QuietButton iconOnly() {
        return iconOnly(UiTokens.ICON_BUTTON_SIZE);
    }

    QuietButton iconOnly(int size) {
        iconOnly = true;
        iconButtonSize = size;
        configure();
        Dimension dimension = new Dimension(size, size);
        setPreferredSize(dimension);
        setMinimumSize(dimension);
        setMaximumSize(dimension);
        return this;
    }

    Kind kind() {
        return kind;
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (kind != null) {
            configure();
        }
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension preferred = super.getPreferredSize();
        if (iconOnly) {
            return new Dimension(iconButtonSize, iconButtonSize);
        }
        return new Dimension(preferred.width, Math.max(UiTokens.CONTROL_HEIGHT, preferred.height));
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            ButtonModel model = getModel();
            Color fill = fillFor(model);
            int inset = 1;
            int width = getWidth() - inset * 2;
            int height = getHeight() - inset * 2;
            int arc = UiTokens.RADIUS_SMALL;
            if (fill != null && width > 0 && height > 0) {
                g2.setColor(fill);
                g2.fillRoundRect(inset, inset, width, height, arc, arc);
            }
            if ((kind == Kind.OUTLINED || kind == Kind.TONAL) && isEnabled()) {
                g2.setColor(kind == Kind.OUTLINED
                        ? ThemeColors.separator()
                        : ThemeColors.blend(
                                ThemeColors.accent(), ThemeColors.separator(), 0.22f));
                g2.setStroke(new BasicStroke(1f));
                g2.drawRoundRect(inset, inset, Math.max(0, width - 1), Math.max(0, height - 1), arc, arc);
            }
            if (isFocusOwner()) {
                g2.setColor(ThemeColors.focusRing());
                g2.setStroke(new BasicStroke(1.6f));
                g2.drawRoundRect(inset, inset, Math.max(0, width - 1), Math.max(0, height - 1), arc, arc);
            }
        } finally {
            g2.dispose();
        }
        super.paintComponent(graphics);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (kind != null) {
            setForeground(foregroundFor());
        }
    }

    private void configure() {
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        int horizontalInset = compact
                ? UiTokens.BUTTON_COMPACT_HORIZONTAL_INSET
                : UiTokens.BUTTON_STANDARD_HORIZONTAL_INSET;
        int verticalInset = iconOnly ? 0 : UiTokens.BUTTON_STANDARD_VERTICAL_INSET;
        horizontalInset = iconOnly ? 0 : horizontalInset;
        setMargin(new Insets(0, 0, 0, 0));
        setBorder(BorderFactory.createEmptyBorder(
                verticalInset, horizontalInset, verticalInset, horizontalInset));
        Font base = UIManager.getFont("Button.font");
        Font resolved = base != null ? base : getFont();
        setFont(bodyFont
                ? UiTokens.body(resolved)
                : compact ? UiTokens.caption(resolved) : UiTokens.label(resolved));
        setForeground(foregroundFor());
    }

    private Color fillFor(ButtonModel model) {
        Color fill = null;
        if (kind == Kind.PRIMARY) {
            fill = ThemeColors.accent();
        } else if (kind == Kind.TONAL) {
            fill = ThemeColors.accentSoft();
        } else if (kind == Kind.OUTLINED) {
            fill = ThemeColors.elevatedSurface();
        } else if (model.isRollover() || model.isPressed()) {
            fill = ThemeColors.hoverBackground();
        }
        if (fill == null) {
            return null;
        }
        if (!isEnabled()) {
            return ThemeColors.blend(fill, ThemeColors.canvas(), 0.42f);
        }
        if (model.isPressed()) {
            return ThemeColors.blend(ThemeColors.foreground(), fill, 0.10f);
        }
        if (model.isRollover()) {
            if (kind == Kind.PRIMARY) {
                return ThemeColors.blend(ThemeColors.foreground(), fill, 0.07f);
            }
            return kind == Kind.TONAL ? ThemeColors.accentHover() : ThemeColors.hoverBackground();
        }
        return fill;
    }

    private Color foregroundFor() {
        if (!isEnabled()) {
            return ThemeColors.secondaryText();
        }
        if (kind == Kind.PRIMARY) {
            return ThemeColors.onAccent();
        }
        if (kind == Kind.TONAL) {
            return ThemeColors.accent();
        }
        return ThemeColors.foreground();
    }
}
