package org.qainsights.jmeter.ai.pet;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JLayeredPane;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Unit tests for {@link PetBootstrap}.
 */
class PetBootstrapTest {

    @Test
    void should_notStart_when_disabled() {
        AtomicInteger loads = new AtomicInteger();
        boolean started = PetBootstrap.start(
                PetConfig.parse("false", "feather", "0.5"),
                name -> {
                    loads.incrementAndGet();
                    return PetTestSupport.uniformSheet(4);
                });
        assertFalse(started);
        assertEquals(0, loads.get());
    }

    @Test
    void should_notStart_when_spritesheetMissing() {
        assumeFalse(GraphicsEnvironment.isHeadless());
        boolean started = PetBootstrap.start(
                PetConfig.parse("true", "feather", "0.5"),
                name -> {
                    throw new IOException("no spritesheet for " + name);
                });
        assertFalse(started);
    }

    @Test
    void should_start_when_enabledAndSheetLoads() {
        assumeFalse(GraphicsEnvironment.isHeadless());
        boolean started = PetBootstrap.start(
                PetConfig.parse("true", "monkey", "0.5"),
                name -> {
                    assertEquals("monkey", name);
                    return PetTestSupport.uniformSheet(4);
                });
        assertTrue(started);
    }

    @Test
    void should_beIdempotent_when_initializedTwice() {
        PetBootstrap.resetForTest();
        assertDoesNotThrow(PetBootstrap::initialize);
        assertDoesNotThrow(PetBootstrap::initialize);
        PetBootstrap.resetForTest();
    }

    @Test
    void should_installOverlay_when_paneBecomesAvailable() throws Exception {
        PetSpriteSheet sheet = PetTestSupport.uniformSheet(4);
        PetView view = new PetView(sheet, new PetAnimator(sheet::frameCount), 1.0);
        PetOverlay overlay = new PetOverlay(view);
        JLayeredPane pane = new JLayeredPane();
        pane.setSize(300, 200);

        PetBootstrap.scheduleInstall(overlay, () -> pane, 0);
        SwingUtilities.invokeAndWait(() -> { });

        assertSame(pane, view.getParent());
        overlay.uninstall();
    }

    @Test
    void should_resolvePaneSafely_when_guiMayBeAbsent() {
        // GuiPackage may or may not exist depending on which suites ran first in this
        // JVM; either way the lookup must not throw.
        assertDoesNotThrow(PetBootstrap::findMainFrameLayeredPane);
    }
}
