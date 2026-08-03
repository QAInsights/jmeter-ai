package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;

import javax.swing.JLabel;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LabelUtils}: labels carrying untrusted text must have
 * HTML rendering disabled so a leading {@code <html>} never activates Swing's
 * HTML kit (remote image loads, layout breakage).
 */
class LabelUtilsTest {

    @Test
    void plainLabelDisablesHtml() {
        JLabel label = LabelUtils.plain("some text");
        assertEquals(Boolean.TRUE, label.getClientProperty("html.disable"));
        assertEquals("some text", label.getText());
    }

    @Test
    void plainLabelWithIconDisablesHtml() {
        JLabel label = LabelUtils.plain("chip", AttachIcons.document(10), JLabel.LEFT);
        assertEquals(Boolean.TRUE, label.getClientProperty("html.disable"));
        assertEquals("chip", label.getText());
        assertNotNull(label.getIcon());
    }

    @Test
    void htmlPayloadSurvivesAsLiteralText() {
        String payload = "<html><img src=https://tracker.example/pixel>";
        JLabel label = LabelUtils.plain(payload);
        assertEquals(payload, label.getText());
        assertEquals(Boolean.TRUE, label.getClientProperty("html.disable"));
    }

    @Test
    void disableHtmlOnExistingLabel() {
        JLabel label = new JLabel("<html>crafted</html>");
        assertNull(label.getClientProperty("html.disable"));
        LabelUtils.disableHtml(label);
        assertEquals(Boolean.TRUE, label.getClientProperty("html.disable"));
    }
}
