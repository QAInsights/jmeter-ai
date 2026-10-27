package org.qainsights.jmeter.ai.gui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.KeyboardFocusManager;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.UIManager;

import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.gui.theme.UiTokens;

final class SegmentedControl<T> extends JComponent {

    private final JComboBox<T> combo;
    private final Function<T, String> labeler;
    private final Function<T, Icon> iconer;
    private final List<SegmentButton> buttons = new ArrayList<>();
    private final WrapLayout wrapLayout;
    private final PropertyChangeListener focusListener = event -> repaint();
    private final int horizontalPadding;
    private final int verticalPadding;
    private final int preferredTrackHeight;

    SegmentedControl(JComboBox<T> combo, Function<T, String> labeler) {
        this(combo, labeler, ignored -> null, FlowLayout.LEFT, UiTokens.SPACE_3, UiTokens.SPACE_1, 0);
    }

    SegmentedControl(JComboBox<T> combo, Function<T, String> labeler, Function<T, Icon> iconer) {
        this(combo, labeler, iconer, FlowLayout.LEFT, UiTokens.SPACE_3, UiTokens.SPACE_1, 0);
    }

    SegmentedControl(JComboBox<T> combo, Function<T, String> labeler, int alignment,
                     int horizontalPadding, int verticalPadding) {
        this(combo, labeler, ignored -> null, alignment, horizontalPadding, verticalPadding, 0);
    }

    SegmentedControl(JComboBox<T> combo, Function<T, String> labeler, int alignment,
                     int horizontalPadding, int verticalPadding, int preferredTrackHeight) {
        this(combo, labeler, ignored -> null, alignment, horizontalPadding, verticalPadding,
                preferredTrackHeight);
    }

