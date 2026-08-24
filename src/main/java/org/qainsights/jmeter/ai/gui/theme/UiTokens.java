package org.qainsights.jmeter.ai.gui.theme;

import java.awt.Font;
import javax.swing.UIManager;

public final class UiTokens {

    public static final int SPACE_1 = 4;
    public static final int SPACE_2 = 8;
    public static final int SPACE_3 = 12;
    public static final int SPACE_4 = 16;
    public static final int SPACE_5 = 20;
    public static final int SPACE_6 = 24;
    public static final int SPACE_8 = 32;

    public static final int RADIUS_SMALL = 8;
    public static final int RADIUS_MEDIUM = 12;
    public static final int RADIUS_LARGE = 18;

    public static final int CONTROL_HEIGHT = 32;
    public static final int ICON_BUTTON_SIZE = 32;
    public static final int BUTTON_COMPACT_HORIZONTAL_INSET = 6;
    public static final int BUTTON_STANDARD_HORIZONTAL_INSET = 11;
    public static final int BUTTON_STANDARD_VERTICAL_INSET = 5;

    public static final int HEADER_COMPACT_WIDTH = 390;
    public static final int HEADER_CONTROL_HEIGHT = 28;
    public static final int HEADER_TEXT_BUTTON_WIDTH = 64;
    public static final int HEADER_ACTION_GAP = 4;
    public static final int HINT_VISIBILITY_WIDTH = 430;
    public static final int WELCOME_DEFAULT_WIDTH = 420;
    public static final int WELCOME_MIN_WIDTH = 220;
    public static final int WELCOME_MIN_HEIGHT = 180;
    public static final int SUGGESTION_ROW_HEIGHT = 48;
    public static final int REASONING_CONTROL_GAP = 6;
    public static final int EFFORT_MIN_WIDTH = 80;
    public static final int EFFORT_MIN_HEIGHT = 28;
    public static final int MODEL_SELECTOR_ICON_GAP = 2;
    public static final int FAVORITE_WIDTH = 26;
    public static final int FAVORITE_HEIGHT = 28;
    public static final int PROMPT_DIALOG_MIN_WIDTH = 520;
    public static final int PROMPT_DIALOG_MIN_HEIGHT = 360;

    private UiTokens() {
    }

    public static Font title(Font base) {
        return font(base, Font.BOLD, 3f);
    }

    public static Font heading(Font base) {
        return font(base, Font.BOLD, 1f);
    }

    public static Font body(Font base) {
        return font(base, Font.PLAIN, 0f);
    }

    public static Font label(Font base) {
        return font(base, Font.BOLD, -1f);
    }

    public static Font caption(Font base) {
        return font(base, Font.PLAIN, -2f);
    }

    private static Font font(Font base, int style, float delta) {
        Font resolved = base != null ? base : UIManager.getFont("Label.font");
        if (resolved == null) {
            resolved = new Font(Font.DIALOG, Font.PLAIN, 12);
        }
        return resolved.deriveFont(style, Math.max(10f, resolved.getSize2D() + delta));
    }
}
