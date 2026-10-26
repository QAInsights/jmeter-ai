package org.qainsights.jmeter.ai.load;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class WorkloadShapeChartTest {

    @Test
    void repaintingWithANewPlanMatchesAFreshRender() {
        int width = 420;
        int height = 230;
        WorkloadPlan planA = plan(200, 600, 3600, 200);
        WorkloadPlan planB = plan(20, 60, 600, 10);
        LoadShapeChart reused = chart(width, height);
        BufferedImage reusedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        reused.setPlan(planA);
        paint(reused, reusedImage);
        reused.setPlan(planB);
        paint(reused, reusedImage);

        LoadShapeChart fresh = chart(width, height);
        fresh.setPlan(planB);
        BufferedImage freshImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        paint(fresh, freshImage);

        assertArrayEquals(pixels(freshImage), pixels(reusedImage));
    }

    private static LoadShapeChart chart(int width, int height) {
        LoadShapeChart chart = new LoadShapeChart();
        chart.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        chart.setSize(width, height);
        return chart;
    }

    private static void paint(LoadShapeChart chart, BufferedImage image) {
        Graphics2D graphics = image.createGraphics();
        try {
            chart.paint(graphics);
        } finally {
            graphics.dispose();
        }
    }

    private static int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(),
                null, 0, image.getWidth());
    }

    private static WorkloadPlan plan(int threads, long ramp, long hold, double samplesPerSecond) {
        return new WorkloadPlan(WorkloadInputs.TargetType.REQUEST_RATE, samplesPerSecond, threads, 1,
                samplesPerSecond, null, samplesPerSecond * 60, ramp, hold, ramp + hold,
                1, null, null, 500, 0, "load", List.of(), List.of(), "");
    }
}
