package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link TreeActivitySequencer}'s pacing/queueing state machine. */
class TreeActivitySequencerTest {

    private static final long T0 = 1_000_000L;

    @Test
    void tick_whenIdle_reportsIdleFrame() {
        TreeActivitySequencer<String> seq = new TreeActivitySequencer<>();

        TreeActivitySequencer.Frame<String> frame = seq.tick(T0);

        assertTrue(frame.isIdle());
        assertNull(frame.getTarget());
        assertEquals(0f, frame.getAlpha());
    }

    @Test
    void enqueue_whenIdle_becomesActiveImmediately() {
        TreeActivitySequencer<String> seq = new TreeActivitySequencer<>();

        seq.enqueue("a", T0);
        TreeActivitySequencer.Frame<String> frame = seq.tick(T0);

        assertEquals("a", frame.getTarget());
        assertEquals(1f, frame.getAlpha());
    }

    @Test
    void enqueue_secondTarget_staysOnFirstUntilFloorElapses() {
        TreeActivitySequencer<String> seq = new TreeActivitySequencer<>();
        seq.enqueue("a", T0);
        seq.enqueue("b", T0 + 100);

        TreeActivitySequencer.Frame<String> frame = seq.tick(T0 + 100);

        assertEquals("a", frame.getTarget(), "should still be showing 'a' - floor not yet elapsed");
    }

    @Test
    void tick_afterFloorElapses_advancesToNextQueuedTarget() {
        TreeActivitySequencer<String> seq = new TreeActivitySequencer<>();
        seq.enqueue("a", T0);
        seq.enqueue("b", T0 + 100);

        TreeActivitySequencer.Frame<String> frame = seq.tick(T0 + TreeActivitySequencer.MIN_VISIBLE_MILLIS);

        assertEquals("b", frame.getTarget());
        assertEquals(1f, frame.getAlpha());
    }

    @Test
    void enqueue_sameAsCurrentlyActiveTarget_isNoOpAndDoesNotQueue() {
        TreeActivitySequencer<String> seq = new TreeActivitySequencer<>();
        seq.enqueue("a", T0);
        seq.enqueue("a", T0 + 50);

        // Advancing well past the floor should still show 'a' (not re-show it from a queue entry).
        TreeActivitySequencer.Frame<String> frame = seq.tick(T0 + TreeActivitySequencer.MIN_VISIBLE_MILLIS + 10);
        assertEquals("a", frame.getTarget());

        // A distinct next tick shouldn't reveal a queued duplicate either.
        TreeActivitySequencer.Frame<String> next = seq.tick(T0 + TreeActivitySequencer.MIN_VISIBLE_MILLIS + 20);
        assertEquals("a", next.getTarget());
    }

    @Test
    void enqueue_duplicateBackOfQueueEntry_isNotAddedTwice() {
        TreeActivitySequencer<String> seq = new TreeActivitySequencer<>();
        seq.enqueue("a", T0);
        seq.enqueue("b", T0);
        seq.enqueue("b", T0); // duplicate of the queue's tail - should be ignored

        seq.tick(T0 + TreeActivitySequencer.MIN_VISIBLE_MILLIS); // advances to 'b'
        TreeActivitySequencer.Frame<String> frame = seq.tick(T0 + TreeActivitySequencer.MIN_VISIBLE_MILLIS + 10);

        // If 'b' had been queued twice, this next floor-elapse tick would still show 'b'
        // (queued again) instead of going idle.
        TreeActivitySequencer.Frame<String> afterSecondFloor =
                seq.tick(T0 + 2 * TreeActivitySequencer.MIN_VISIBLE_MILLIS + 10);
        assertEquals("b", frame.getTarget());
        assertTrue(afterSecondFloor.isIdle() || "b".equals(afterSecondFloor.getTarget()));
    }

