package org.qainsights.jmeter.ai.load;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.util.List;
import javax.accessibility.AccessibleContext;
import javax.swing.JComponent;
import javax.swing.UIManager;

import org.qainsights.jmeter.ai.gui.theme.ThemeColors;

/** Antialiased closed-model thread and sample-rate chart. */
public final class LoadShapeChart extends JComponent {
    private static final int BASE_PREFERRED_HEIGHT = 210;
    private static final float BASE_FONT_SIZE = 12f;

    private WorkloadPlan plan;
    private String emptyMessage = "Enter a target to preview the load shape";
    private boolean emptyMessageIsError;

    public LoadShapeChart() {
        setOpaque(false);
        setPreferredSize(new java.awt.Dimension(420, 210));
        getAccessibleContext().setAccessibleName("Workload thread ramp and sample-rate chart");
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
        setPlan(plan, null, false);
    }

    public void setPlan(WorkloadPlan plan, String emptyMessage) {
        setPlan(plan, emptyMessage, emptyMessage != null && !emptyMessage.isBlank());
    }

    public void setPlan(WorkloadPlan plan, String emptyMessage, boolean error) {
        this.plan = plan;
        this.emptyMessage = emptyMessage == null || emptyMessage.isBlank()
                ? "Enter a target to preview the load shape" : emptyMessage;
        this.emptyMessageIsError = error;
        repaint();
    }

    String emptyMessage() {
        return emptyMessage;
    }

    boolean emptyMessageIsError() {
        return emptyMessageIsError;
    }

