package org.qainsights.jmeter.ai.gui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Rectangle;
import java.util.Objects;
import java.util.function.Function;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.ListCellRenderer;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicComboPopup;
import javax.swing.plaf.basic.ComboPopup;

import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.gui.theme.UiTokens;

public class FieldComboBox<T> extends JComboBox<T> {

    private final Function<T, String> labeler;

    public FieldComboBox(T[] items, Function<T, String> labeler) {
        super(items);
        this.labeler = Objects.requireNonNull(labeler);
        updateUI();
    }

    public FieldComboBox(Function<T, String> labeler) {
        super();
        this.labeler = Objects.requireNonNull(labeler);
        updateUI();
    }

    @Override
    public void updateUI() {
        setUI(new FieldComboUI());
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder());
        setFont(UiTokens.body(UIManager.getFont("Label.font")));
        if (labeler != null) {
            setRenderer(new FieldComboRenderer<>(labeler, this));
        }
    }
}

final class FieldComboUI extends BasicComboBoxUI {

    private static final int POPUP_GAP = UiTokens.SPACE_1;

    @Override
    protected JButton createArrowButton() {
        JButton button = new JButton();
        Font font = comboBox == null ? UIManager.getFont("Label.font") : comboBox.getFont();
        int iconSize = Math.max(10, Math.round((font == null ? 12f : font.getSize2D()) * 0.75f));
        button.setIcon(ChevronIcons.down(iconSize));
        button.setFocusable(false);
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setOpaque(false);
        button.setBorder(BorderFactory.createEmptyBorder());
        button.setMargin(new Insets(0, 0, 0, 0));
        button.setPreferredSize(new Dimension(iconSize + UiTokens.SPACE_2, UiTokens.CONTROL_HEIGHT));
        return button;
    }

    @Override
    protected ComboPopup createPopup() {
        return new FieldComboPopup(comboBox);
    }

    @Override
    public void paintCurrentValueBackground(Graphics graphics, Rectangle bounds, boolean hasFocus) {
    }

    @Override
    public void paintCurrentValue(Graphics graphics, Rectangle bounds, boolean hasFocus) {
        ListCellRenderer<Object> renderer = comboBox.getRenderer();
        Component value = renderer.getListCellRendererComponent(
                listBox, comboBox.getSelectedItem(), -1, false, false);
        if (value instanceof JComponent component) {
            component.setOpaque(false);
            component.setBackground(new Color(0, 0, 0, 0));
            component.setForeground(comboBox.isEnabled()
                    ? ThemeColors.foreground() : ThemeColors.secondaryText());
            component.setFont(comboBox.getFont());
            component.setBorder(BorderFactory.createEmptyBorder());
        }
        currentValuePane.paintComponent(graphics, value, comboBox,
                bounds.x, bounds.y, bounds.width, bounds.height, false);
    }

    static Rectangle popupBoundsFor(JComboBox<?> combo, int x, int y, int width, int height) {
        if (combo.getParent() instanceof FieldShell shell) {
            return new Rectangle(-combo.getX(), shell.getHeight() - combo.getY() + POPUP_GAP,
                    shell.getWidth(), height);
        }
        return new Rectangle(x, y, width, height);
    }

    static Border popupBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ThemeColors.separator()),
                BorderFactory.createEmptyBorder(UiTokens.SPACE_1, UiTokens.SPACE_1,
                        UiTokens.SPACE_1, UiTokens.SPACE_1));
    }

    static int fixedCellHeightFor(JComboBox<?> combo) {
        ListCellRenderer<?> renderer = combo.getRenderer();
        if (renderer == null) {
            renderer = new DefaultListCellRenderer();
        }
        int height = 0;
        JList<?> probeList = new JList<>();
        probeList.setFont(combo.getFont());
        for (int index = 0; index < combo.getItemCount(); index++) {
            Object value = combo.getItemAt(index);
            @SuppressWarnings({"rawtypes", "unchecked"})
            Component cell = ((ListCellRenderer) renderer).getListCellRendererComponent(
                    probeList, value, index, false, false);
            if (cell instanceof JComponent component) {
                component.setFont(combo.getFont());
            }
            height = Math.max(height, cell.getPreferredSize().height);
        }
        return Math.max(1, height);
    }

    static int popupHeightFor(int itemCount, int maximumRowCount, int cellHeight, Insets insets) {
        int rows = Math.min(Math.max(0, itemCount), Math.max(0, maximumRowCount));
        return rows * cellHeight + insets.top + insets.bottom;
    }

    static final class FieldComboPopup extends BasicComboPopup {

        @SuppressWarnings({"rawtypes", "unchecked"})
        FieldComboPopup(JComboBox<?> comboBox) {
            super((JComboBox) comboBox);
            Color background = ThemeColors.elevatedSurface();
            setBackground(background);
            setBorder(popupBorder());
            list.setBackground(background);
            list.setForeground(ThemeColors.foreground());
            list.setSelectionBackground(ThemeColors.selectedBackground());
            list.setSelectionForeground(ThemeColors.foreground());
            list.setBorder(null);
            scroller.setBorder(null);
            scroller.setViewportBorder(null);
            scroller.setBackground(background);
            scroller.getViewport().setBackground(background);
        }

        @Override
        protected Rectangle computePopupBounds(int x, int y, int width, int height) {
            int cellHeight = fixedCellHeightFor(comboBox);
            list.setFixedCellHeight(cellHeight);
            Insets insets = getInsets();
            int popupHeight = popupHeightFor(comboBox.getItemCount(),
                    comboBox.getMaximumRowCount(), cellHeight, insets);
            Rectangle requested = popupBoundsFor(comboBox, x, y, width, height);
            return super.computePopupBounds(
                    requested.x, requested.y,
                    Math.max(0, requested.width - insets.left - insets.right),
                    Math.max(0, popupHeight - insets.top - insets.bottom));
        }
    }
}

final class FieldComboRenderer<T> extends DefaultListCellRenderer {

    private final Function<T, String> labeler;
    private final JComboBox<?> comboBox;
    private final Border rowPadding = BorderFactory.createEmptyBorder(
            UiTokens.SPACE_2, UiTokens.SPACE_3, UiTokens.SPACE_2, UiTokens.SPACE_3);

    FieldComboRenderer(Function<T, String> labeler, JComboBox<?> comboBox) {
        this.labeler = labeler;
        this.comboBox = comboBox;
    }

    @Override
    public Component getListCellRendererComponent(
            JList<?> list, Object value, int index, boolean selected, boolean cellHasFocus) {
        super.getListCellRendererComponent(
                list == null ? new JList<>() : list, value, index, selected, cellHasFocus);
        @SuppressWarnings("unchecked")
        T typedValue = (T) value;
        setText(value == null ? "" : Objects.toString(labeler.apply(typedValue), ""));
        setFont(list == null ? comboBox.getFont() : list.getFont());
        setBorder(index < 0 ? BorderFactory.createEmptyBorder() : rowPadding);
        setOpaque(index >= 0);
        if (index < 0) {
            setBackground(new Color(0, 0, 0, 0));
            setForeground(list == null || list.isEnabled()
                    ? ThemeColors.foreground() : ThemeColors.secondaryText());
        } else {
            setBackground(selected ? ThemeColors.selectedBackground() : ThemeColors.elevatedSurface());
            setForeground(ThemeColors.foreground());
        }
        return this;
    }
}
