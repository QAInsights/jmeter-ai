package org.qainsights.jmeter.ai.pet;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PetView}.
 */
class PetViewTest {

    private static PetView newView(double scale) {
        PetSpriteSheet sheet = PetTestSupport.uniformSheet(4);
        return new PetView(sheet, new PetAnimator(sheet::frameCount), scale);
    }

    @Test
    void should_sizeToScaledContent_when_created() {
        PetView view = newView(0.5);
        Dimension size = view.getPreferredSize();
        // view is sized to content bounds (CELL_W-4 x CELL_H-4), not full cell
        assertEquals(Math.round((PetTestSupport.CELL_W - 4) * 0.5f), size.width);
        assertEquals(Math.round((PetTestSupport.CELL_H - 4) * 0.5f), size.height);
        assertFalse(view.isOpaque());
    }

    @Test
    void should_advanceAnimator_when_ticked() {
        PetSpriteSheet sheet = PetTestSupport.uniformSheet(4);
        PetAnimator animator = new PetAnimator(sheet::frameCount);
        PetView view = new PetView(sheet, animator, 1.0);
        view.tickOnce();
        view.tickOnce();
        assertEquals(2, animator.frameIndex());
    }

    @Test
    void should_adjustTimerDelayPerFrame_when_stateChanges() {
        PetTiming timing = PetTiming.defaults();
        PetSpriteSheet sheet = PetTestSupport.uniformSheet(8);
        PetAnimator animator = new PetAnimator(sheet::frameCount);
        PetView view = new PetView(sheet, animator, timing, 1.0);
        view.startAnimation();
        // initial delay is the idle frame 0 long hold (280ms)
        assertEquals(timing.durationMs(PetState.IDLE, 0), view.getTimerDelay());
        animator.onTestStarted();
        view.tickOnce();
        // onTestStarted plays the JUMPING one-shot first; after tick frame index is 1
        assertEquals(timing.durationMs(PetState.JUMPING, 1), view.getTimerDelay());
        view.stopAnimation();
    }

    @Test
    void should_holdSteadyIdleDelay_when_loopingIdleFrames() {
        PetTiming timing = PetTiming.defaults();
        PetSpriteSheet sheet = PetTestSupport.uniformSheet(6);
        PetAnimator animator = new PetAnimator(sheet::frameCount);
        PetView view = new PetView(sheet, animator, timing, 1.0);
        view.startAnimation();
        // idle is a uniform 300ms long hold across all frames
        assertEquals(300, view.getTimerDelay());
        view.tickOnce();
        assertEquals(300, view.getTimerDelay());
        view.tickOnce();
        assertEquals(300, view.getTimerDelay());
        view.stopAnimation();
    }

    @Test
    void should_paintVisiblePixels_when_frameAvailable() {
        PetView view = newView(1.0);
        // canvas matches the content-sized view (CELL_W-4 x CELL_H-4)
        int cw = PetTestSupport.CELL_W - 4;
        int ch = PetTestSupport.CELL_H - 4;
        BufferedImage canvas = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        try {
            view.paint(g);
        } finally {
            g.dispose();
        }
        assertTrue(((canvas.getRGB(cw / 2, ch / 2) >>> 24) & 0xFF) > 0, "expected opaque pet pixels");
    }

    @Test
    void should_paintVisiblePixels_when_frameIndexWrapsPastRowEnd() {
        PetSpriteSheet sheet = PetTestSupport.uniformSheet(4);
        PetAnimator animator = new PetAnimator(sheet::frameCount);
        PetView view = new PetView(sheet, animator, 1.0);
        for (int i = 0; i < 9; i++) {
            animator.tick();
        }
        int cw = PetTestSupport.CELL_W - 4;
        int ch = PetTestSupport.CELL_H - 4;
        BufferedImage canvas = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        try {
            view.paint(g);
        } finally {
            g.dispose();
        }
        assertTrue(((canvas.getRGB(cw / 2, ch / 2) >>> 24) & 0xFF) > 0,
                "expected opaque pet pixels after the frame index wrapped past the row end");
    }

    @Test
    void should_notThrow_when_paintingEmptyRow() throws Exception {
        PetSpriteSheet sheet = PetSpriteSheet.fromImage(
                PetTestSupport.atlasWithFrameCounts(0, 0, 0, 0, 0, 0, 0, 0, 0));
        PetView view = new PetView(sheet, new PetAnimator(sheet::frameCount), 1.0);
        BufferedImage canvas = new BufferedImage(16, 20, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        try {
            assertDoesNotThrow(() -> view.paint(g));
        } finally {
            g.dispose();
        }
    }

    @Test
    void should_clampToParentBounds_when_movedOutside() {
        PetView view = newView(1.0);
        JPanel parent = new JPanel(null);
        parent.setSize(100, 100);
        parent.add(view);
        view.moveWithinParent(500, 500);
        assertEquals(100 - view.getWidth(), view.getX());
        assertEquals(100 - view.getHeight(), view.getY());
        view.moveWithinParent(-50, -50);
        assertEquals(0, view.getX());
        assertEquals(0, view.getY());
    }

    @Test
    void should_notifyMoveListener_when_moved() {
        PetView view = newView(1.0);
        AtomicInteger moves = new AtomicInteger();
        view.setUserMoveListener(moves::incrementAndGet);
        view.moveWithinParent(10, 10);
        assertEquals(1, moves.get());
        assertEquals(10, view.getX());
        assertEquals(10, view.getY());
    }

    @Test
    void should_stopTimer_when_animationStopped() {
        PetView view = newView(1.0);
        view.startAnimation();
        view.stopAnimation();
        assertDoesNotThrow(view::stopAnimation);
    }
}