    static int preferredHeightForFont(Font font) {
        float fontSize = font == null ? BASE_FONT_SIZE : font.getSize2D();
        return Math.max(1, Math.round(BASE_PREFERRED_HEIGHT * fontSize / BASE_FONT_SIZE));
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension preferred = super.getPreferredSize();
        return new Dimension(preferred.width, preferredHeightForFont(getFont()));
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(0, preferredHeightForFont(getFont()));
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(UIManager.getFont("Label.font"));
            int left = 54;
            int right = getWidth() - 62;
            int top = 18;
            int bottom = getHeight() - 30;
            if (right <= left || bottom <= top) {
                return;
            }
            drawAxes(g, left, right, top, bottom);
            if (plan != null) {
                drawLoad(g, left, right, top, bottom);
                drawTimeTicks(g, left, right, bottom);
            } else {
                g.setColor(emptyMessageIsError ? ThemeColors.error() : ThemeColors.secondaryText());
                int textY = top + 28;
                java.awt.FontMetrics metrics = g.getFontMetrics();
                StringBuilder line = new StringBuilder();
                for (String word : emptyMessage.split("\\s+")) {
                    String candidate = line.isEmpty() ? word : line + " " + word;
                    if (metrics.stringWidth(candidate) > right - left - 24 && !line.isEmpty()) {
                        g.drawString(line.toString(), left + 12, textY);
                        textY += metrics.getHeight();
                        line.setLength(0);
                        line.append(word);
                    } else {
                        if (!line.isEmpty()) {
                            line.append(' ');
                        }
                        line.append(word);
                    }
                }
                if (!line.isEmpty()) {
                    g.drawString(line.toString(), left + 12, textY);
                }
            }
        } finally {
            g.dispose();
        }
    }

    private void drawAxes(Graphics2D g, int left, int right, int top, int bottom) {
        g.setFont(UIManager.getFont("Label.font"));
        FontMetrics metrics = g.getFontMetrics();
        g.setColor(ThemeColors.secondaryText());
        double maxThreads = plan == null ? 0 : WorkloadChartModel.threadAxisMaximum(plan);
        double maxRps = plan == null ? 0 : WorkloadChartModel.sampleRateAxisMaximum(plan);
        for (int tick = 0; tick <= 4; tick++) {
            int y = bottom - (bottom - top) * tick / 4;
            g.setColor(ThemeColors.separator());
            g.drawLine(left, y, right, y);
            if (plan != null) {
                g.setColor(ThemeColors.secondaryText());
                String threads = WorkloadChartModel.formatAxisTick(maxThreads * tick / 4.0);
                g.drawString(threads, left - metrics.stringWidth(threads) - 7,
                        y + metrics.getAscent() / 2);
                String samples = WorkloadChartModel.formatAxisTick(maxRps * tick / 4.0);
                g.drawString(samples, right + 7, y + metrics.getAscent() / 2);
            }
        }
        g.setColor(ThemeColors.border());
        g.drawLine(left, top, left, bottom);
        g.drawLine(right, top, right, bottom);
        g.drawLine(left, bottom, right, bottom);
    }

    private void drawLoad(Graphics2D g, int left, int right, int top, int bottom) {
        if (plan.shapeConfig().shape().usesOpenModelThreadGroup()) {
            drawOpenModel(g, left, right, top, bottom);
            return;
        }
        double width = right - left;
        double height = bottom - top;
        double total = Math.max(1, plan.durationSeconds());
        double rampX = left + width * plan.rampSeconds() / total;
        double maxThreads = WorkloadChartModel.threadAxisMaximum(plan);
        double threadPeakY = bottom - height * plan.threads() / maxThreads;
        Path2D area = new Path2D.Double();
        area.moveTo(left, bottom);
        area.lineTo(rampX, threadPeakY);
        area.lineTo(right, threadPeakY);
        area.lineTo(right, bottom);
        area.closePath();
        Color fillStart = new Color(ThemeColors.accent().getRed(), ThemeColors.accent().getGreen(),
                ThemeColors.accent().getBlue(), 100);
        Color fillEnd = new Color(ThemeColors.accent().getRed(), ThemeColors.accent().getGreen(),
                ThemeColors.accent().getBlue(), 8);
        g.setPaint(new GradientPaint(0, (float) threadPeakY, fillStart,
                0, bottom, fillEnd));
        g.fill(area);
        g.setColor(ThemeColors.accent());
        g.setStroke(new BasicStroke(2.2f));
        int rampPixelX = (int) Math.round(rampX);
        int threadPeakPixelY = (int) Math.round(threadPeakY);
        g.drawLine(left, bottom, rampPixelX, threadPeakPixelY);
        g.drawLine(rampPixelX, threadPeakPixelY, right, threadPeakPixelY);

        double maxRps = WorkloadChartModel.sampleRateAxisMaximum(plan);
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

    private void drawOpenModel(Graphics2D g, int left, int right, int top, int bottom) {
        double width = right - left;
        double height = bottom - top;
        double total = Math.max(1, plan.durationSeconds());
        double maxThreads = WorkloadChartModel.threadAxisMaximum(plan);
        double maxRps = WorkloadChartModel.sampleRateAxisMaximum(plan);
        Path2D threadPath = new Path2D.Double();
        Path2D threadArea = new Path2D.Double();
        Path2D ratePath = new Path2D.Double();
        for (int index = 0; index <= 120; index++) {
            double elapsed = total * index / 120.0;
            WorkloadChartModel.Series point = WorkloadChartModel.at(plan, elapsed);
            double x = left + width * elapsed / total;
            double threadY = bottom - height * point.threads() / maxThreads;
            double rateY = bottom - height * point.requestsPerSecond() / maxRps;
            if (index == 0) {
                threadPath.moveTo(x, threadY);
                threadArea.moveTo(x, bottom);
                threadArea.lineTo(x, threadY);
                ratePath.moveTo(x, rateY);
            } else {
                threadPath.lineTo(x, threadY);
                threadArea.lineTo(x, threadY);
                ratePath.lineTo(x, rateY);
            }
        }
        threadArea.lineTo(right, bottom);
        threadArea.closePath();
        Color fillStart = new Color(ThemeColors.accent().getRed(), ThemeColors.accent().getGreen(),
                ThemeColors.accent().getBlue(), 100);
        Color fillEnd = new Color(ThemeColors.accent().getRed(), ThemeColors.accent().getGreen(),
                ThemeColors.accent().getBlue(), 8);
        g.setPaint(new GradientPaint(0, top, fillStart, 0, bottom, fillEnd));
        g.fill(threadArea);
        g.setColor(ThemeColors.accent());
        g.setStroke(new BasicStroke(2.2f));
        g.draw(threadPath);
        g.setColor(ThemeColors.info());
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL,
                0, new float[]{5, 4}, 0));
        g.draw(ratePath);
    }

    private void drawTimeTicks(Graphics2D g, int left, int right, int bottom) {
        g.setFont(UIManager.getFont("Label.font"));
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
