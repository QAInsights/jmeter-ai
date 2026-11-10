package org.qainsights.jmeter.ai.gui;

import javax.swing.JButton;
import javax.swing.JComponent;

/** Shared chat controls for the local parameterization dialog. */
public final class ParameterDialogControls {
    private ParameterDialogControls() {}

    public static JComponent field(JComponent control) {
        return new FieldShell(control);
    }

    public static JButton button(String text, boolean primary) {
        return new QuietButton(
                text, primary ? QuietButton.Kind.PRIMARY : QuietButton.Kind.OUTLINED);
    }
}
