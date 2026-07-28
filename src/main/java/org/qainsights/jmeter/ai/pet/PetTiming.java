package org.qainsights.jmeter.ai.pet;

import java.util.EnumMap;
import java.util.Map;

/**
 * Per-frame display durations for each {@link PetState}, matching the codex
 * hatch-pet animation contract. Idle uses a breathing pattern (long holds on
 * the first and last frame, quick transitions between) rather than a uniform
 * cadence. Frame indices wrap modulo the row's frame count, so the duration
 * table only needs to list each frame once.
 */
public final class PetTiming {

    /** Fallback duration when a state has no explicit per-frame table. */
    static final int DEFAULT_FRAME_MS = 140;

    private final Map<PetState, int[]> durations;

    private PetTiming(Map<PetState, int[]> durations) {
        this.durations = durations;
    }

    /** The codex hatch-pet default timing table. */
    public static PetTiming defaults() {
        Map<PetState, int[]> table = new EnumMap<>(PetState.class);
        table.put(PetState.IDLE,           new int[]{300, 300, 300, 300, 300, 300});
        table.put(PetState.RUNNING_RIGHT,  new int[]{120, 120, 120, 120, 120, 120, 120, 220});
        table.put(PetState.RUNNING_LEFT,   new int[]{120, 120, 120, 120, 120, 120, 120, 220});
        table.put(PetState.WAVING,         new int[]{140, 140, 140, 280});
        table.put(PetState.JUMPING,         new int[]{140, 140, 140, 140, 280});
        table.put(PetState.FAILED,         new int[]{140, 140, 140, 140, 140, 140, 140, 240});
        table.put(PetState.WAITING,        new int[]{150, 150, 150, 150, 150, 260});
        table.put(PetState.RUNNING,        new int[]{120, 120, 120, 120, 120, 220});
        table.put(PetState.REVIEW,         new int[]{150, 150, 150, 150, 150, 280});
        return new PetTiming(table);
    }

    /**
     * Display duration in milliseconds for the given frame index within the
     * state's row. The index wraps modulo the number of entries in the state's
     * duration table, so callers can pass the raw animator frame index.
     */
    public int durationMs(PetState state, int frameIndex) {
        int[] row = durations.get(state);
        if (row == null || row.length == 0) {
            return DEFAULT_FRAME_MS;
        }
        return row[Math.floorMod(frameIndex, row.length)];
    }

    /** Number of duration entries defined for the state (may differ from the sprite frame count). */
    public int entryCount(PetState state) {
        int[] row = durations.get(state);
        return row == null ? 0 : row.length;
    }
}
