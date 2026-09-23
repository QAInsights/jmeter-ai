package org.qainsights.jmeter.ai.record;

import java.nio.file.Paths;
import java.util.List;
import org.apache.jmeter.gui.GuiPackage;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.qainsights.jmeter.ai.gui.CommandCallback;
import org.qainsights.jmeter.ai.service.AiService;
import org.qainsights.jmeter.ai.service.ClaudeService;
import org.qainsights.jmeter.ai.utils.AiConfig;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DefaultRecordingWorkflow}.
 */
class DefaultRecordingWorkflowTest {

    private static final SessionConfig CONFIGURED =
            new SessionConfig("Dialog prompt", "http://example.com", "chromium");

    private static RecordingSessionSnapshot snapshot(SessionConfig config, String artifactDir) {
        return new RecordingSessionSnapshot(RecordingSessionState.ARMED, 0L, config, artifactDir, null);
    }

    // ---- configFor ----

    @Test
    void should_throwRecordingException_when_noConfigInSnapshot() {
        RecordingException e = assertThrows(RecordingException.class,
                () -> DefaultRecordingWorkflow.configFor(snapshot(null, null), "Buy a book"));
        assertTrue(e.getMessage().contains("No recording session is configured"));
    }

    @Test
    void should_useDialogPrompt_when_chatPromptIsBlank() {
        assertEquals("Dialog prompt",
                DefaultRecordingWorkflow.configFor(snapshot(CONFIGURED, null), "   ").prompt());
        assertEquals("Dialog prompt",
                DefaultRecordingWorkflow.configFor(snapshot(CONFIGURED, null), null).prompt());
    }

    @Test
    void should_useTrimmedChatPrompt_when_chatPromptGiven() {
        SessionConfig config = DefaultRecordingWorkflow.configFor(snapshot(CONFIGURED, null), "  Buy a book  ");
        assertEquals("Buy a book", config.prompt());
        assertEquals("http://example.com", config.baseUri());
        assertEquals("chromium", config.browser());
    }

    // ---- parseLong / maxIterations ----

    @Test
    void should_parseTrimmedNumber_when_valid() {
        assertEquals(42L, DefaultRecordingWorkflow.parseLong(" 42 ", 7L));
    }

    @Test
    void should_fallBack_when_valueIsMalformed() {
        assertEquals(7L, DefaultRecordingWorkflow.parseLong("lots", 7L));
        assertEquals(7L, DefaultRecordingWorkflow.parseLong("", 7L));
        assertEquals(7L, DefaultRecordingWorkflow.parseLong(null, 7L));
    }

    @Test
    void should_readMaxIterationsFromProperty_when_numeric() {
        try (MockedStatic<AiConfig> aiConfig = mockStatic(AiConfig.class)) {
            aiConfig.when(() -> AiConfig.getProperty(eq(RecordingWorkflowService.MAX_ITERATIONS_KEY), anyString()))
                    .thenReturn("25");
            assertEquals(25, DefaultRecordingWorkflow.maxIterations());
        }
    }

    @Test
    void should_useDefaultMaxIterations_when_propertyIsMalformed() {
        try (MockedStatic<AiConfig> aiConfig = mockStatic(AiConfig.class)) {
            aiConfig.when(() -> AiConfig.getProperty(eq(RecordingWorkflowService.MAX_ITERATIONS_KEY), anyString()))
                    .thenReturn("not-a-number");
            assertEquals(RecordingWorkflowService.DEFAULT_MAX_ITERATIONS, DefaultRecordingWorkflow.maxIterations());
        }
    }

    // ---- report ----

    @Test
    void should_reportCompletionWithStepsAndFinalText_when_completed() {
        RecordingWorkflowService.RecordingOutcome outcome = new RecordingWorkflowService.RecordingOutcome(
                true, "All done.", 12, List.of("Login", "Checkout"));

        String report = DefaultRecordingWorkflow.report(outcome);

        assertTrue(report.startsWith("Recording complete. "));
        assertTrue(report.contains("Captured 12 requests across 2 steps: Login, Checkout."));
        assertTrue(report.contains("\n\nAll done."));
        assertTrue(report.endsWith("run Correlation Studio to parameterise any dynamic values."));
        assertFalse(report.contains("stopped before"));
    }

