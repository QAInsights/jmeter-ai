package org.qainsights.jmeter.ai.pet;

import java.awt.image.BufferedImage;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PetSpriteSheet}.
 */
class PetSpriteSheetTest {

    @Test
    void should_deriveCellSizeFromAtlas_when_sliced() throws IOException {
        PetSpriteSheet sheet = PetSpriteSheet.fromImage(PetTestSupport.uniformAtlas(3));
        assertEquals(PetTestSupport.CELL_W, sheet.cellWidth());
        assertEquals(PetTestSupport.CELL_H, sheet.cellHeight());
    }

    @Test
    void should_detectFrameCountsPerRow_when_rowsDiffer() throws IOException {
        BufferedImage atlas = PetTestSupport.atlasWithFrameCounts(6, 8, 8, 4, 5, 4, 4, 6, 6);
        PetSpriteSheet sheet = PetSpriteSheet.fromImage(atlas);
        assertEquals(6, sheet.frameCount(PetState.IDLE));
        assertEquals(8, sheet.frameCount(PetState.RUNNING_RIGHT));
        assertEquals(8, sheet.frameCount(PetState.RUNNING_LEFT));
        assertEquals(4, sheet.frameCount(PetState.WAVING));
        assertEquals(5, sheet.frameCount(PetState.JUMPING));
        assertEquals(4, sheet.frameCount(PetState.FAILED));
        assertEquals(4, sheet.frameCount(PetState.WAITING));
        assertEquals(6, sheet.frameCount(PetState.RUNNING));
        assertEquals(6, sheet.frameCount(PetState.REVIEW));
    }

    @Test
    void should_reportZeroFrames_when_rowIsFullyTransparent() throws IOException {
        BufferedImage atlas = PetTestSupport.atlasWithFrameCounts(2, 0, 0, 0, 0, 0, 0, 0, 0);
        PetSpriteSheet sheet = PetSpriteSheet.fromImage(atlas);
        assertEquals(2, sheet.frameCount(PetState.IDLE));
        assertEquals(0, sheet.frameCount(PetState.RUNNING_RIGHT));
    }

    @Test
    void should_returnCellSizedFrames_when_requested() throws IOException {
        PetSpriteSheet sheet = PetTestSupport.uniformSheet(4);
        BufferedImage frame = sheet.frame(PetState.IDLE, 0);
        assertEquals(PetTestSupport.CELL_W, frame.getWidth());
        assertEquals(PetTestSupport.CELL_H, frame.getHeight());
    }

    @Test
    void should_wrapFrameIndex_when_beyondFrameCount() throws IOException {
        PetSpriteSheet sheet = PetTestSupport.uniformSheet(3);
        BufferedImage direct = sheet.frame(PetState.RUNNING, 1);
        BufferedImage wrapped = sheet.frame(PetState.RUNNING, 4);
        assertEquals(direct.getRGB(3, 3), wrapped.getRGB(3, 3));
    }

    @Test
    void should_throw_when_frameRequestedFromEmptyRow() throws IOException {
        BufferedImage atlas = PetTestSupport.atlasWithFrameCounts(1, 0, 0, 0, 0, 0, 0, 0, 0);
        PetSpriteSheet sheet = PetSpriteSheet.fromImage(atlas);
        assertThrows(IllegalArgumentException.class, () -> sheet.frame(PetState.WAVING, 0));
    }

    @Test
    void should_throw_when_imageIsNullOrTooSmall() {
        assertThrows(IOException.class, () -> PetSpriteSheet.fromImage(null));
        BufferedImage tiny = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        assertThrows(IOException.class, () -> PetSpriteSheet.fromImage(tiny));
    }

    @Test
    void should_throw_when_bundledSpritesheetMissing() {
        assertThrows(IOException.class, () -> PetSpriteSheet.load("no-such-pet"));
    }

    @Test
    void should_loadBundledQuillSpritesheet_when_requestedByName() throws IOException {
        PetSpriteSheet sheet = PetSpriteSheet.load("quill");
        assertEquals(192, sheet.cellWidth());
        assertEquals(208, sheet.cellHeight());
        assertEquals(6, sheet.frameCount(PetState.IDLE));
        assertEquals(8, sheet.frameCount(PetState.RUNNING_RIGHT));
        assertEquals(8, sheet.frameCount(PetState.RUNNING_LEFT));
        assertEquals(4, sheet.frameCount(PetState.WAVING));
        assertEquals(5, sheet.frameCount(PetState.JUMPING));
        assertEquals(8, sheet.frameCount(PetState.FAILED));
        assertEquals(6, sheet.frameCount(PetState.WAITING));
        assertEquals(6, sheet.frameCount(PetState.RUNNING));
        assertEquals(6, sheet.frameCount(PetState.REVIEW));
    }

