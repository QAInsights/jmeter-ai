package org.qainsights.jmeter.ai.gui;

import javax.swing.JLabel;

/**
 * Safety helper for labels that display untrusted text (AI responses, file
 * names from disk). Swing's {@link JLabel} auto-renders a leading {@code
 * <html>} tag through its HTML kit - including {@code <img src="...">}, which
 * can fetch remote resources - so any label fed model output or user file
 * names must force plain-text rendering via the {@code html.disable} client
 * property.
 */
final class LabelUtils {

    private LabelUtils() {
    }

    /** A label that always renders its text literally, never as HTML. */
    static JLabel plain(String text) {
        JLabel label = new JLabel(text);
        disableHtml(label);
        return label;
    }

    /** A label with icon that always renders its text literally, never as HTML. */
    static JLabel plain(String text, javax.swing.Icon icon, int horizontalAlignment) {
        JLabel label = new JLabel(text, icon, horizontalAlignment);
        disableHtml(label);
        return label;
    }

    /** Forces plain-text rendering on an existing label. */
    static void disableHtml(JLabel label) {
        label.putClientProperty("html.disable", Boolean.TRUE);
    }
}
