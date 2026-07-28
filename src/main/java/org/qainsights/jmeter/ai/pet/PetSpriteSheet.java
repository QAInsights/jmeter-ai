package org.qainsights.jmeter.ai.pet;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;
import javax.imageio.ImageIO;

/** Per-frame content bounds: {x, y, width, height} in cell-local coordinates. */
record FrameBounds(int x, int y, int width, int height) {}

/**
 * A sliced pet atlas: {@value #COLS} columns x {@value PetState#ROW_COUNT} rows of
 * equal-size cells (192x208 at native resolution), one row per {@link PetState}.
 * Frame counts are auto-detected per row by scanning cells left-to-right until the
 * first fully transparent cell, so no per-pet frame configuration is needed.
 */
public final class PetSpriteSheet {

    public static final int COLS = 8;
    /** Alpha values at or below this threshold count as transparent. */
    static final int ALPHA_THRESHOLD = 16;
    private static final String RESOURCE_PATTERN = "/org/qainsights/jmeter/ai/pet/%s/spritesheet.png";

    private final BufferedImage atlas;
    private final int cellWidth;
    private final int cellHeight;
    private final Map<PetState, Integer> frameCounts;
    private final Map<PetState, FrameBounds[]> frameBounds;
    private final int contentX;
    private final int contentY;
    private final int contentWidth;
    private final int contentHeight;

    private PetSpriteSheet(BufferedImage atlas) {
        this.atlas = atlas;
        this.cellWidth = atlas.getWidth() / COLS;
        this.cellHeight = atlas.getHeight() / PetState.ROW_COUNT;
        this.frameCounts = detectFrameCounts();
        this.frameBounds = detectFrameBounds();
        int[] bounds = detectContentBounds();
        this.contentX = bounds[0];
        this.contentY = bounds[1];
        this.contentWidth = bounds[2];
        this.contentHeight = bounds[3];
    }

