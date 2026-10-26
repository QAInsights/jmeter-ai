package org.qainsights.jmeter.ai.load;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import javax.accessibility.AccessibleContext;
import javax.swing.JComponent;

import org.qainsights.jmeter.ai.gui.theme.ThemeColors;

/** Antialiased closed-model thread ramp and hold chart. */
public final class LoadShapeChart extends JComponent {
    private WorkloadPlan plan;

    public LoadShapeChart() {
        setOpaque(true);
        setBackground(ThemeColors.canvas());
        setPreferredSize(new java.awt.Dimension(420, 230));
        getAccessibleContext().setAccessibleName("Workload thread ramp and hold chart");
    }

    @Override
    public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) {
            accessibleContext = new AccessibleLoadShapeChart();
        }
        return accessibleContext;
    }

    protected class AccessibleLoadShapeChart extends AccessibleJComponent {
    }

    public void setPlan(WorkloadPlan plan) {
        this.plan = plan;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int left = 54;
            int right = getWidth() - 22;
            int top = 28;
            int bottom = getHeight() - 36;
            if (right <= left || bottom <= top) {
                return;
            }
            drawAxes(g, left, right, top, bottom);
            if (plan != null) {
                drawLoad(g, left, right, top, bottom);
            } else {
                g.setColor(ThemeColors.secondaryText());
                g.drawString("Enter a target to preview the load shape", left + 12, top + 28);
            }
        } finally {
            g.dispose();
        }
    }

    private void drawAxes(Graphics2D g, int left, int right, int top, int bottom) {
        g.setFont(getFont());
        FontMetrics metrics = g.getFontMetrics();
        g.setColor(ThemeColors.secondaryText());
        g.drawString("Threads", 8, top + 4);
        g.drawString("Time", right - metrics.stringWidth("Time"), getHeight() - 5);
        for (int tick = 0; tick <= 4; tick++) {
            int y = bottom - (bottom - top) * tick / 4;
            g.setColor(ThemeColors.separator());
            g.drawLine(left, y, right, y);
            g.setColor(ThemeColors.secondaryText());
            String label = plan == null ? "" : Integer.toString(plan.threads() * tick / 4);
            g.drawString(label, left - metrics.stringWidth(label) - 7, y + metrics.getAscent() / 2);
        }
        g.setColor(ThemeColors.border());
        g.drawLine(left, top, left, bottom);
        g.drawLine(left, bottom, right, bottom);
    }

    private void drawLoad(Graphics2D g, int left, int right, int top, int bottom) {
        double width = right - left;
        double height = bottom - top;
        double total = Math.max(1, plan.durationSeconds());
        double rampX = left + width * plan.rampSeconds() / total;
        double threadY = bottom - height;
        Path2D area = new Path2D.Double();
        area.moveTo(left, bottom);
        area.lineTo(rampX, threadY);
        area.lineTo(right, threadY);
        area.lineTo(right, bottom);
        area.closePath();
        Color fill = new Color(ThemeColors.accentSoft().getRed(), ThemeColors.accentSoft().getGreen(),
                ThemeColors.accentSoft().getBlue(), 190);
        g.setColor(fill);
        g.fill(area);
        g.setColor(ThemeColors.accent());
        g.setStroke(new BasicStroke(2f));
        g.drawLine(left, bottom, (int) rampX, top);
        g.drawLine((int) rampX, top, right, top);
        if (plan.rampSeconds() == 0) {
            g.drawLine(left, bottom, left, top);
        }
        drawTimeLabels(g, left, right, bottom, total);
        if (plan.targetRequestsPerSecond() != null) {
            g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL,
                    0, new float[]{5, 4}, 0));
            g.setColor(ThemeColors.warning());
            int y = top + (int) (height * 0.16);
            g.drawLine(left, y, right, y);
            g.drawString("Target " + WorkloadCalculator.format(plan.targetRequestsPerSecond()) + " requests/s",
                    left + 8, y - 5);
        }
    }

    private void drawTimeLabels(Graphics2D g, int left, int right, int bottom, double totalSeconds) {
        g.setColor(ThemeColors.secondaryText());
        for (int tick = 0; tick <= 4; tick++) {
            int x = left + (right - left) * tick / 4;
            double seconds = totalSeconds * tick / 4.0;
            String label = timeLabel(seconds);
            g.drawString(label, x - g.getFontMetrics().stringWidth(label) / 2, bottom + 18);
        }
    }

    private static String timeLabel(double seconds) {
        long totalMinutes = (long) (seconds / 60);
        if (seconds >= 3600) {
            long hours = (long) (seconds / 3600);
            long minutes = Math.round((seconds - hours * 3600) / 60);
            return minutes == 0 ? String.format("%dh", hours) : String.format("%dh%dm", hours, minutes);
        }
        return String.format("%d:%02d", totalMinutes, (long) seconds % 60);
    }
}
