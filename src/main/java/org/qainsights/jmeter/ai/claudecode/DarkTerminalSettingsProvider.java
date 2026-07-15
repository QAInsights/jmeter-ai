package org.qainsights.jmeter.ai.claudecode;

import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.emulator.ColorPalette;
import com.jediterm.terminal.emulator.ColorPaletteImpl;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Font;

/**
 * Dark-themed settings provider for the JediTerm terminal widget.
 * Provides a terminal appearance similar to VS Code's Dark+ theme.
 * <p>
 * The terminal font can be configured via JMeter properties, and when a
 * CJK-capable font is not available the provider will fall back to one of the
 * common CJK monospaced / system UI fonts.
 */
public class DarkTerminalSettingsProvider extends DefaultSettingsProvider {

    private static final Logger log = LoggerFactory.getLogger(DarkTerminalSettingsProvider.class);

    private static final TerminalColor BG = TerminalColor.rgb(30, 30, 30);
    private static final TerminalColor FG = TerminalColor.rgb(204, 204, 204);

    private static final String PROP_FONT_FAMILY = "jmeter.ai.terminal.font.family";
    private static final String PROP_FONT_NAME = "jmeter.ai.terminal.font";
    private static final String PROP_FONT_SIZE = "jmeter.ai.terminal.font.size";
    private static final String PROP_CJK_FALLBACK = "jmeter.ai.terminal.font.cjk.fallback";

    private static final String DEFAULT_FONT_SIZE = "16.0";

    /**
     * Representative CJK sample used to test whether a font can display the
     * characters. Includes Simplified Chinese, Traditional Chinese, Japanese
     * (Hiragana/Katakana) and Korean.
     */
    private static final String CJK_SAMPLE =
            "\u4f60\u597d\u4e16\u754c\u7e41\u9ad4\u4e2d\u6587" +
            "\u3053\u3093\u306b\u3061\u306f\u30c6\u30b9\u30c8" +
            "\uc548\ub155\ud558\uc138\uc694";

    private static final String[] CJK_CANDIDATES = {
            // Monospaced CJK fonts (preferred)
            "Noto Sans Mono CJK SC",
            "Noto Sans Mono CJK TC",
            "Noto Sans Mono CJK JP",
            "Noto Sans Mono CJK KR",
            "WenQuanYi Zen Hei Mono",
            "WenQuanYi Micro Hei Mono",
            // Windows CJK fonts
            "NSimSun",
            "SimSun",
            "SimHei",
            "MS Gothic",
            "MS Mincho",
            "MingLiU",
            "MingLiU_HKSCS",
            "GulimChe",
            "DotumChe",
            "Malgun Gothic",
            // Fallback CJK UI fonts
            "Droid Sans Fallback",
            "Microsoft YaHei",
            "PingFang SC",
            "Heiti SC",
            "Hiragino Kaku Gothic ProN",
            "Apple SD Gothic Neo",
            // Java logical fonts as a final fallback
            Font.MONOSPACED,
            Font.DIALOG
    };

    @Override
    public TextStyle getDefaultStyle() {
        return new TextStyle(FG, BG);
    }

    @Override
    public TextStyle getFoundPatternColor() {
        return new TextStyle(
                TerminalColor.rgb(0, 0, 0),
                TerminalColor.rgb(255, 200, 50));
    }

    @Override
    public TextStyle getSelectionColor() {
        return new TextStyle(
                TerminalColor.rgb(255, 255, 255),
                TerminalColor.rgb(82, 82, 122));
    }

    @Override
    public ColorPalette getTerminalColorPalette() {
        return ColorPaletteImpl.XTERM_PALETTE;
    }

    @Override
    public boolean useAntialiasing() {
        return true;
    }

    @Override
    public float getTerminalFontSize() {
        String value = AiConfig.getProperty(PROP_FONT_SIZE, DEFAULT_FONT_SIZE);
        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException | NullPointerException e) {
            return Float.parseFloat(DEFAULT_FONT_SIZE);
        }
    }

    @Override
    public Font getTerminalFont() {
        return resolveTerminalFont();
    }

    private Font resolveTerminalFont() {
        float size = getTerminalFontSize();
        String configuredFamily = getConfiguredFamily();
        boolean familyConfiguredByUser = configuredFamily != null;
        String family = familyConfiguredByUser ? configuredFamily : getDefaultPlatformFamily();

        Font selected = new Font(family, Font.PLAIN, (int) size);
        boolean cjkFallback = isCjkFallbackEnabled(familyConfiguredByUser);

        int selectedScore = cjkScore(selected);
        if (cjkFallback && selectedScore < CJK_SAMPLE.length()) {
            Font cjkFont = findCjkCapableFont(size);
            if (cjkFont != null && cjkScore(cjkFont) > selectedScore) {
                log.info("Terminal font '{}' does not support CJK; falling back to '{}'",
                        selected.getFamily(), cjkFont.getFamily());
                selected = cjkFont;
            }
        }

        log.info("Using terminal font: {} (size={})", selected.getFamily(), size);
        return selected;
    }

    private String getConfiguredFamily() {
        String family = trim(AiConfig.getProperty(PROP_FONT_FAMILY, null));
        if (family == null) {
            family = trim(AiConfig.getProperty(PROP_FONT_NAME, null));
        }
        return family;
    }

    private String getDefaultPlatformFamily() {
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("win")) {
            if (fontExists("JetBrains Mono")) {
                return "JetBrains Mono";
            }
            return "Consolas";
        }
        if (osName.contains("mac")) {
            return "Menlo";
        }
        return "DejaVu Sans Mono";
    }

    private boolean isCjkFallbackEnabled(boolean familyConfiguredByUser) {
        String value = trim(AiConfig.getProperty(PROP_CJK_FALLBACK, null));
        if (value != null) {
            return Boolean.parseBoolean(value);
        }
        // When no explicit font family is configured, auto-fallback to a CJK-capable font.
        return !familyConfiguredByUser;
    }

    private boolean fontExists(String name) {
        Font font = new Font(name, Font.PLAIN, 12);
        return font.getFamily().equalsIgnoreCase(name);
    }

    private int cjkScore(Font font) {
        if (font == null || CJK_SAMPLE.isEmpty()) {
            return 0;
        }
        int score = 0;
        for (int i = 0; i < CJK_SAMPLE.length(); i++) {
            if (font.canDisplay(CJK_SAMPLE.charAt(i))) {
                score++;
            }
        }
        return score;
    }

    private boolean canDisplayCjk(Font font) {
        return cjkScore(font) == CJK_SAMPLE.length();
    }

    private Font findCjkCapableFont(float size) {
        Font best = null;
        int bestScore = 0;
        for (String name : CJK_CANDIDATES) {
            Font candidate = new Font(name, Font.PLAIN, (int) size);
            if (!candidate.getFamily().equalsIgnoreCase(name)) {
                continue;
            }
            int score = cjkScore(candidate);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        value = value.trim();
        return value.isEmpty() ? null : value;
    }
}
