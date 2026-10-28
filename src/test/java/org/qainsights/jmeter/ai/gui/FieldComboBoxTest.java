package org.qainsights.jmeter.ai.gui;

import java.awt.Font;

import javax.swing.JList;
import javax.swing.JLabel;

import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.gui.theme.UiTokens;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FieldComboBoxTest {

    @Test
    void keepsItsFieldUiAndBodyFontAfterUpdateUi() {
        FieldComboBox<String> combo = new FieldComboBox<>(
                new String[]{"one", "two"}, value -> "Label " + value);

        combo.updateUI();

        assertInstanceOf(FieldComboUI.class, combo.getUI());
        assertFalse(combo.isOpaque());
        assertEquals(UiTokens.body(javax.swing.UIManager.getFont("Label.font")), combo.getFont());
    }

    @Test
    void rendererUsesLabelsPaddingAndSelectedSurface() {
        FieldComboBox<String> combo = new FieldComboBox<>(
                new String[]{"one", "two"}, value -> "Label " + value);
        JList<String> list = new JList<>();
        JLabel selected = (JLabel) combo.getRenderer()
                .getListCellRendererComponent(list, "one", 0, true, false);
        java.awt.Color selectedBackground = selected.getBackground();
        java.awt.Insets selectedInsets = selected.getInsets();
        JLabel closed = (JLabel) combo.getRenderer()
                .getListCellRendererComponent(list, "one", -1, false, false);

        assertEquals("Label one", selected.getText());
        assertEquals(ThemeColors.selectedBackground(), selectedBackground);
        assertTrue(selectedInsets.top >= UiTokens.SPACE_2);
        assertTrue(selectedInsets.left >= UiTokens.SPACE_3);
        assertTrue(ThemeColors.contrastRatio(
                ThemeColors.foreground(), ThemeColors.selectedBackground()) >= 4.5);
        JLabel empty = (JLabel) new FieldComboBox<String>(value -> value)
                .getRenderer().getListCellRendererComponent(list, null, -1, false, false);
        assertEquals("", empty.getText());
        assertFalse(closed.isOpaque());
        assertEquals(0, closed.getInsets().left);
    }

    @Test
    void rendererUsesThePopupListsCurrentFont() {
        FieldComboBox<String> combo = new FieldComboBox<>(
                new String[]{"one"}, value -> value);
        JList<String> list = new JList<>();
        Font zoomedFont = combo.getFont().deriveFont(combo.getFont().getSize2D() * 2);
        list.setFont(zoomedFont);

        JLabel rendered = (JLabel) combo.getRenderer()
                .getListCellRendererComponent(list, "one", 0, false, false);
        assertEquals(zoomedFont, rendered.getFont());

        JLabel withoutList = (JLabel) combo.getRenderer()
                .getListCellRendererComponent(null, "one", 0, false, false);

        assertEquals(combo.getFont(), withoutList.getFont());
    }

    @Test
    void alignsPopupWithItsFieldShell() {
        FieldComboBox<String> combo = new FieldComboBox<>(
                new String[]{"one"}, value -> value);
        FieldShell shell = new FieldShell(combo);
        shell.setSize(300, 40);
        combo.setBounds(8, 0, 284, 32);

        java.awt.Rectangle popup = FieldComboUI.popupBoundsFor(combo, 0, 32, 284, 120);

        assertEquals(-combo.getX(), popup.x);
        assertEquals(shell.getHeight() + UiTokens.SPACE_1, popup.y);
        assertEquals(shell.getWidth(), popup.width);
    }

    @Test
    void popupHeightFitsVisibleRowsAndPopupInsets() {
        FieldComboBox<String> combo = new FieldComboBox<>(
                new String[]{"one", "two", "three", "four"}, value -> value);
        int cellHeight = FieldComboUI.fixedCellHeightFor(combo);
        java.awt.Insets insets = FieldComboUI.popupBorder().getBorderInsets(null);

        int popupHeight = FieldComboUI.popupHeightFor(
                combo.getItemCount(), combo.getMaximumRowCount(), cellHeight, insets);

        assertEquals(4 * cellHeight + insets.top + insets.bottom, popupHeight);

        combo.setFont(combo.getFont().deriveFont(combo.getFont().getSize2D() * 1.5f));
        assertTrue(FieldComboUI.fixedCellHeightFor(combo) > cellHeight);
    }

    @Test
    void popupPreferredSizeIncludesInsetsOnceForListsBelowAndAboveMaximumRows() {
        assertPopupPreferredSize(3, 5);
        assertPopupPreferredSize(8, 5);
    }

    private static void assertPopupPreferredSize(int itemCount, int maximumRowCount) {
        String[] items = new String[itemCount];
        for (int index = 0; index < itemCount; index++) {
            items[index] = "item " + index;
        }
        FieldComboBox<String> combo = new FieldComboBox<>(items, value -> value);
        combo.setMaximumRowCount(maximumRowCount);
        FieldShell shell = new FieldShell(combo);
        shell.setSize(300, 40);
        combo.setBounds(8, 0, 284, 32);
        FieldComboUI.FieldComboPopup popup = new FieldComboUI.FieldComboPopup(combo);
        java.awt.Insets insets = popup.getInsets();
        int cellHeight = FieldComboUI.fixedCellHeightFor(combo);

        java.awt.Rectangle bounds = popup.computePopupBounds(
                0, combo.getHeight(), combo.getWidth(), 120);
        popup.getComponent(0).setPreferredSize(bounds.getSize());
        java.awt.Dimension popupSize = popup.getPreferredSize();

        assertEquals(shell.getWidth(), popupSize.width);
        assertEquals(Math.min(itemCount, maximumRowCount) * cellHeight + insets.top + insets.bottom,
                popupSize.height);
    }
}
