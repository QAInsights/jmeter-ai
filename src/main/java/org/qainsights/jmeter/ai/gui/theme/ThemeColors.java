package org.qainsights.jmeter.ai.gui.theme;

import java.awt.Color;
import javax.swing.UIManager;

/**
 * Central, theme-aware color palette for the plugin's UI.
 * <p>
 * JMeter ships with both light and dark look-and-feels; raw AWT colors such as
 * {@link Color#RED} or {@link Color#BLUE} are unreadable on one of the two.
 * This utility derives semantic colors (error, success, warning, info, ...)
 * from the <em>current</em> {@link UIManager} palette at call time, so values
 * always match the active theme - including after a live light/dark switch.
 * <p>
 * All methods are cheap and read the palette on every call by design: callers
 * should not cache the returned colors across look-and-feel changes.
 */
public final class ThemeColors {

    private ThemeColors() {
    }

    /**
     * Returns true when the active look-and-feel is a dark theme, judged by the
     * relative luminance of the standard panel background.
     */
    public static boolean isDark() {
        Color bg = themeColor("Panel.background", Color.WHITE);
        return luminance(bg) < 0.5;
    }

    /** Soft red on dark themes, deeper red on light themes. */
    public static Color error() {
        return isDark() ? new Color(0xE0, 0x6C, 0x75) : new Color(0xB3, 0x26, 0x1E);
    }

    /** Muted green readable on both dark and light backgrounds. */
    public static Color success() {
        return isDark() ? new Color(0x98, 0xC3, 0x79) : new Color(0x0F, 0x7B, 0x0F);
    }

    /** Amber/gold suitable for "in progress" or caution states. */
    public static Color warning() {
        return isDark() ? new Color(0xE5, 0xC0, 0x7B) : new Color(0x9D, 0x5D, 0x00);
    }

    /** Informational blue with adequate contrast on both themes. */
    public static Color info() {
        return isDark() ? new Color(0x61, 0xAF, 0xEF) : new Color(0x0B, 0x5C, 0xAD);
    }

    /** Brand accent (Gemini blue family), slightly lightened on dark themes. */
    public static Color accent() {
        return isDark() ? new Color(0x6F, 0xA8, 0xF8) : new Color(0x2A, 0x6A, 0xD4);
    }

    /** De-emphasized text (hints, placeholders, status lines). */
    public static Color secondaryText() {
        Color c = UIManager.getColor("Label.disabledForeground");
        if (c == null) {
            c = UIManager.getColor("textInactiveText");
        }
        if (c == null) {
            c = blend(themeColor("Label.foreground", Color.BLACK),
                    themeColor("Panel.background", Color.WHITE), 0.45f);
        }
        return c;
    }

    /** Standard component border color for the active theme. */
    public static Color border() {
        Color c = UIManager.getColor("Component.borderColor");
        if (c == null) {
            c = UIManager.getColor("Separator.foreground");
        }
        return c != null ? c : Color.LIGHT_GRAY;
    }

    /** Default text foreground for the active theme. */
    public static Color foreground() {
        return themeColor("Label.foreground", Color.BLACK);
    }

    /**
     * Background for code blocks: the panel background shifted slightly lighter
     * on dark themes and slightly darker on light themes.
     */
    public static Color codeBackground() {
        Color panelBg = UIManager.getColor("Panel.background");
        if (panelBg == null) {
            return new Color(240, 240, 240);
        }
        return luminance(panelBg) < 0.5 ? shift(panelBg, 20) : shift(panelBg, -15);
    }

    /** Subtle hover background for flat buttons, derived from the panel background. */
    public static Color hoverBackground() {
        Color panelBg = themeColor("Panel.background", Color.WHITE);
        return luminance(panelBg) < 0.5 ? shift(panelBg, 18) : shift(panelBg, -12);
    }

    /**
     * Background for the user's chat bubble: a professional light grey
     * (#DEDEDE) on light themes; on dark themes the panel background lifted
     * slightly so the bubble reads as a card without glaring.
     */
    public static Color userBubbleBackground() {
        if (!isDark()) {
            return new Color(0xDE, 0xDE, 0xDE);
        }
        return shift(themeColor("Panel.background", new Color(43, 43, 43)), 24);
    }

    /**
     * Reads a color from the current UIManager theme, falling back to a default
     * if the key is not defined by the active look-and-feel.
     */
    public static Color themeColor(String key, Color fallback) {
        Color color = UIManager.getColor(key);
        return color != null ? color : fallback;
    }

    /** Relative luminance in the 0-1 range (0 = black, 1 = white). */
    static double luminance(Color c) {
        return (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue()) / 255.0;
    }

    /** Shifts each RGB channel by {@code delta}, clamping to the 0-255 range. */
    static Color shift(Color c, int delta) {
        return new Color(
                clamp(c.getRed() + delta),
                clamp(c.getGreen() + delta),
                clamp(c.getBlue() + delta));
    }

    /** Linear blend of two colors; {@code ratio} is the weight of {@code a}. */
    static Color blend(Color a, Color b, float ratio) {
        float inv = 1f - ratio;
        return new Color(
                clamp(Math.round(a.getRed() * ratio + b.getRed() * inv)),
                clamp(Math.round(a.getGreen() * ratio + b.getGreen() * inv)),
                clamp(Math.round(a.getBlue() * ratio + b.getBlue() * inv)));
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
