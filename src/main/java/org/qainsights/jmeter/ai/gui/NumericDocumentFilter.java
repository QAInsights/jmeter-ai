package org.qainsights.jmeter.ai.gui;

import java.math.BigDecimal;
import java.math.RoundingMode;

import javax.swing.JComponent;
import javax.swing.UIManager;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.DocumentFilter;
import javax.swing.text.JTextComponent;

public final class NumericDocumentFilter extends DocumentFilter {
    public static final int MAX_LENGTH = 12;

    private final JTextComponent component;
    private final Mode mode;
    private boolean bypassLength;

    public NumericDocumentFilter(JTextComponent component, Mode mode) {
        this.component = component;
        this.mode = mode;
    }

    public static void install(JTextComponent component, Mode mode) {
        if (component.getDocument() instanceof AbstractDocument document) {
            document.setDocumentFilter(new NumericDocumentFilter(component, mode));
        }
    }

    public static void setTextUnbounded(JTextComponent component, String text) {
        if (component.getDocument() instanceof AbstractDocument document
                && document.getDocumentFilter() instanceof NumericDocumentFilter filter) {
            filter.bypassLength = true;
            try {
                String value = text == null ? "" : text;
                if (filter.valid(value, true)) {
                    component.setText(text);
                } else {
                    filter.provideErrorFeedback();
                }
            } finally {
                filter.bypassLength = false;
            }
        } else {
            component.setText(text);
        }
    }

    public static String format(double value, Mode mode) {
        return format(BigDecimal.valueOf(value), mode);
    }

    public static String format(long value, Mode mode) {
        return format(BigDecimal.valueOf(value), mode);
    }

    private static String format(BigDecimal value, Mode mode) {
        BigDecimal rounded = value.setScale(mode == Mode.WHOLE ? 0 : 3, RoundingMode.HALF_UP);
        return rounded.stripTrailingZeros().toPlainString();
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
        if (valid(candidate, bypassLength || inserted.isEmpty())) {
            bypass.replace(offset, length, inserted, attributes);
        } else {
            provideErrorFeedback();
        }
    }

    private boolean valid(String value, boolean allowOverlength) {
        if (!allowOverlength && value.length() > MAX_LENGTH) {
            return false;
        }
        boolean decimalPoint = false;
        int fractionalDigits = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character >= '0' && character <= '9') {
                if (decimalPoint) {
                    fractionalDigits++;
                }
            } else if (mode == Mode.DECIMAL && character == '.' && !decimalPoint) {
                decimalPoint = true;
            } else {
                return false;
            }
        }
        return mode == Mode.WHOLE || fractionalDigits <= 3;
    }

    private void provideErrorFeedback() {
        if (UIManager.getLookAndFeel() != null) {
            UIManager.getLookAndFeel().provideErrorFeedback((JComponent) component);
        }
    }

    public enum Mode {
        WHOLE,
        DECIMAL
    }
}
