package org.qainsights.jmeter.ai.gui.theme;

import java.awt.Color;
import javax.swing.UIManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThemeColorsTest {

    private Color originalPanelBackground;

    @BeforeEach
    void saveTheme() {
        originalPanelBackground = UIManager.getColor("Panel.background");
    }

    @AfterEach
    void restoreTheme() {
        UIManager.put("Panel.background", originalPanelBackground);
    }

    private void forceDarkTheme() {
        UIManager.put("Panel.background", new Color(43, 43, 43));
    }

    private void forceLightTheme() {
        UIManager.put("Panel.background", new Color(242, 242, 242));
    }

    @Test
    void isDarkDetectsDarkAndLightBackgrounds() {
        forceDarkTheme();
        assertTrue(ThemeColors.isDark());

        forceLightTheme();
        assertFalse(ThemeColors.isDark());
    }

    @Test
    void semanticColorsAreNeverNull() {
        for (boolean dark : new boolean[] { true, false }) {
            if (dark) {
                forceDarkTheme();
            } else {
                forceLightTheme();
            }
            assertNotNull(ThemeColors.error());
            assertNotNull(ThemeColors.success());
            assertNotNull(ThemeColors.warning());
            assertNotNull(ThemeColors.info());
            assertNotNull(ThemeColors.accent());
            assertNotNull(ThemeColors.secondaryText());
            assertNotNull(ThemeColors.border());
            assertNotNull(ThemeColors.foreground());
            assertNotNull(ThemeColors.codeBackground());
            assertNotNull(ThemeColors.hoverBackground());
            assertNotNull(ThemeColors.userBubbleBackground());
        }
    }

    @Test
    void errorColorIsSofterOnDarkThemes() {
        forceDarkTheme();
        Color darkError = ThemeColors.error();
        forceLightTheme();
        Color lightError = ThemeColors.error();

        // Dark-theme red must be lighter (higher luminance) than the light-theme red.
        assertTrue(ThemeColors.luminance(darkError) > ThemeColors.luminance(lightError));
    }

    @Test
    void semanticColorsContrastWithTheirBackground() {
        forceDarkTheme();
        Color darkBg = UIManager.getColor("Panel.background");
        assertTrue(contrast(ThemeColors.error(), darkBg) > 0.25);
        assertTrue(contrast(ThemeColors.info(), darkBg) > 0.25);
        assertTrue(contrast(ThemeColors.success(), darkBg) > 0.25);

        forceLightTheme();
        Color lightBg = UIManager.getColor("Panel.background");
        assertTrue(contrast(ThemeColors.error(), lightBg) > 0.25);
        assertTrue(contrast(ThemeColors.info(), lightBg) > 0.25);
        assertTrue(contrast(ThemeColors.success(), lightBg) > 0.25);
    }

    @Test
    void userBubbleIsLightGreyOnLightThemeAndLiftedPanelOnDark() {
        forceLightTheme();
        assertEquals(new Color(0xDE, 0xDE, 0xDE), ThemeColors.userBubbleBackground());

        forceDarkTheme();
        Color panel = UIManager.getColor("Panel.background");
        assertTrue(ThemeColors.luminance(ThemeColors.userBubbleBackground())
                > ThemeColors.luminance(panel));
    }

    @Test
    void codeBackgroundIsLighterThanPanelOnDarkAndDarkerOnLight() {
        forceDarkTheme();
        Color panel = UIManager.getColor("Panel.background");
        assertTrue(ThemeColors.luminance(ThemeColors.codeBackground()) > ThemeColors.luminance(panel));

        forceLightTheme();
        panel = UIManager.getColor("Panel.background");
        assertTrue(ThemeColors.luminance(ThemeColors.codeBackground()) < ThemeColors.luminance(panel));
    }

    @Test
    void themeColorFallsBackWhenKeyMissing() {
        Color fallback = new Color(1, 2, 3);
        assertEquals(fallback, ThemeColors.themeColor("no.such.key.exists", fallback));
    }

    @Test
    void luminanceBounds() {
        assertEquals(0.0, ThemeColors.luminance(Color.BLACK), 1e-9);
        assertEquals(1.0, ThemeColors.luminance(Color.WHITE), 1e-9);
    }

    @Test
    void shiftClampsChannels() {
        Color nearWhite = ThemeColors.shift(new Color(250, 250, 250), 20);
        assertEquals(new Color(255, 255, 255), nearWhite);

        Color nearBlack = ThemeColors.shift(new Color(5, 5, 5), -20);
        assertEquals(new Color(0, 0, 0), nearBlack);
    }

    @Test
    void blendMixesColors() {
        Color mid = ThemeColors.blend(Color.WHITE, Color.BLACK, 0.5f);
        assertTrue(Math.abs(mid.getRed() - 128) <= 1);
        assertTrue(Math.abs(mid.getGreen() - 128) <= 1);
        assertTrue(Math.abs(mid.getBlue() - 128) <= 1);
    }

    private static double contrast(Color a, Color b) {
        return Math.abs(ThemeColors.luminance(a) - ThemeColors.luminance(b));
    }
}
