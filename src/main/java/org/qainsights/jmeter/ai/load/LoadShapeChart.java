package org.qainsights.jmeter.ai.load;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.util.List;
import javax.accessibility.AccessibleContext;
import javax.swing.JComponent;

import org.qainsights.jmeter.ai.gui.theme.ThemeColors;

/** Antialiased closed-model thread and request-rate chart. */
public final class LoadShapeChart extends JComponent {
    private WorkloadPlan plan;

    public LoadShapeChart() {
        setOpaque(true);
        setBackground(ThemeColors.canvas());
        setPreferredSize(new java.awt.Dimension(420, 230));
        getAccessibleContext().setAccessibleName("Workload thread ramp and request-rate chart");
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
            int right = getWidth() - 62;
            int top = 36;
            int bottom = getHeight() - 38;
            if (right <= left || bottom <= top) {
                return;
            }
            drawLegend(g, left, right);
            drawAxes(g, left, right, top, bottom);
            if (plan != null) {
                drawLoad(g, left, right, top, bottom);
                drawTimeTicks(g, left, right, bottom);
            } else {
                g.setColor(ThemeColors.secondaryText());
                g.drawString("Enter a target to preview the load shape", left + 12, top + 28);
            }
        } finally {
            g.dispose();
        }
    }

    private void drawLegend(Graphics2D g, int left, int right) {
        g.setFont(getFont());
        FontMetrics metrics = g.getFontMetrics();
        int baseline = 17;
        g.setColor(ThemeColors.secondaryText());
        drawLegendItem(g, left, baseline, ThemeColors.accent(), false, "Threads");
        String rateLabel = "Requests/s";
        int rateX = right - metrics.stringWidth(rateLabel) - 22;
        drawLegendItem(g, rateX, baseline, ThemeColors.info(), true, rateLabel);
    }

    private static void drawLegendItem(Graphics2D g, int x, int baseline, Color color,
                                       boolean dashed, String label) {
        g.setColor(color);
        g.setStroke(dashed
                ? new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[]{5, 4}, 0)
                : new BasicStroke(2f));
        g.drawLine(x, baseline - 4, x + 14, baseline - 4);
        g.setStroke(new BasicStroke(1f));
        g.setColor(ThemeColors.secondaryText());
        g.drawString(label, x + 19, baseline);
    }

    private void drawAxes(Graphics2D g, int left, int right, int top, int bottom) {
        g.setFont(getFont());
        FontMetrics metrics = g.getFontMetrics();
        double maxRps = plan == null ? 0 : WorkloadChartModel.requestRateAxisMaximum(plan);
        for (int tick = 0; tick <= 4; tick++) {
            int y = bottom - (bottom - top) * tick / 4;
            g.setColor(ThemeColors.separator());
            g.drawLine(left, y, right, y);
            if (plan != null) {
                g.setColor(ThemeColors.secondaryText());
                String threads = Integer.toString(plan.threads() * tick / 4);
                g.drawString(threads, left - metrics.stringWidth(threads) - 7,
                        y + metrics.getAscent() / 2);
                double requestTick = Math.round(maxRps * tick / 4 * 100) / 100.0;
                String requests = WorkloadCalculator.format(requestTick);
                g.drawString(requests, right + 7, y + metrics.getAscent() / 2);
            }
        }
        g.setColor(ThemeColors.border());
        g.drawLine(left, top, left, bottom);
        g.drawLine(right, top, right, bottom);
        g.drawLine(left, bottom, right, bottom);
    }

    private void drawLoad(Graphics2D g, int left, int right, int top, int bottom) {
        double width = right - left;
        double height = bottom - top;
        double total = Math.max(1, plan.durationSeconds());
        double rampX = left + width * plan.rampSeconds() / total;
        Path2D area = new Path2D.Double();
        area.moveTo(left, bottom);
        area.lineTo(rampX, top);
        area.lineTo(right, top);
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

        double maxRps = WorkloadChartModel.requestRateAxisMaximum(plan);
        double finalRate = WorkloadChartModel.maximumRequestsPerSecond(plan);
        if (maxRps > 0 && finalRate > 0) {
            double plateau = WorkloadChartModel.ratePlateauSeconds(plan);
            double plateauX = left + width * plateau / total;
            double plateauY = bottom - height * finalRate / maxRps;
            Path2D rateLine = new Path2D.Double();
            rateLine.moveTo(left, bottom);
            rateLine.lineTo(plateauX, plateauY);
            rateLine.lineTo(right, plateauY);
            g.setColor(ThemeColors.info());
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL,
                    0, new float[]{5, 4}, 0));
            g.draw(rateLine);
        }
    }

    private void drawTimeTicks(Graphics2D g, int left, int right, int bottom) {
        g.setFont(getFont());
        g.setColor(ThemeColors.secondaryText());
        List<Long> ticks = WorkloadChartModel.timeTicks(plan.durationSeconds());
        for (long seconds : ticks) {
            int x = left + (int) Math.round((right - left) * seconds / (double) plan.durationSeconds());
            String label = WorkloadChartModel.formatTime(seconds, plan.durationSeconds());
            int labelWidth = g.getFontMetrics().stringWidth(label);
            int labelX = Math.max(left, Math.min(x - labelWidth / 2, right - labelWidth));
            g.drawString(label, labelX, bottom + 18);
        }
    }
}
