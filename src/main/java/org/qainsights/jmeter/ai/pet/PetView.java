package org.qainsights.jmeter.ai.pet;

import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;
import javax.swing.Timer;

/**
 * The visible pet: paints the animator's current frame at the configured scale on a
 * Swing timer, and lets the user drag it around its parent container. Horizontal drag
 * direction flips the pet into its directional running rows.
 */
public final class PetView extends JComponent {

    /** Initial timer delay before the first tick; subsequent delays are per-frame. */
    static final int INITIAL_INTERVAL_MS = 300;

    private final PetSpriteSheet sheet;
    private final PetAnimator animator;
    private final PetTiming timing;
    private final Timer timer;
    private Runnable userMoveListener;
    private Point dragOrigin;

    public PetView(PetSpriteSheet sheet, PetAnimator animator, double scale) {
        this(sheet, animator, PetTiming.defaults(), scale);
    }

    public PetView(PetSpriteSheet sheet, PetAnimator animator, PetTiming timing, double scale) {
        this.sheet = sheet;
        this.animator = animator;
        this.timing = timing;
        int width = Math.max(1, (int) Math.round(sheet.contentWidth() * scale));
        int height = Math.max(1, (int) Math.round(sheet.contentHeight() * scale));
        setPreferredSize(new Dimension(width, height));
        setSize(width, height);
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setToolTipText("Feather Wand pet - drag me around!");
        this.timer = new Timer(INITIAL_INTERVAL_MS, e -> tickOnce());
        installDragSupport();
    }

    /** Called by whoever repositions this view when the user drags it. */
    public void setUserMoveListener(Runnable listener) {
        this.userMoveListener = listener;
    }

    public void startAnimation() {
        timer.start();
    }

    public void stopAnimation() {
        timer.stop();
    }

    /** Current timer delay in milliseconds; package-visible for tests. */
    int getTimerDelay() {
        return timer.getDelay();
    }

    /** Advances one animation frame and repaints; package-visible for tests. */
    void tickOnce() {
        animator.tick();
        PetState state = animator.currentState();
        int delay = timing.durationMs(state, animator.frameIndex());
        if (delay != timer.getDelay()) {
            timer.setDelay(delay);
        }
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        PetState state = animator.currentState();
        if (sheet.frameCount(state) == 0) {
            return;
        }
        int frameIdx = Math.floorMod(animator.frameIndex(), sheet.frameCount(state));
        BufferedImage frame = sheet.frame(state, frameIdx);
        FrameBounds fb = sheet.frameBounds(state, frameIdx);
        if (fb == null) {
            return;
        }
        // Center this frame's content within the view, scaling to fit
        double scale = Math.min((double) getWidth() / fb.width(),
                                (double) getHeight() / fb.height());
        int drawW = (int) Math.round(fb.width() * scale);
        int drawH = (int) Math.round(fb.height() * scale);
        int drawX = (getWidth() - drawW) / 2;
        int drawY = (getHeight() - drawH) / 2;
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(frame, drawX, drawY, drawX + drawW, drawY + drawH,
                    fb.x(), fb.y(), fb.x() + fb.width(), fb.y() + fb.height(), null);
        } finally {
            g2.dispose();
        }
    }

    private void installDragSupport() {
        MouseAdapter dragHandler = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                dragOrigin = e.getPoint();
                animator.onDragStarted();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragOrigin == null) {
                    return;
                }
                int dx = e.getX() - dragOrigin.x;
                int dy = e.getY() - dragOrigin.y;
                animator.onDragMoved(dx);
                moveWithinParent(getX() + dx, getY() + dy);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragOrigin = null;
                animator.onDragEnded();
            }
        };
        addMouseListener(dragHandler);
        addMouseMotionListener(dragHandler);
    }

    /** Moves the view to ({@code x}, {@code y}) clamped inside its parent's bounds. */
    void moveWithinParent(int x, int y) {
        if (getParent() != null) {
            x = Math.max(0, Math.min(getParent().getWidth() - getWidth(), x));
            y = Math.max(0, Math.min(getParent().getHeight() - getHeight(), y));
        }
        setLocation(x, y);
        if (userMoveListener != null) {
            userMoveListener.run();
        }
    }
}