    @Test
    void queue_overflow_dropsOldestEntries() {
        TreeActivitySequencer<Integer> seq = new TreeActivitySequencer<>();
        seq.enqueue(0, T0);
        for (int i = 1; i <= 20; i++) {
            seq.enqueue(i, T0);
        }

        long t = T0;
        Integer lastSeen = null;
        for (int i = 0; i <= 20; i++) {
            t += TreeActivitySequencer.MIN_VISIBLE_MILLIS;
            TreeActivitySequencer.Frame<Integer> frame = seq.tick(t);
            if (!frame.isIdle()) {
                lastSeen = frame.getTarget();
            }
        }
        // The most recently enqueued target must eventually be shown even though the
        // backlog vastly exceeded the cap.
        assertEquals(20, lastSeen);
    }

    @Test
    void finish_withNothingQueued_fadesOutAfterFloor() {
        TreeActivitySequencer<String> seq = new TreeActivitySequencer<>();
        seq.enqueue("a", T0);
        seq.finish();

        TreeActivitySequencer.Frame<String> stillShowing = seq.tick(T0 + 100);
        assertEquals("a", stillShowing.getTarget());
        assertEquals(1f, stillShowing.getAlpha());

        TreeActivitySequencer.Frame<String> fadingStart = seq.tick(T0 + TreeActivitySequencer.MIN_VISIBLE_MILLIS);
        assertEquals("a", fadingStart.getTarget());
        assertEquals(1f, fadingStart.getAlpha(), 0.01f);

        TreeActivitySequencer.Frame<String> fadingMid = seq.tick(
                T0 + TreeActivitySequencer.MIN_VISIBLE_MILLIS + TreeActivitySequencer.FADE_MILLIS / 2);
        assertEquals("a", fadingMid.getTarget());
        assertTrue(fadingMid.getAlpha() < 1f && fadingMid.getAlpha() > 0f);

        TreeActivitySequencer.Frame<String> fadedOut = seq.tick(
                T0 + TreeActivitySequencer.MIN_VISIBLE_MILLIS + TreeActivitySequencer.FADE_MILLIS + 1);
        assertTrue(fadedOut.isIdle());
    }

    @Test
    void finish_thenEnqueueDuringFade_abortsFadeAndResumesShowing() {
        TreeActivitySequencer<String> seq = new TreeActivitySequencer<>();
        seq.enqueue("a", T0);
        seq.finish();
        seq.tick(T0 + TreeActivitySequencer.MIN_VISIBLE_MILLIS); // now fading

        seq.enqueue("b", T0 + TreeActivitySequencer.MIN_VISIBLE_MILLIS + 10);
        TreeActivitySequencer.Frame<String> frame =
                seq.tick(T0 + TreeActivitySequencer.MIN_VISIBLE_MILLIS + 20);

        assertEquals("b", frame.getTarget());
        assertEquals(1f, frame.getAlpha());
    }

    @Test
    void finish_withNothingActive_staysIdle() {
        TreeActivitySequencer<String> seq = new TreeActivitySequencer<>();
        seq.finish();

        assertTrue(seq.tick(T0).isIdle());
    }

    @Test
    void reset_clearsActiveAndQueuedState() {
        TreeActivitySequencer<String> seq = new TreeActivitySequencer<>();
        seq.enqueue("a", T0);
        seq.enqueue("b", T0);

        seq.reset();

        assertTrue(seq.tick(T0).isIdle());
        // The previously queued 'b' must not resurface after reset.
        assertTrue(seq.tick(T0 + TreeActivitySequencer.MIN_VISIBLE_MILLIS).isIdle());
    }

    @Test
    void enqueue_nullTarget_isIgnored() {
        TreeActivitySequencer<String> seq = new TreeActivitySequencer<>();
        seq.enqueue(null, T0);

        assertTrue(seq.tick(T0).isIdle());
    }

    @Test
    void convenienceOverloads_useRealWallClock_andDoNotThrow() {
        TreeActivitySequencer<String> seq = new TreeActivitySequencer<>();
        assertDoesNotThrow(() -> seq.enqueue("a"));
        assertDoesNotThrow(() -> { seq.tick(); });
    }
}
