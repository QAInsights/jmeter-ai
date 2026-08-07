package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import static org.junit.jupiter.api.Assertions.*;

class CodeBlockRendererTest {

    @Test
    void render_insertsCodePanelWithCopyButton() throws Exception {
        StyledDocument doc = new DefaultStyledDocument();

        CodeBlockRenderer.render(doc, "int x = 1;", "java");

        assertNotNull(findCopyButton(doc), "rendered code panel must contain a Copy button");
    }

    @Test
    void copyButton_showsCopiedCheckmarkFeedback() throws Exception {
        StyledDocument doc = new DefaultStyledDocument();
        CodeBlockRenderer.render(doc, "int x = 1;", "java");
        JButton copyButton = findCopyButton(doc);
        assertNotNull(copyButton);

        assertDoesNotThrow(() -> {
            for (ActionListener al : copyButton.getActionListeners()) {
                al.actionPerformed(new ActionEvent(copyButton, ActionEvent.ACTION_PERFORMED, "copy"));
            }
        });

        assertEquals("Copied ✓", copyButton.getText());
    }

    private static JButton findCopyButton(StyledDocument doc) {
        for (int i = 0; i < doc.getLength(); i++) {
            javax.swing.text.Element elem = doc.getCharacterElement(i);
            Object comp = StyleConstants.getComponent(elem.getAttributes());
            if (comp instanceof JPanel) {
                for (Component child : ((JPanel) comp).getComponents()) {
                    if (child instanceof JPanel) {
                        for (Component sub : ((JPanel) child).getComponents()) {
                            if (sub instanceof JButton && "Copy".equals(((JButton) sub).getText())) {
                                return (JButton) sub;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }
}
