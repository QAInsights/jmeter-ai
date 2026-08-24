package org.qainsights.jmeter.ai.record;

import java.awt.Dimension;
import java.awt.Insets;
import javax.swing.JComponent;
import javax.swing.JToggleButton;
import org.qainsights.jmeter.ai.gui.theme.UiTokens;

/**
 * Dimmed Record control shown when {@code jmeter.ai.record.enabled} is false,
 * so users discover the feature without enabling the recording engine.
 * Does not start sessions or touch {@link RecordingSessionController}.
 */
public final class DisabledRecordChip {

    public static final String TOOLTIP =
            "Recording is disabled. Set jmeter.ai.record.enabled=true in user.properties and restart JMeter.";

    private DisabledRecordChip() {
    }

    /** A non-interactive Record toggle used only for discovery. */
    public static JComponent create() {
        JToggleButton button = new JToggleButton("Record");
        button.setEnabled(false);
        button.setSelected(false);
        button.setMargin(new Insets(2, 6, 2, 6));
        Dimension size = new Dimension(
                UiTokens.HEADER_TEXT_BUTTON_WIDTH, UiTokens.HEADER_CONTROL_HEIGHT);
        button.setPreferredSize(size);
        button.setMinimumSize(size);
        button.setMaximumSize(size);
        button.setToolTipText(TOOLTIP);
        button.setFocusable(false);
        return button;
    }
}
