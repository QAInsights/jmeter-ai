package org.qainsights.jmeter.ai.pet;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * Shared helpers for pet tests: builds small synthetic spritesheet atlases with a
 * configurable number of opaque frames per row.
 */
final class PetTestSupport {

    static final int CELL_W = 16;
    static final int CELL_H = 20;

    private PetTestSupport() {
    }

    /**
     * Builds an 8x9 atlas where each row {@code r} has {@code frameCounts[r]} opaque
     * frames followed by fully transparent cells.
     */
    static BufferedImage atlasWithFrameCounts(int... frameCounts) {
        BufferedImage atlas = new BufferedImage(
                PetSpriteSheet.COLS * CELL_W, PetState.ROW_COUNT * CELL_H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = atlas.createGraphics();
        try {
            for (int row = 0; row < PetState.ROW_COUNT; row++) {
                int frames = row < frameCounts.length ? frameCounts[row] : 0;
                for (int col = 0; col < frames; col++) {
                    g.setColor(new Color(10 + row * 20, 40, 200 - col * 10));
                    g.fillRect(col * CELL_W + 2, row * CELL_H + 2, CELL_W - 4, CELL_H - 4);
                }
            }
        } finally {
            g.dispose();
        }
        return atlas;
    }

    /** Builds an atlas with the same frame count in every row. */
    static BufferedImage uniformAtlas(int framesPerRow) {
        int[] counts = new int[PetState.ROW_COUNT];
        java.util.Arrays.fill(counts, framesPerRow);
        return atlasWithFrameCounts(counts);
    }

    /** Builds a sheet from {@link #uniformAtlas}. */
    static PetSpriteSheet uniformSheet(int framesPerRow) {
        try {
            return PetSpriteSheet.fromImage(uniformAtlas(framesPerRow));
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }
}
