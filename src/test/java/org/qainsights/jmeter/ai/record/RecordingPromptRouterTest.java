package org.qainsights.jmeter.ai.record;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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

        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<String> received = new AtomicReference<>();
        RecordingWorkflowRunnable runnable = (prompt, cb) -> {
            received.set(prompt);
            started.countDown();
        };

        RecordingPromptRouter router = new RecordingPromptRouter(controller, runnable);
        CommandCallback cb = mock(CommandCallback.class);

        assertTrue(router.route("test prompt", cb));

        assertTrue(started.await(5, TimeUnit.SECONDS), "the workflow should start");
        assertEquals("test prompt", received.get(),
                "the chat message is the recording brief");
    }

    @Test
    void should_leaveTheStateMachineToTheWorkflow_when_routing() {
        // RecordingWorkflowService owns every transition. If the router also moved to
        // PREFLIGHT, the service's own PREFLIGHT transition would be rejected as illegal.
        RecordingSessionController controller = new RecordingSessionController();
        controller.startSession(new SessionConfig("Prompt", "http://example.com", "chromium"), "dir");

        CountDownLatch blocked = new CountDownLatch(1);
        RecordingPromptRouter router = new RecordingPromptRouter(controller, (prompt, cb) -> {
            try {
                blocked.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        router.route("test prompt", mock(CommandCallback.class));

        assertEquals(RecordingSessionState.ARMED, controller.getSnapshot().state());
        blocked.countDown();
    }

    @Test
    void should_runOffTheEventDispatchThread() throws Exception {
        // A recording takes minutes; blocking the EDT would freeze JMeter.
        RecordingSessionController controller = new RecordingSessionController();
        controller.startSession(new SessionConfig("Prompt", "http://example.com", "chromium"), "dir");

        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Thread> workerThread = new AtomicReference<>();
        RecordingPromptRouter router = new RecordingPromptRouter(controller, (prompt, cb) -> {
            workerThread.set(Thread.currentThread());
            started.countDown();
        });

        router.route("test prompt", mock(CommandCallback.class));
        assertTrue(started.await(5, TimeUnit.SECONDS));

        assertNotSame(Thread.currentThread(), workerThread.get());
        assertTrue(workerThread.get().isDaemon(),
                "a stuck recording must not keep the JVM alive");
    }
}
