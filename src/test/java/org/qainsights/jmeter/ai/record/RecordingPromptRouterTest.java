package org.qainsights.jmeter.ai.record;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.gui.CommandCallback;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RecordingPromptRouter}.
 */
class RecordingPromptRouterTest {

    @Test
    void should_notRoute_when_notArmed() {
        RecordingSessionController controller = new RecordingSessionController();
        RecordingWorkflowRunnable runnable = mock(RecordingWorkflowRunnable.class);
        RecordingPromptRouter router = new RecordingPromptRouter(controller, runnable);

        CommandCallback cb = mock(CommandCallback.class);
        assertFalse(router.route("test prompt", cb));
        verifyNoInteractions(runnable);
    }

    @Test
    void should_routeAndStartWorkflow_when_armed() throws Exception {
        RecordingSessionController controller = new RecordingSessionController();
        SessionConfig config = new SessionConfig("Prompt", "http://example.com", "chromium");
        controller.startSession(config, "dir"); // state: ARMED

        AtomicBoolean runCalled = new AtomicBoolean(false);
        RecordingWorkflowRunnable runnable = (prompt, cb) -> runCalled.set(true);

        RecordingPromptRouter router = new RecordingPromptRouter(controller, runnable);
        CommandCallback cb = mock(CommandCallback.class);

        assertTrue(router.route("test prompt", cb));
        assertEquals(RecordingSessionState.PREFLIGHT, controller.getSnapshot().state());

        // Wait brief moment for background thread
        Thread.sleep(100);
        assertTrue(runCalled.get());
    }
}