    @Test
    void should_reportStoppedWithoutSteps_when_notCompleted() {
        RecordingWorkflowService.RecordingOutcome outcome = new RecordingWorkflowService.RecordingOutcome(
                false, null, 3, null);

        String report = DefaultRecordingWorkflow.report(outcome);

        assertTrue(report.startsWith("Recording stopped before the agent finished the scenario. "));
        assertTrue(report.contains("Whatever was captured is still in your test plan. "));
        assertTrue(report.contains("Captured 3 requests."));
        assertFalse(report.contains(" across "));
        assertFalse(report.contains("\n\n\n"));
        assertTrue(report.contains("Review the recorded samplers"));
    }

    // ---- run ----

    @Test
    void should_reportFailureAndCleanUp_when_noSessionConfigured() {
        RecordingSessionController controller = mock(RecordingSessionController.class);
        when(controller.getSnapshot()).thenReturn(snapshot(null, null));
        RecordingArtifactStore store = mock(RecordingArtifactStore.class);
        CommandCallback cb = mock(CommandCallback.class);

        new DefaultRecordingWorkflow(controller, store).run("Buy a book", cb);

        verify(cb).processAiResponse(startsWith("Recording failed: No recording session is configured"));
        verify(controller).resetToOff();
        verify(cb).setInputEnabled(true);
        verify(cb, never()).resolveAiService(any());
    }

    @Test
    void should_reportFailureAndCleanUp_when_modelDoesNotSupportTools() {
        RecordingSessionController controller = mock(RecordingSessionController.class);
        when(controller.getSnapshot()).thenReturn(snapshot(CONFIGURED, null));
        RecordingArtifactStore store = mock(RecordingArtifactStore.class);
        CommandCallback cb = mock(CommandCallback.class);
        when(cb.getSelectedModel()).thenReturn("some-model");
        when(cb.resolveAiService("some-model")).thenReturn(mock(AiService.class));

        new DefaultRecordingWorkflow(controller, store).run("Buy a book", cb);

        verify(cb).processAiResponse(startsWith("Recording failed: Record Mode needs a tool-calling model"));
        verify(controller).resetToOff();
        verify(cb).setInputEnabled(true);
    }

    @Test
    void should_reportFailureAndCleanUp_when_guiIsUnavailable() {
        RecordingSessionController controller = mock(RecordingSessionController.class);
        when(controller.getSnapshot()).thenReturn(snapshot(CONFIGURED, null));
        RecordingArtifactStore store = mock(RecordingArtifactStore.class);
        CommandCallback cb = mock(CommandCallback.class);
        when(cb.getSelectedModel()).thenReturn("claude");
        when(cb.resolveAiService("claude")).thenReturn(mock(ClaudeService.class));

        try (MockedStatic<GuiPackage> gui = mockStatic(GuiPackage.class)) {
            gui.when(GuiPackage::getInstance).thenReturn(null);

            new DefaultRecordingWorkflow(controller, store).run("Buy a book", cb);
        }

        verify(cb).processAiResponse(startsWith("Recording failed: Record Mode needs the JMeter GUI"));
        verify(controller).resetToOff();
        verify(cb).setInputEnabled(true);
        verifyNoInteractions(store);
    }

    @Test
    void should_reportUnexpectedFailureAndCleanUp_when_runtimeExceptionEscapes() {
        RecordingSessionController controller = mock(RecordingSessionController.class);
        when(controller.getSnapshot()).thenReturn(snapshot(CONFIGURED, null));
        RecordingArtifactStore store = mock(RecordingArtifactStore.class);
        CommandCallback cb = mock(CommandCallback.class);
        when(cb.getSelectedModel()).thenReturn("claude");
        when(cb.resolveAiService("claude")).thenThrow(new IllegalStateException("boom"));

        new DefaultRecordingWorkflow(controller, store).run("Buy a book", cb);

        verify(cb).processAiResponse("Recording failed unexpectedly: boom");
        verify(controller).resetToOff();
        verify(cb).setInputEnabled(true);
    }

    @Test
    void should_useSnapshotArtifactDirectory_when_present() {
        RecordingArtifactStore store = mock(RecordingArtifactStore.class);
        when(store.getRootDirectory()).thenReturn(Paths.get("/store/root"));
        DefaultRecordingWorkflow workflow =
                new DefaultRecordingWorkflow(mock(RecordingSessionController.class), store);

        assertEquals(Paths.get("/snap/dir"), workflow.resolveArtifactDir(snapshot(CONFIGURED, "/snap/dir")));
        assertEquals(Paths.get("/store/root"), workflow.resolveArtifactDir(snapshot(CONFIGURED, "")));
        assertEquals(Paths.get("/store/root"), workflow.resolveArtifactDir(snapshot(CONFIGURED, null)));
    }
}
