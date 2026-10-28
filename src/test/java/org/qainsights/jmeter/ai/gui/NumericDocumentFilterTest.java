package org.qainsights.jmeter.ai.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import javax.swing.JTextField;
import org.junit.jupiter.api.Test;

class NumericDocumentFilterTest {
    @Test
    void acceptsOnlyWholeOrDecimalNumericEdits() throws Exception {
        JTextField decimal = new JTextField();
        NumericDocumentFilter.install(decimal, NumericDocumentFilter.Mode.DECIMAL);
        for (char character : "100fg".toCharArray()) {
            decimal.getDocument().insertString(decimal.getDocument().getLength(),
                    Character.toString(character), null);
        }
        assertEquals("100", decimal.getText());
        decimal.setText("");
        decimal.getDocument().insertString(0, "1.2.3", null);
        assertEquals("", decimal.getText());
        decimal.getDocument().insertString(0, "1.2345", null);
        assertEquals("", decimal.getText());
        decimal.getDocument().insertString(0, "1e2", null);
        assertEquals("", decimal.getText());
        decimal.getDocument().insertString(0, "-5", null);
        assertEquals("", decimal.getText());
        decimal.getDocument().insertString(0, "1234567890123", null);
        assertEquals("", decimal.getText());

        JTextField whole = new JTextField();
        NumericDocumentFilter.install(whole, NumericDocumentFilter.Mode.WHOLE);
        whole.getDocument().insertString(0, ".", null);
        assertEquals("", whole.getText());
    }

    @Test
    void formatsProgrammaticNumericValuesWithoutExponentNotation() {
        assertEquals("10000000", NumericDocumentFilter.format(1.0E7, NumericDocumentFilter.Mode.DECIMAL));
        assertEquals("2.5", NumericDocumentFilter.format(2.5000, NumericDocumentFilter.Mode.DECIMAL));
    }
}
