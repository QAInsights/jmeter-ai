package org.qainsights.jmeter.ai.gui;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Pure timing/queueing state machine behind the agent-activity tree glow: decides
 * which target is "active" (should be highlighted) at any given instant, enforcing
 * a minimum visible duration per target so fast-firing tool calls stay perceivable,
 * and a fade-out once the whole run is finished.
 * <p>
 * Deterministic and AWT-free - {@code now} is supplied by the caller (typically a
 * {@code javax.swing.Timer} tick) rather than read internally, so this is fully
 * unit-testable without a live Swing environment.
 *
 * @param <T> the highlighted target's identity (e.g. a tree node); compared via
 *            {@code equals}
 */
public final class TreeActivitySequencer<T> {

    /** Minimum time (ms) a target stays visible before advancing to the next queued one. */
    public static final long MIN_VISIBLE_MILLIS = 700;

    /** Duration (ms) of the fade-out once the run finishes and the queue drains. */
    public static final long FADE_MILLIS = 1000;

    /** Bounds the pending queue so a very long run doesn't trail minutes behind. */
    private static final int MAX_QUEUED = 12;

    private enum State { IDLE, SHOWING, FADING }

    /** Result of a {@link #tick} call: what should be painted right now. */
    public static final class Frame<T> {
        private final T target;
        private final float alpha;

        private Frame(T target, float alpha) {
            this.target = target;
            this.alpha = alpha;
        }

        /** The target to highlight, or {@code null} if nothing should be highlighted. */
        public T getTarget() {
            return target;
        }

        /** Opacity to paint the highlight at, from 0f (invisible) to 1f (fully visible). */
        public float getAlpha() {
            return alpha;
        }

        public boolean isIdle() {
            return target == null;
        }
    }

    private final Deque<T> pending = new ArrayDeque<>();
    private State state = State.IDLE;
    private T active;
    private long phaseStartedAt;
    private boolean finishRequested;

    /** Convenience overload using {@code System.currentTimeMillis()} as "now". */
    public void enqueue(T target) {
        enqueue(target, System.currentTimeMillis());
    }

    /**
     * Requests that {@code target} be highlighted. If nothing is currently showing,
     * it becomes active immediately; otherwise it is queued behind whatever is
     * currently visible (dropping the oldest queued entry if the backlog is full).
     * Re-requesting the currently-active (or already back-of-queue) target is a no-op.
     * Also cancels any in-progress fade-out (see {@link #finish()}), since new activity
     * means the run isn't actually done yet.
     */
    public synchronized void enqueue(T target, long nowMillis) {
        if (target == null) {
            return;
        }
        finishRequested = false;
        if (state == State.IDLE) {
            beginShowing(target, nowMillis);
            return;
        }
        if (state == State.SHOWING && target.equals(active)) {
            return;
        }
        if (!pending.isEmpty() && target.equals(pending.peekLast())) {
            return;
        }
        if (pending.size() >= MAX_QUEUED) {
            pending.pollFirst();
        }
        pending.addLast(target);
    }

    /**
     * Signals that the agent run has finished. Whatever is currently showing still
     * gets its full {@link #MIN_VISIBLE_MILLIS}, then (once the queue drains) fades
     * out over {@link #FADE_MILLIS} instead of advancing to a next target.
     */
    public synchronized void finish() {
        finishRequested = true;
    }

    /** Resets to idle immediately, discarding any active or queued targets. */
    public synchronized void reset() {
        state = State.IDLE;
        active = null;
        pending.clear();
        finishRequested = false;
    }

    /** Convenience overload using {@code System.currentTimeMillis()} as "now". */
    public Frame<T> tick() {
        return tick(System.currentTimeMillis());
    }

    /**
     * Advances the state machine to the given time and reports what should be
     * painted right now.
     *
     * @param nowMillis current time, e.g. {@code System.currentTimeMillis()}
     */
    public synchronized Frame<T> tick(long nowMillis) {
        if (state == State.SHOWING) {
            if (nowMillis - phaseStartedAt >= MIN_VISIBLE_MILLIS) {
                if (!pending.isEmpty()) {
                    beginShowing(pending.pollFirst(), nowMillis);
                } else if (finishRequested) {
                    state = State.FADING;
                    phaseStartedAt = nowMillis;
                }
            }
        } else if (state == State.FADING) {
            if (!pending.isEmpty()) {
                // New activity arrived mid-fade: abort the fade and resume immediately.
                beginShowing(pending.pollFirst(), nowMillis);
            } else if (nowMillis - phaseStartedAt >= FADE_MILLIS) {
                reset();
            }
        }

        if (state == State.IDLE) {
            return new Frame<>(null, 0f);
        }
        if (state == State.FADING) {
            long elapsed = nowMillis - phaseStartedAt;
            float alpha = 1f - Math.min(1f, elapsed / (float) FADE_MILLIS);
            return new Frame<>(active, alpha);
        }
        return new Frame<>(active, 1f);
    }

    private void beginShowing(T target, long nowMillis) {
        state = State.SHOWING;
        active = target;
        phaseStartedAt = nowMillis;
    }
}
