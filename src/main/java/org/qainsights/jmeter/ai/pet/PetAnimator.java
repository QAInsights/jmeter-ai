package org.qainsights.jmeter.ai.pet;

import java.util.function.ToIntFunction;

/**
 * Headless animation state machine for a pet. Holds a looping <em>base</em> state
 * (idle while no test runs, running while one does), plays one-shot overlays
 * (jumping, failed, waving) that revert to the base state, and switches to a
 * directional running row while the pet is dragged.
 * <p>
 * All methods are synchronized: events arrive from JMeter engine threads while
 * {@link #tick()} runs on the Swing timer.
 */
public final class PetAnimator {

    /** How many full loops a one-shot state plays before reverting. */
    static final int ONE_SHOT_LOOPS = 2;

    private final ToIntFunction<PetState> frameCounts;

    private PetState baseState = PetState.IDLE;
    private PetState currentState = PetState.IDLE;
    private int frameIndex;
    private int oneShotFramesLeft;
    private boolean dragging;
    private boolean testRunning;
    private boolean chatBusy;
    private boolean hadFailures;

    /**
     * @param frameCounts frames available per state (usually {@code sheet::frameCount});
     *                    states reporting zero frames are skipped gracefully.
     */
    public PetAnimator(ToIntFunction<PetState> frameCounts) {
        this.frameCounts = frameCounts;
    }

    /** Advances one animation frame; call at the render tick rate. */
    public synchronized void tick() {
        frameIndex++;
        if (!currentState.isLooping() && !dragging) {
            oneShotFramesLeft--;
            if (oneShotFramesLeft <= 0) {
                enterState(baseState);
            }
        }
    }

    /** The state whose row should be rendered right now. */
    public synchronized PetState currentState() {
        return currentState;
    }

    /** The frame index to render (callers wrap it against the row's frame count). */
    public synchronized int frameIndex() {
        return frameIndex;
    }

    /** A test run started: burst of excitement, then work hard until it ends. */
    public synchronized void onTestStarted() {
        testRunning = true;
        hadFailures = false;
        baseState = pickAvailable(PetState.RUNNING, PetState.IDLE);
        playOneShot(PetState.JUMPING);
    }

    /** A sampler failed mid-run: frown, then get back to work. */
    public synchronized void onSampleFailure() {
        hadFailures = true;
        if (testRunning) {
            playOneShot(PetState.FAILED);
        }
    }

    /** The test run ended: celebrate a clean run, frown over a failed one. */
    public synchronized void onTestEnded() {
        testRunning = false;
        baseState = busyBase();
        playOneShot(hadFailures ? PetState.FAILED : PetState.WAVING);
    }

    /** The AI chat started processing: a burst of excitement, then deep review mode. */
    public synchronized void onBusyStarted() {
        chatBusy = true;
        baseState = busyBase();
        playOneShot(PetState.JUMPING);
    }

    /** The AI chat finished: back to idle unless a test run is still going. */
    public synchronized void onBusyEnded() {
        chatBusy = false;
        baseState = busyBase();
        enterState(baseState);
    }

    /**
     * The base state from the activity flags. Chat-busy alone means REVIEW
     * (thinking/analyzing, not pacing); a running test takes the stage with
     * RUNNING (test work dominates chat work); nothing busy means IDLE.
     */
    private PetState busyBase() {
        if (testRunning) {
            return pickAvailable(PetState.RUNNING, PetState.IDLE);
        }
        if (chatBusy) {
            return pickAvailable(PetState.REVIEW, PetState.RUNNING);
        }
        return PetState.IDLE;
    }

    /** The user started dragging the pet. */
    public synchronized void onDragStarted() {
        dragging = true;
        enterState(pickAvailable(PetState.RUNNING_RIGHT, PetState.IDLE));
    }

    /** The user dragged the pet by {@code dx} pixels horizontally. */
    public synchronized void onDragMoved(int dx) {
        if (!dragging || dx == 0) {
            return;
        }
        PetState facing = dx < 0
                ? pickAvailable(PetState.RUNNING_LEFT, PetState.RUNNING_RIGHT)
                : pickAvailable(PetState.RUNNING_RIGHT, PetState.RUNNING_LEFT);
        if (facing != currentState) {
            enterState(facing);
        }
    }

    /** The user released the pet: resume whatever it was doing. */
    public synchronized void onDragEnded() {
        dragging = false;
        enterState(baseState);
    }

    private void playOneShot(PetState state) {
        if (dragging) {
            return;
        }
        int frames = frameCounts.applyAsInt(state);
        if (frames <= 0) {
            enterState(baseState);
            return;
        }
        enterState(state);
        oneShotFramesLeft = frames * ONE_SHOT_LOOPS;
    }

    private void enterState(PetState state) {
        currentState = frameCounts.applyAsInt(state) > 0 ? state : fallback();
        frameIndex = 0;
    }

    private PetState pickAvailable(PetState preferred, PetState alternate) {
        return frameCounts.applyAsInt(preferred) > 0 ? preferred : alternate;
    }

    private PetState fallback() {
        return frameCounts.applyAsInt(PetState.IDLE) > 0 ? PetState.IDLE : PetState.values()[0];
    }
}
