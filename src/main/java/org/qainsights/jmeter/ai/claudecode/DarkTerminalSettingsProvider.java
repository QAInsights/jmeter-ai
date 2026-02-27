package org.qainsights.jmeter.ai.claudecode;

import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.emulator.ColorPalette;
import com.jediterm.terminal.emulator.ColorPaletteImpl;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;

/**
 * Dark-themed settings provider for the JediTerm terminal widget.
 * Provides a terminal appearance similar to VS Code's Dark+ theme.
 */
public class DarkTerminalSettingsProvider extends DefaultSettingsProvider {

    private static final TerminalColor BG = TerminalColor.rgb(30, 30, 30);
    private static final TerminalColor FG = TerminalColor.rgb(204, 204, 204);

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
        return 16.0f;
    }

    @Override
    public java.awt.Font getTerminalFont() {
        String osName = System.getProperty("os.name").toLowerCase();
        String fontName = "Monospaced";
        if (osName.contains("win")) {
            // Use JetBrains Mono if available, or a good monospace that supports emojis
            fontName = "JetBrains Mono";
            java.awt.Font font = new java.awt.Font(fontName, java.awt.Font.PLAIN, (int) getTerminalFontSize());
            if (!font.getFamily().equals(fontName)) {
                fontName = "Consolas";
            }
        } else if (osName.contains("mac")) {
            fontName = "Menlo";
        } else {
            fontName = "DejaVu Sans Mono";
        }
        return new java.awt.Font(fontName, java.awt.Font.PLAIN, (int) getTerminalFontSize());
    }
}
