package org.qainsights.jmeter.ai.gui.theme;

import java.awt.Dimension;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import javax.swing.AbstractButton;

public final class HeaderButtonSizing {

    private static final String LISTENER_PROPERTY =
            HeaderButtonSizing.class.getName() + ".sizingListener";

    private HeaderButtonSizing() {
    }

    public static void apply(AbstractButton button) {
        SizingListener listener = (SizingListener) button.getClientProperty(LISTENER_PROPERTY);
        if (listener == null) {
            listener = new SizingListener(button);
            button.putClientProperty(LISTENER_PROPERTY, listener);
            button.addPropertyChangeListener("UI", listener);
            button.addPropertyChangeListener("font", listener);
            button.addPropertyChangeListener("text", listener);
        }
        listener.updateSize();
    }

    private static void updateSize(AbstractButton button) {
        button.setPreferredSize(null);
        button.setMinimumSize(null);
        button.setMaximumSize(null);

        Dimension natural = button.getUI() == null ? null : button.getUI().getPreferredSize(button);
        if (natural == null) {
            natural = button.getPreferredSize();
        }
        int width = Math.max(UiTokens.HEADER_TEXT_BUTTON_WIDTH,
                natural == null ? UiTokens.HEADER_TEXT_BUTTON_WIDTH : natural.width);
        Dimension size = new Dimension(width, UiTokens.HEADER_CONTROL_HEIGHT);
        button.setPreferredSize(new Dimension(size));
        button.setMinimumSize(new Dimension(size));
        button.setMaximumSize(new Dimension(size));
    }

    private static final class SizingListener implements PropertyChangeListener {
        private final AbstractButton button;
        private boolean updating;

        private SizingListener(AbstractButton button) {
            this.button = button;
        }

        @Override
        public void propertyChange(PropertyChangeEvent event) {
            updateSize();
        }

        private void updateSize() {
            if (updating) {
                return;
            }
            updating = true;
            try {
                HeaderButtonSizing.updateSize(button);
            } finally {
                updating = false;
            }
        }
    }
}
