package org.qainsights.jmeter.ai.gui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;

import org.qainsights.jmeter.ai.gui.theme.UiTokens;

final class WrapLayout extends FlowLayout {

    WrapLayout() {
        this(FlowLayout.LEFT, UiTokens.SPACE_1, UiTokens.SPACE_1);
    }

    WrapLayout(int alignment, int horizontalGap, int verticalGap) {
        super(alignment, horizontalGap, verticalGap);
    }

    @Override
    public Dimension preferredLayoutSize(Container parent) {
        synchronized (parent.getTreeLock()) {
            int width = parent.getWidth();
            if (width <= 0) {
                width = preferredContentWidth(parent);
            }
            return preferredLayoutSizeForWidth(parent, width);
        }
    }

    Dimension preferredLayoutSizeForWidth(Container parent, int availableWidth) {
        synchronized (parent.getTreeLock()) {
            Insets insets = parent.getInsets();
            int contentWidth = Math.max(1, availableWidth - insets.left - insets.right);
            int rowWidth = 0;
            int rowHeight = 0;
            int widestRow = 0;
            int totalHeight = 0;
            int rows = 0;
            for (Component component : parent.getComponents()) {
                if (!component.isVisible()) {
                    continue;
                }
                Dimension size = component.getPreferredSize();
                int componentWidth = Math.min(contentWidth, size.width);
                if (rowWidth > 0 && rowWidth + getHgap() + componentWidth > contentWidth) {
                    widestRow = Math.max(widestRow, rowWidth);
                    totalHeight += rowHeight;
                    rows++;
                    rowWidth = 0;
                    rowHeight = 0;
                }
                if (rowWidth > 0) {
                    rowWidth += getHgap();
                }
                rowWidth += componentWidth;
                rowHeight = Math.max(rowHeight, size.height);
            }
            if (rowWidth > 0 || rows == 0) {
                widestRow = Math.max(widestRow, rowWidth);
                totalHeight += rowHeight;
                rows++;
            }
            totalHeight += Math.max(0, rows - 1) * getVgap();
            return new Dimension(widestRow + insets.left + insets.right,
                    totalHeight + insets.top + insets.bottom);
        }
    }

    @Override
    public Dimension minimumLayoutSize(Container parent) {
        synchronized (parent.getTreeLock()) {
            int minWidth = 0;
            int minHeight = 0;
            int count = 0;
            for (Component component : parent.getComponents()) {
                if (!component.isVisible()) {
                    continue;
                }
                Dimension size = component.getMinimumSize();
                minWidth = Math.max(minWidth, size.width);
                minHeight = Math.max(minHeight, size.height);
                count++;
            }
            Insets insets = parent.getInsets();
            return new Dimension(minWidth + insets.left + insets.right,
                    minHeight + insets.top + insets.bottom
                            + (count > 0 ? getVgap() : 0));
        }
    }

    @Override
    public void layoutContainer(Container parent) {
        synchronized (parent.getTreeLock()) {
            Insets insets = parent.getInsets();
            int left = insets.left;
            int right = parent.getWidth() - insets.right;
            int x = left;
            int y = insets.top;
            int rowHeight = 0;
            boolean rowHasComponent = false;
            for (Component component : parent.getComponents()) {
                if (!component.isVisible()) {
                    continue;
                }
                Dimension size = component.getPreferredSize();
                if (rowHasComponent && x + getHgap() + size.width > right) {
                    x = left;
                    y += rowHeight + getVgap();
                    rowHeight = 0;
                    rowHasComponent = false;
                }
                if (rowHasComponent) {
                    x += getHgap();
                }
                component.setBounds(x, y, size.width, size.height);
                x += size.width;
                rowHeight = Math.max(rowHeight, size.height);
                rowHasComponent = true;
            }
        }
    }

    private int preferredContentWidth(Container parent) {
        int width = 0;
        for (Component component : parent.getComponents()) {
            if (component.isVisible()) {
                if (width > 0) {
                    width += getHgap();
                }
                width += component.getPreferredSize().width;
            }
        }
        Insets insets = parent.getInsets();
        return width + insets.left + insets.right;
    }
}
