package org.qainsights.jmeter.ai.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * A custom Swing panel that provides a rotating, shiny Google Gemini-style
 * gradient border around the chat input area and Send button when in thinking mode.
 */
public class GeminiBorderPanel extends JPanel {

    private boolean isThinking = false;
    private int rotationAngle = 0;
    private final Timer animationTimer;

    public GeminiBorderPanel() {
        super(new BorderLayout());
        
        // Add padding to leave room for the animated border
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        
        // Use default text area background for a unified look
        Color bg = UIManager.getColor("TextArea.background");
        if (bg == null) {
            bg = Color.WHITE;
        }
        setBackground(bg);

        // Set up the animation timer (updates angle and repaints)
        animationTimer = new Timer(30, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                rotationAngle = (rotationAngle + 4) % 360;
                repaint();
            }
        });
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
            animationTimer.start();
        } else {
            animationTimer.stop();
            rotationAngle = 0;
            repaint();
        }
    }

    public boolean isThinking() {
        return isThinking;
    }

    @Override
    public void paint(Graphics g) {
        super.paint(g);
        Graphics2D g2d = (Graphics2D) g.create();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        int width = getWidth();
        int height = getHeight();

        if (isThinking) {
            // Calculate rotating gradient points
            double angleRad = Math.toRadians(rotationAngle);
            float x1 = (float) (width / 2.0 + (width / 2.0) * Math.cos(angleRad));
            float y1 = (float) (height / 2.0 + (height / 2.0) * Math.sin(angleRad));
            float x2 = (float) (width / 2.0 - (width / 2.0) * Math.cos(angleRad));
            float y2 = (float) (height / 2.0 - (height / 2.0) * Math.sin(angleRad));

            // Google Gemini shiny gradient colors
            Color[] colors = {
                new Color(0x42, 0x85, 0xF4), // Blue
                new Color(0x9b, 0x51, 0xe0), // Purple
                new Color(0xea, 0x43, 0x35), // Pink/Red
                new Color(0x24, 0xb4, 0xf4), // Cyan
                new Color(0x42, 0x85, 0xF4)  // Blue (wrap)
            };
            float[] fractions = { 0.0f, 0.25f, 0.5f, 0.75f, 1.0f };

            LinearGradientPaint paint = new LinearGradientPaint(x1, y1, x2, y2, fractions, colors);
            g2d.setPaint(paint);
            g2d.setStroke(new BasicStroke(3.0f));
            
            // Draw a rounded rectangle border
            g2d.drawRoundRect(2, 2, width - 4, height - 4, 12, 12);
        } else {
            // Paint a subtle default border
            Color borderColor = UIManager.getColor("Component.borderColor");
            if (borderColor == null) {
                borderColor = Color.LIGHT_GRAY;
            }
            g2d.setColor(borderColor);
            g2d.setStroke(new BasicStroke(1.0f));
            g2d.drawRoundRect(1, 1, width - 2, height - 2, 12, 12);
        }
        g2d.dispose();
    }
}