    @Test
    void should_loadBundledMonkeySpritesheet_when_requestedByName() throws IOException {
        PetSpriteSheet sheet = PetSpriteSheet.load("monkey");
        assertEquals(192, sheet.cellWidth());
        assertEquals(208, sheet.cellHeight());
        assertEquals(6, sheet.frameCount(PetState.IDLE));
        assertEquals(8, sheet.frameCount(PetState.RUNNING_RIGHT));
        assertEquals(8, sheet.frameCount(PetState.RUNNING_LEFT));
        assertEquals(4, sheet.frameCount(PetState.WAVING));
        assertEquals(5, sheet.frameCount(PetState.JUMPING));
        assertEquals(8, sheet.frameCount(PetState.FAILED));
        assertEquals(6, sheet.frameCount(PetState.WAITING));
        assertEquals(6, sheet.frameCount(PetState.RUNNING));
        assertEquals(6, sheet.frameCount(PetState.REVIEW));
    }

    @Test
    void should_loadBundledGlimSpritesheet_when_requestedByName() throws IOException {
        PetSpriteSheet sheet = PetSpriteSheet.load("glim");
        assertEquals(192, sheet.cellWidth());
        assertEquals(208, sheet.cellHeight());
        assertEquals(6, sheet.frameCount(PetState.IDLE));
        assertEquals(8, sheet.frameCount(PetState.RUNNING_RIGHT));
        assertEquals(8, sheet.frameCount(PetState.RUNNING_LEFT));
        assertEquals(4, sheet.frameCount(PetState.WAVING));
        assertEquals(5, sheet.frameCount(PetState.JUMPING));
        assertEquals(8, sheet.frameCount(PetState.FAILED));
        assertEquals(6, sheet.frameCount(PetState.WAITING));
        assertEquals(6, sheet.frameCount(PetState.RUNNING));
        assertEquals(6, sheet.frameCount(PetState.REVIEW));
    }

    @Test
    void should_loadBundledPeacockSpritesheet_when_requestedByName() throws IOException {
        PetSpriteSheet sheet = PetSpriteSheet.load("peacock");
        assertEquals(192, sheet.cellWidth());
        assertEquals(208, sheet.cellHeight());
        assertEquals(6, sheet.frameCount(PetState.IDLE));
        assertEquals(8, sheet.frameCount(PetState.RUNNING_RIGHT));
        assertEquals(8, sheet.frameCount(PetState.RUNNING_LEFT));
        assertEquals(4, sheet.frameCount(PetState.WAVING));
        assertEquals(5, sheet.frameCount(PetState.JUMPING));
        assertEquals(8, sheet.frameCount(PetState.FAILED));
        assertEquals(6, sheet.frameCount(PetState.WAITING));
        assertEquals(6, sheet.frameCount(PetState.RUNNING));
        assertEquals(6, sheet.frameCount(PetState.REVIEW));
    }

    @Test
    void should_detectContentBounds_when_artworkHasPadding() throws IOException {
        PetSpriteSheet sheet = PetTestSupport.uniformSheet(4);
        // test atlas fills (2,2)-(CELL_W-2, CELL_H-2) within each cell
        assertEquals(2, sheet.contentX());
        assertEquals(2, sheet.contentY());
        assertEquals(PetTestSupport.CELL_W - 4, sheet.contentWidth());
        assertEquals(PetTestSupport.CELL_H - 4, sheet.contentHeight());
    }

    @Test
    void should_contentSmallerThanCell_when_paddingExists() throws IOException {
        PetSpriteSheet sheet = PetTestSupport.uniformSheet(4);
        assertTrue(sheet.contentWidth() < sheet.cellWidth(),
                "content should be narrower than cell due to padding");
        assertTrue(sheet.contentHeight() < sheet.cellHeight(),
                "content should be shorter than cell due to padding");
    }

    @Test
    void should_defaultToFullCell_when_atlasIsEmpty() throws IOException {
        BufferedImage atlas = PetTestSupport.atlasWithFrameCounts(0, 0, 0, 0, 0, 0, 0, 0, 0);
        PetSpriteSheet sheet = PetSpriteSheet.fromImage(atlas);
        assertEquals(0, sheet.contentX());
        assertEquals(0, sheet.contentY());
        assertEquals(sheet.cellWidth(), sheet.contentWidth());
        assertEquals(sheet.cellHeight(), sheet.contentHeight());
    }
}