    /** Loads the bundled spritesheet for the given pet name from plugin resources. */
    public static PetSpriteSheet load(String petName) throws IOException {
        String resource = String.format(RESOURCE_PATTERN, petName);
        try (InputStream in = PetSpriteSheet.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Pet spritesheet not found on classpath: " + resource);
            }
            return fromImage(ImageIO.read(in));
        }
    }

    /** Builds a sheet from an in-memory atlas image (used by tests and custom loads). */
    public static PetSpriteSheet fromImage(BufferedImage atlas) throws IOException {
        if (atlas == null) {
            throw new IOException("Pet spritesheet image could not be decoded");
        }
        if (atlas.getWidth() < COLS || atlas.getHeight() < PetState.ROW_COUNT) {
            throw new IOException("Pet spritesheet is too small: "
                    + atlas.getWidth() + "x" + atlas.getHeight());
        }
        return new PetSpriteSheet(atlas);
    }

    private Map<PetState, Integer> detectFrameCounts() {
        Map<PetState, Integer> counts = new EnumMap<>(PetState.class);
        for (PetState state : PetState.values()) {
            int count = 0;
            for (int col = 0; col < COLS; col++) {
                if (isCellEmpty(col, state.row())) {
                    break;
                }
                count++;
            }
            counts.put(state, count);
        }
        return counts;
    }

    private boolean isCellEmpty(int col, int row) {
        int x0 = col * cellWidth;
        int y0 = row * cellHeight;
        for (int y = y0; y < y0 + cellHeight; y++) {
            for (int x = x0; x < x0 + cellWidth; x++) {
                if (((atlas.getRGB(x, y) >>> 24) & 0xFF) > ALPHA_THRESHOLD) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Computes per-frame content bounding boxes for every used frame in every row. */
    private Map<PetState, FrameBounds[]> detectFrameBounds() {
        Map<PetState, FrameBounds[]> result = new EnumMap<>(PetState.class);
        for (PetState state : PetState.values()) {
            int count = frameCounts.get(state);
            FrameBounds[] bounds = new FrameBounds[count];
            for (int col = 0; col < count; col++) {
                bounds[col] = detectSingleFrameBounds(col, state.row());
            }
            result.put(state, bounds);
        }
        return result;
    }

    private FrameBounds detectSingleFrameBounds(int col, int row) {
        int x0 = col * cellWidth;
        int y0 = row * cellHeight;
        int minX = cellWidth, minY = cellHeight, maxX = 0, maxY = 0;
        boolean found = false;
        for (int y = y0; y < y0 + cellHeight; y++) {
            for (int x = x0; x < x0 + cellWidth; x++) {
                if (((atlas.getRGB(x, y) >>> 24) & 0xFF) > ALPHA_THRESHOLD) {
                    int lx = x - x0;
                    int ly = y - y0;
                    if (lx < minX) minX = lx;
                    if (ly < minY) minY = ly;
                    if (lx > maxX) maxX = lx;
                    if (ly > maxY) maxY = ly;
                    found = true;
                }
            }
        }
        if (!found) {
            return new FrameBounds(0, 0, cellWidth, cellHeight);
        }
        return new FrameBounds(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    /**
     * Computes the union bounding box of all non-transparent pixels across every
     * used frame in the atlas. This is the actual artwork area, excluding the
     * transparent padding around it. Returns {@code {x, y, width, height}} in
     * cell-local coordinates (relative to the top-left of each cell).
     */
    private int[] detectContentBounds() {
        int minX = cellWidth, minY = cellHeight, maxX = 0, maxY = 0;
        boolean found = false;
        for (PetState state : PetState.values()) {
            int row = state.row();
            int count = frameCounts.get(state);
            for (int col = 0; col < count; col++) {
                int x0 = col * cellWidth;
                int y0 = row * cellHeight;
                for (int y = y0; y < y0 + cellHeight; y++) {
                    for (int x = x0; x < x0 + cellWidth; x++) {
                        if (((atlas.getRGB(x, y) >>> 24) & 0xFF) > ALPHA_THRESHOLD) {
                            int localX = x - x0;
                            int localY = y - y0;
                            if (localX < minX) minX = localX;
                            if (localY < minY) minY = localY;
                            if (localX > maxX) maxX = localX;
                            if (localY > maxY) maxY = localY;
                            found = true;
                        }
                    }
                }
            }
        }
        if (!found) {
            return new int[]{0, 0, cellWidth, cellHeight};
        }
        return new int[]{minX, minY, maxX - minX + 1, maxY - minY + 1};
    }

    /** Number of non-empty frames detected for the state's row (may be 0). */
    public int frameCount(PetState state) {
        return frameCounts.get(state);
    }

    /**
     * Returns the frame image at {@code index} for the given state. The index wraps
     * around the detected frame count; rows with no frames throw.
     */
    public BufferedImage frame(PetState state, int index) {
        int count = frameCount(state);
        if (count == 0) {
            throw new IllegalArgumentException("Pet row has no frames: " + state);
        }
        int col = Math.floorMod(index, count);
        return atlas.getSubimage(col * cellWidth, state.row() * cellHeight, cellWidth, cellHeight);
    }

    public int cellWidth() {
        return cellWidth;
    }

    public int cellHeight() {
        return cellHeight;
    }

    /** X offset of the artwork within each cell (transparent padding to the left). */
    public int contentX() {
        return contentX;
    }

    /** Y offset of the artwork within each cell (transparent padding above). */
    public int contentY() {
        return contentY;
    }

    /** Width of the actual artwork, excluding transparent padding. */
    public int contentWidth() {
        return contentWidth;
    }

    /** Height of the actual artwork, excluding transparent padding. */
    public int contentHeight() {
        return contentHeight;
    }

    /**
     * Returns the content bounds of a specific frame within its cell, or null if
     * the state has no frames or the index is out of range.
     */
    public FrameBounds frameBounds(PetState state, int index) {
        FrameBounds[] bounds = frameBounds.get(state);
        if (bounds == null || index < 0 || index >= bounds.length) {
            return null;
        }
        return bounds[index];
    }
}
