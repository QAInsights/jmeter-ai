package org.qainsights.jmeter.ai.gui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.Timer;
import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.gui.theme.UiTokens;

/**
 * A custom Swing panel that provides a quiet elevated composer surface and a
 * restrained Google-style gradient border while the model is processing.
 */
public class GeminiBorderPanel extends JPanel {

    private boolean isThinking = false;
    private boolean focused = false;
    private int rotationAngle = 0;
    private final Timer animationTimer;

    public GeminiBorderPanel() {
        super(new BorderLayout());

        // Add padding to leave room for the animated border
        setBorder(BorderFactory.createEmptyBorder(
                UiTokens.SPACE_2, UiTokens.SPACE_2, UiTokens.SPACE_1, UiTokens.SPACE_2));
        setOpaque(false);
        applyThemeBackground();

        // Set up the animation timer (updates angle and repaints)
        animationTimer = new Timer(45, e -> {
            rotationAngle = (rotationAngle + 2) % 360;
            repaint();
        });
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (isThinking) {
            animationTimer.start();
        }
    }

    @Override
    public void removeNotify() {
        animationTimer.stop();
        super.removeNotify();
    }

    boolean isAnimationRunning() {
        return animationTimer.isRunning();
    }

    /**
     * Toggles the thinking mode and starts/stops the rotating gradient border animation.
     *
     * @param thinking true to display the rotating Gemini border, false for subtle static border
     */
    public void setThinking(boolean thinking) {
        if (this.isThinking == thinking) {
            return;
        }
        this.isThinking = thinking;
        if (thinking) {
            if (isDisplayable()) {
                animationTimer.start();
            }
        } else {
            animationTimer.stop();
            rotationAngle = 0;
            repaint();
        }
    }

    public boolean isThinking() {
        return isThinking;
    }

    public void setFocused(boolean focused) {
        if (this.focused != focused) {
            this.focused = focused;
            repaint();
        }
    }

    boolean isFocused() {
        return focused;
    }

    /**
     * Re-applies the theme background when the look-and-feel changes
     * (e.g. switching between JMeter's light and dark themes), so the
     * composer never keeps a stale color from the previous theme.
     */
    @Override
    public void updateUI() {
        super.updateUI();
        applyThemeBackground();
    }

    /**
     * Applies the elevated composer surface for the active theme. Reads the
     * color from the current theme on every call rather than caching it.
     */
    public void applyThemeBackground() {
        setBackground(ThemeColors.elevatedSurface());
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int width = getWidth();
            int height = getHeight();
            int inset = 2;
            int arc = UiTokens.RADIUS_LARGE * 2;
            int paintedWidth = Math.max(0, width - inset * 2 - 1);
            int paintedHeight = Math.max(0, height - inset * 2 - 2);

            g2.setColor(ThemeColors.shadow());
            g2.fillRoundRect(inset, inset + 2, paintedWidth, paintedHeight, arc, arc);
            g2.setColor(getBackground());
            g2.fillRoundRect(inset, inset, paintedWidth, paintedHeight, arc, arc);

            if (isThinking) {
                AnimatedGradientPainter.paintRotatingBorder(
                        g2, width, height, rotationAngle, 2.0f, arc, inset, 0.68f);
            } else {
                Color border = focused ? ThemeColors.focusRing() : ThemeColors.separator();
                g2.setColor(border);
                g2.setStroke(new BasicStroke(focused ? 1.6f : 1f));
                g2.drawRoundRect(inset, inset, paintedWidth, paintedHeight, arc, arc);
            }
        } finally {
            g2.dispose();
        }
    }
}
