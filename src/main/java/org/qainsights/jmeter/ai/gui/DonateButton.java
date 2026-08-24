package org.qainsights.jmeter.ai.gui;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import javax.swing.JButton;
import javax.swing.Timer;
import org.qainsights.jmeter.ai.gui.theme.UiTokens;

/**
 * The native-chrome Donate action with the shared animated gradient outline.
 */
class DonateButton extends JButton {

    private static final float STROKE_WIDTH = 2f;
    private static final int ARC_RADIUS = 8;
    private static final int PAINT_INSET = 1;

    private final Timer animationTimer;
    private int rotationAngle;

    DonateButton() {
        super("Donate");
        setMargin(new Insets(2, 6, 2, 6));
        Dimension size = new Dimension(
                UiTokens.HEADER_TEXT_BUTTON_WIDTH, UiTokens.HEADER_CONTROL_HEIGHT);
        setPreferredSize(size);
        setMinimumSize(size);
        setMaximumSize(size);
        setAlignmentY(CENTER_ALIGNMENT);
        setToolTipText("Support this project as it takes time, tokens and resources to build and maintain");
        getAccessibleContext().setAccessibleName("Donate to support Feather Wand");
        animationTimer = new Timer(45, event -> advanceAnimation());
    }

    @Override
    public void addNotify() {
        super.addNotify();
        animationTimer.start();
    }

    @Override
    public void removeNotify() {
        animationTimer.stop();
        super.removeNotify();
    }

    @Override
    public void paint(Graphics graphics) {
        super.paint(graphics);
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            AnimatedGradientPainter.paintRotatingBorder(
                    g2, getWidth(), getHeight(), rotationAngle,
                    STROKE_WIDTH, ARC_RADIUS, PAINT_INSET, 1f);
        } finally {
            g2.dispose();
        }
    }

    boolean isAnimationRunning() {
        return animationTimer.isRunning();
    }

    int rotationAngle() {
        return rotationAngle;
    }

    void advanceAnimation() {
        rotationAngle = (rotationAngle + 2) % 360;
        repaint();
    }
}
