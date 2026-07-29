package org.qainsights.jmeter.ai.pet;

import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import javax.swing.JLayeredPane;

/**
 * Hosts a {@link PetView} on a window's {@link JLayeredPane}, floating above the
 * regular content. The pet stays anchored to the bottom-right corner (with a margin)
 * across window resizes until the user drags it somewhere, after which its position
 * is only clamped back into view on resize.
 */
public final class PetOverlay {

    static final int MARGIN = 16;

    private final PetView view;
    private JLayeredPane layeredPane;
    private boolean autoAnchor = true;
    private boolean repositioning;
    private final ComponentAdapter resizeListener = new ComponentAdapter() {
        @Override
        public void componentResized(ComponentEvent e) {
            reposition();
        }
    };

    public PetOverlay(PetView view) {
        this.view = view;
    }

    /** Adds the pet to the layered pane, anchored bottom-right, and starts animating. */
    public void install(JLayeredPane pane) {
        if (layeredPane != null) {
            return;
        }
        layeredPane = pane;
        view.setUserMoveListener(this::onUserMoved);
        pane.add(view, JLayeredPane.POPUP_LAYER);
        pane.addComponentListener(resizeListener);
        reposition();
        view.startAnimation();
    }

    /** Removes the pet and stops its animation timer. */
    public void uninstall() {
        if (layeredPane == null) {
            return;
        }
        view.stopAnimation();
        layeredPane.removeComponentListener(resizeListener);
        layeredPane.remove(view);
        layeredPane.repaint();
        layeredPane = null;
        autoAnchor = true;
    }

    /** Whether the pet is still auto-anchored to the bottom-right corner. */
    boolean isAutoAnchored() {
        return autoAnchor;
    }

    private void onUserMoved() {
        if (!repositioning) {
            autoAnchor = false;
        }
    }

    /** Re-anchors or clamps the pet; package-visible for tests (resize events need a live window). */
    void reposition() {
        if (layeredPane == null) {
            return;
        }
        repositioning = true;
        try {
            if (autoAnchor) {
                view.setLocation(
                        Math.max(0, layeredPane.getWidth() - view.getWidth() - MARGIN),
                        Math.max(0, layeredPane.getHeight() - view.getHeight() - MARGIN));
            } else {
                view.moveWithinParent(view.getX(), view.getY());
            }
        } finally {
            repositioning = false;
        }
    }
}
