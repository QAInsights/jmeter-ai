package org.qainsights.jmeter.ai.record;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RecordingSessionController}.
 */
class RecordingSessionControllerTest {

    @Test
    void should_allowValidTransitions_when_invoked() {
        RecordingSessionController controller = new RecordingSessionController();
        assertEquals(RecordingSessionState.OFF, controller.getSnapshot().state());

        SessionConfig config = new SessionConfig("Prompt", "http://example.com", "chromium");
        controller.startSession(config, "dir");
        assertEquals(RecordingSessionState.ARMED, controller.getSnapshot().state());

        controller.transitionTo(RecordingSessionState.PREFLIGHT);
        assertEquals(RecordingSessionState.PREFLIGHT, controller.getSnapshot().state());

        controller.transitionTo(RecordingSessionState.PLANNING);
        controller.transitionTo(RecordingSessionState.EXECUTING);
        controller.transitionTo(RecordingSessionState.FLUSHING_HAR);
        controller.transitionTo(RecordingSessionState.CONVERTING);
        controller.transitionTo(RecordingSessionState.LOADING);
        controller.transitionTo(RecordingSessionState.AWAITING_CORRELATION);
        controller.transitionTo(RecordingSessionState.REVIEWING_CORRELATION);
        controller.transitionTo(RecordingSessionState.SAVING);
        controller.transitionTo(RecordingSessionState.DONE);
        assertEquals(RecordingSessionState.DONE, controller.getSnapshot().state());

        controller.transitionTo(RecordingSessionState.OFF);
        assertEquals(RecordingSessionState.OFF, controller.getSnapshot().state());
    }

    @Test
    void should_throwException_when_transitionIsIllegal() {
        RecordingSessionController controller = new RecordingSessionController();
        assertThrows(IllegalStateException.class, () -> controller.transitionTo(RecordingSessionState.PLANNING));
    }

    @Test
    void should_notifyListeners_when_transitionOccurs() {
        RecordingSessionController controller = new RecordingSessionController();
        List<RecordingSessionSnapshot> snapshots = new ArrayList<>();
        controller.addListener(snapshots::add);

        SessionConfig config = new SessionConfig("Prompt", "http://example.com", "chromium");
        controller.startSession(config, "dir");

        assertEquals(1, snapshots.size());
        assertEquals(RecordingSessionState.ARMED, snapshots.get(0).state());

        controller.transitionTo(RecordingSessionState.PREFLIGHT);
        assertEquals(2, snapshots.size());
        assertEquals(RecordingSessionState.PREFLIGHT, snapshots.get(1).state());
    }

    @Test
    void should_allowAnyActiveToFailedOrCancelled_when_active() {
        RecordingSessionController controller = new RecordingSessionController();
        SessionConfig config = new SessionConfig("Prompt", "http://example.com", "chromium");
        controller.startSession(config, "dir");

        controller.transitionTo(RecordingSessionState.CANCELLED);
        assertEquals(RecordingSessionState.CANCELLED, controller.getSnapshot().state());
    }
}
