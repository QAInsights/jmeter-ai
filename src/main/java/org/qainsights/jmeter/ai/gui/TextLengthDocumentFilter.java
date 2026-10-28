package org.qainsights.jmeter.ai.gui;

import javax.swing.JComponent;
import javax.swing.UIManager;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.DocumentFilter;
import javax.swing.text.JTextComponent;

public final class TextLengthDocumentFilter extends DocumentFilter {
    private final JTextComponent component;
    private final int maximumLength;

    public TextLengthDocumentFilter(JTextComponent component, int maximumLength) {
        this.component = component;
        this.maximumLength = maximumLength;
    }

    public static void install(JTextComponent component, int maximumLength) {
        if (component.getDocument() instanceof AbstractDocument document) {
            document.setDocumentFilter(new TextLengthDocumentFilter(component, maximumLength));
        }
    }

    @Override
    public void insertString(FilterBypass bypass, int offset, String text, AttributeSet attributes)
            throws BadLocationException {
        replace(bypass, offset, 0, text, attributes);
    }

    @Override
    public void remove(FilterBypass bypass, int offset, int length) throws BadLocationException {
        replace(bypass, offset, length, "", null);
    }

    @Override
    public void replace(FilterBypass bypass, int offset, int length, String text, AttributeSet attributes)
            throws BadLocationException {
        Document document = bypass.getDocument();
        String current = document.getText(0, document.getLength());
        String inserted = text == null ? "" : text;
        String candidate = current.substring(0, offset) + inserted
                + current.substring(offset + length);
        if (candidate.length() <= maximumLength) {
            bypass.replace(offset, length, inserted, attributes);
        } else if (UIManager.getLookAndFeel() != null) {
            UIManager.getLookAndFeel().provideErrorFeedback((JComponent) component);
        }
    }
}
