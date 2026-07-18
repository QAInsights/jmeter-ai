package org.qainsights.jmeter.ai.record;

import java.io.File;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qainsights.jmeter.ai.gui.CommandCallback;
import org.qainsights.jmeter.ai.service.ClaudeService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RecordingWorkflowService}.
 */
class RecordingWorkflowServiceTest {

    @Test
    void should_executeWorkflowSuccessfully(@TempDir File tempDir) throws Exception {
        RecordingSessionController controller = mock(RecordingSessionController.class);
        BrowserFlowPlanner planner = mock(BrowserFlowPlanner.class);
        CommandCallback cb = mock(CommandCallback.class);
        ClaudeService claude = mock(ClaudeService.class);

        SessionConfig config = new SessionConfig("Prompt", "https://example.com", "chromium");
        RecordingSessionSnapshot snapshot = new RecordingSessionSnapshot(
            RecordingSessionState.ARMED, System.currentTimeMillis(), config, tempDir.getAbsolutePath(), null
        );

        when(controller.getSnapshot()).thenReturn(snapshot);
        when(cb.getSelectedModel()).thenReturn("claude-test");
        when(cb.resolveAiService("claude-test")).thenReturn(claude);

        BrowserFlowPlan plan = new BrowserFlowPlan(
            List.of(new BrowserStep("navigate", "", "", "https://example.com", null, "Navigate")),
            "Test Plan"
        );
        when(planner.plan(anyString(), any())).thenReturn(plan);

        PlaywrightBrowserSession session = mock(PlaywrightBrowserSession.class);
        StepExecutionResult result = new StepExecutionResult(plan.steps().get(0), true, 100, null, null);
        when(session.executeStep(any())).thenReturn(result);

        RecordingWorkflowService service = new RecordingWorkflowService(controller, planner, () -> session);
        service.run("Prompt", cb);

        verify(controller).transitionTo(RecordingSessionState.PLANNING);
        verify(controller).transitionTo(RecordingSessionState.EXECUTING);
    }
}
