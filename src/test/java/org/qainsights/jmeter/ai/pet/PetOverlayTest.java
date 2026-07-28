package org.qainsights.jmeter.ai.pet;

import javax.swing.JLayeredPane;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PetOverlay}.
 */
class PetOverlayTest {

    private PetOverlay overlay;

    private static PetView newView() {
        PetSpriteSheet sheet = PetTestSupport.uniformSheet(4);
        return new PetView(sheet, new PetAnimator(sheet::frameCount), 1.0);
    }

    private static JLayeredPane newPane(int width, int height) {
        JLayeredPane pane = new JLayeredPane();
        pane.setSize(width, height);
        return pane;
    }

    @AfterEach
    void tearDown() {
        if (overlay != null) {
            overlay.uninstall();
        }
    }

    @Test
    void should_anchorBottomRight_when_installed() {
        PetView view = newView();
        overlay = new PetOverlay(view);
        JLayeredPane pane = newPane(400, 300);
        overlay.install(pane);

        assertSame(pane, view.getParent());
        assertEquals(400 - view.getWidth() - PetOverlay.MARGIN, view.getX());
        assertEquals(300 - view.getHeight() - PetOverlay.MARGIN, view.getY());
        assertTrue(overlay.isAutoAnchored());
    }

    @Test
    void should_reanchor_when_resizedBeforeUserMove() {
        PetView view = newView();
        overlay = new PetOverlay(view);
        overlay.install(newPane(400, 300));

        view.getParent().setSize(600, 500);
        overlay.reposition();

        assertEquals(600 - view.getWidth() - PetOverlay.MARGIN, view.getX());
        assertEquals(500 - view.getHeight() - PetOverlay.MARGIN, view.getY());
    }

    @Test
    void should_stopAutoAnchoring_when_userDragsPet() {
        PetView view = newView();
        overlay = new PetOverlay(view);
        overlay.install(newPane(400, 300));

        view.moveWithinParent(50, 60);
        assertFalse(overlay.isAutoAnchored());

        view.getParent().setSize(600, 500);
        overlay.reposition();
        assertEquals(50, view.getX());
        assertEquals(60, view.getY());
    }

    @Test
    void should_clampUserPosition_when_windowShrinks() {
        PetView view = newView();
        overlay = new PetOverlay(view);
        JLayeredPane pane = newPane(400, 300);
        overlay.install(pane);

        view.moveWithinParent(350, 250);
        pane.setSize(120, 100);
        overlay.reposition();

        assertTrue(view.getX() + view.getWidth() <= 120);
        assertTrue(view.getY() + view.getHeight() <= 100);
    }

    @Test
    void should_removeView_when_uninstalled() {
        PetView view = newView();
        overlay = new PetOverlay(view);
        JLayeredPane pane = newPane(400, 300);
        overlay.install(pane);

        overlay.uninstall();
        assertNull(view.getParent());
        assertEquals(0, pane.getComponentCount());
    }

    @Test
    void should_beIdempotent_when_installedOrUninstalledTwice() {
        PetView view = newView();
        overlay = new PetOverlay(view);
        JLayeredPane pane = newPane(400, 300);
        overlay.install(pane);
        overlay.install(newPane(100, 100));
        assertSame(pane, view.getParent());

        overlay.uninstall();
        assertDoesNotThrow(() -> overlay.uninstall());
    }
}