    private SegmentedControl(JComboBox<T> combo, Function<T, String> labeler, Function<T, Icon> iconer,
                             int alignment, int horizontalPadding, int verticalPadding,
                             int preferredTrackHeight) {
        this.combo = Objects.requireNonNull(combo);
        this.labeler = Objects.requireNonNull(labeler);
        this.iconer = Objects.requireNonNull(iconer);
        this.horizontalPadding = horizontalPadding;
        this.verticalPadding = verticalPadding;
        this.preferredTrackHeight = Math.max(0, preferredTrackHeight);
        wrapLayout = new WrapLayout(alignment, UiTokens.SPACE_1, UiTokens.SPACE_1);
        setLayout(wrapLayout);
        setOpaque(false);
        setFocusable(true);
        setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        for (int index = 0; index < combo.getItemCount(); index++) {
            T item = combo.getItemAt(index);
            SegmentButton button = new SegmentButton(item);
            buttons.add(button);
            add(button);
        }
        combo.addActionListener(event -> syncSelection());
        installKeyboardActions();
        refreshFonts();
        syncSelection();
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent event) {
                revalidate();
            }
        });
    }

    int selectedIndex() {
        return combo.getSelectedIndex();
    }

    boolean isSelected(T item) {
        return Objects.equals(combo.getSelectedItem(), item);
    }

    void refreshFonts() {
        java.awt.Font base = UIManager.getFont("Button.font");
        for (SegmentButton button : buttons) {
            button.setFont(UiTokens.caption(base));
        }
        revalidate();
        repaint();
    }

    void refreshTheme() {
        refreshFonts();
        repaint();
    }

    @Override
    public java.awt.Dimension getPreferredSize() {
        int width = getWidth();
        java.awt.Dimension preferred = preferredTrackHeight > 0
                ? wrapLayout.preferredLayoutSizeForWidth(this, Integer.MAX_VALUE)
                : width > 0
                ? wrapLayout.preferredLayoutSizeForWidth(this, width)
                : wrapLayout.preferredLayoutSize(this);
        return new java.awt.Dimension(preferred.width, Math.max(preferred.height, preferredTrackHeight));
    }

    @Override
    public void addNotify() {
        super.addNotify();
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addPropertyChangeListener("focusOwner", focusListener);
    }

    @Override
    public void removeNotify() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .removePropertyChangeListener("focusOwner", focusListener);
        super.removeNotify();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int width = getWidth() - 1;
            int height = getHeight() - 1;
            if (width <= 0 || height <= 0) {
                return;
            }
            g.setColor(ThemeColors.surface());
            g.fillRoundRect(0, 0, width, height, UiTokens.RADIUS_MEDIUM * 2,
                    UiTokens.RADIUS_MEDIUM * 2);
            g.setColor(ThemeColors.separator());
            g.setStroke(new BasicStroke(1f));
            g.drawRoundRect(0, 0, width, height,
                    UiTokens.RADIUS_MEDIUM * 2, UiTokens.RADIUS_MEDIUM * 2);
        } finally {
            g.dispose();
        }
    }

    private void syncSelection() {
        T selected = (T) combo.getSelectedItem();
        for (SegmentButton button : buttons) {
            button.setSelectedValue(Objects.equals(button.item, selected));
        }
        revalidate();
        repaint();
    }

    private void installKeyboardActions() {
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("LEFT"), "previous-segment");
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("RIGHT"), "next-segment");
        getActionMap().put("previous-segment", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                moveSelection(-1);
            }
        });
        getActionMap().put("next-segment", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                moveSelection(1);
            }
        });
    }

    private void moveSelection(int amount) {
        int next = Math.max(0, Math.min(buttons.size() - 1, combo.getSelectedIndex() + amount));
        if (next >= 0 && next < buttons.size()) {
            combo.setSelectedIndex(next);
            buttons.get(next).requestFocusInWindow();
        }
    }

    private final class SegmentButton extends JButton {
        private final T item;
        private boolean selectedValue;

        private SegmentButton(T item) {
            super(labeler.apply(item), iconer.apply(item));
            this.item = item;
            setHorizontalTextPosition(SwingConstants.RIGHT);
            setIconTextGap(UiTokens.SPACE_1);
            setMargin(new Insets(0, 0, 0, 0));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setFocusable(true);
            setRolloverEnabled(true);
            setBorder(BorderFactory.createEmptyBorder(
                    verticalPadding, horizontalPadding, verticalPadding, horizontalPadding));
            setForeground(ThemeColors.secondaryText());
            getAccessibleContext().setAccessibleName(labeler.apply(item));
            addActionListener(event -> combo.setSelectedItem(item));
        }

        private void setSelectedValue(boolean selected) {
            selectedValue = selected;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int width = getWidth() - 1;
                int height = getHeight() - 1;
                if (selectedValue || getModel().isRollover() || getModel().isPressed()) {
                    Color fill = selectedValue ? ThemeColors.selectedBackground() : ThemeColors.hoverBackground();
                    if (getModel().isPressed()) {
                        fill = ThemeColors.blend(ThemeColors.foreground(), fill, 0.10f);
                    }
                    g.setColor(isEnabled() ? fill : ThemeColors.blend(fill, ThemeColors.canvas(), 0.55f));
                    g.fillRoundRect(0, 0, width, height, UiTokens.RADIUS_SMALL * 2,
                            UiTokens.RADIUS_SMALL * 2);
                    if (selectedValue) {
                        g.setColor(ThemeColors.blend(ThemeColors.accent(), ThemeColors.separator(), 0.22f));
                        g.setStroke(new BasicStroke(1f));
                        g.drawRoundRect(0, 0, width, height, UiTokens.RADIUS_SMALL * 2,
                                UiTokens.RADIUS_SMALL * 2);
                    }
                }
                if (isFocusOwner()) {
                    g.setColor(ThemeColors.focusRing());
                    g.setStroke(new BasicStroke(1.6f));
                    g.drawRoundRect(1, 1, Math.max(0, width - 2), Math.max(0, height - 2),
                            UiTokens.RADIUS_SMALL * 2, UiTokens.RADIUS_SMALL * 2);
                }
                setForeground(selectedValue ? ThemeColors.foreground() : ThemeColors.secondaryText());
            } finally {
                g.dispose();
            }
            super.paintComponent(graphics);
        }
    }
}
