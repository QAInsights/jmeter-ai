package org.qainsights.jmeter.ai.pet;

/**
 * The nine animation rows of a Feather Wand pet spritesheet, in atlas row order.
 * Looping states play continuously; non-looping ("one-shot") states play a fixed
 * number of loops and then revert to the animator's base state.
 */
public enum PetState {
    IDLE(0, true),
    RUNNING_RIGHT(1, true),
    RUNNING_LEFT(2, true),
    WAVING(3, false),
    JUMPING(4, false),
    FAILED(5, false),
    WAITING(6, true),
    RUNNING(7, true),
    REVIEW(8, true);

    /** Number of rows in a pet atlas. */
    public static final int ROW_COUNT = 9;

    private final int row;
    private final boolean looping;

    PetState(int row, boolean looping) {
        this.row = row;
        this.looping = looping;
    }

    /** Zero-based spritesheet row index for this state. */
    public int row() {
        return row;
    }

    /** Whether this state loops indefinitely (vs playing once and reverting). */
    public boolean isLooping() {
        return looping;
    }
}
