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
        return isDark() ? new Color(0x8A, 0xB4, 0xF8) : new Color(0x1A, 0x73, 0xE8);
    }

    public static Color canvas() {
        return themeColor("TextPane.background",
                themeColor("Panel.background", Color.WHITE));
    }

    public static Color surface() {
        return blend(themeColor("Panel.background", canvas()), canvas(), 0.72f);
    }

    public static Color elevatedSurface() {
        Color base = canvas();
        return isDark() ? shift(base, 10) : blend(Color.WHITE, base, 0.82f);
    }

    public static Color subtleSurface() {
        Color base = canvas();
        return isDark() ? shift(base, 7) : shift(base, -5);
    }

    public static Color accentSoft() {
        return blend(accent(), canvas(), isDark() ? 0.20f : 0.10f);
    }

    public static Color accentHover() {
        return blend(accent(), canvas(), isDark() ? 0.30f : 0.17f);
    }

    public static Color selectedBackground() {
        return blend(accent(), canvas(), isDark() ? 0.28f : 0.14f);
    }

    public static Color onAccent() {
        return contrastRatio(Color.WHITE, accent()) >= contrastRatio(Color.BLACK, accent())
                ? Color.WHITE : Color.BLACK;
    }

    public static Color focusRing() {
        return accent();
    }

    public static Color separator() {
        return blend(border(), canvas(), isDark() ? 0.62f : 0.48f);
    }

    public static Color shadow() {
        return new Color(0, 0, 0, isDark() ? 72 : 28);
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
        return subtleSurface();
    }

    /** Subtle hover background for flat buttons, derived from the panel background. */
    public static Color hoverBackground() {
        Color base = surface();
        return isDark() ? shift(base, 12) : shift(base, -7);
    }

    /**
     * Background for the user's chat bubble: a restrained accent tint derived
     * from the active canvas so it remains quiet and readable in either theme.
     */
    public static Color userBubbleBackground() {
        return accentSoft();
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
    public static Color blend(Color a, Color b, float ratio) {
        float inv = 1f - ratio;
        return new Color(
                clamp(Math.round(a.getRed() * ratio + b.getRed() * inv)),
                clamp(Math.round(a.getGreen() * ratio + b.getGreen() * inv)),
                clamp(Math.round(a.getBlue() * ratio + b.getBlue() * inv)));
    }

    static double contrastRatio(Color a, Color b) {
        double lighter = Math.max(relativeLuminance(a), relativeLuminance(b));
        double darker = Math.min(relativeLuminance(a), relativeLuminance(b));
        return (lighter + 0.05) / (darker + 0.05);
    }

    private static double relativeLuminance(Color color) {
        double red = linearChannel(color.getRed() / 255.0);
        double green = linearChannel(color.getGreen() / 255.0);
        double blue = linearChannel(color.getBlue() / 255.0);
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue;
    }

    private static double linearChannel(double channel) {
        return channel <= 0.04045
                ? channel / 12.92
                : Math.pow((channel + 0.055) / 1.055, 2.4);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
