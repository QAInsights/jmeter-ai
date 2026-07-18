package org.qainsights.jmeter.ai.record;

/**
 * Routes user prompts during an active recording session.
 * Intercepts user input when the recording session is ARMED and starts the workflow.
 */
public final class RecordingPromptRouter {

    private final RecordingSessionController controller;
    private final RecordingWorkflowRunnable workflowRunnable;

    public RecordingPromptRouter(RecordingSessionController controller, RecordingWorkflowRunnable workflowRunnable) {
        this.controller = controller;
        this.workflowRunnable = workflowRunnable;
    }

    public boolean route(String message, org.qainsights.jmeter.ai.gui.CommandCallback cb) {
        if (controller.getSnapshot().state() != RecordingSessionState.ARMED) {
            return false;
        }
        cb.processAiResponse("Acknowledged. Initializing preflight checks and browser planner...");
        controller.transitionTo(RecordingSessionState.PREFLIGHT);
        
        // Start workflow on a separate thread
        new Thread(() -> workflowRunnable.run(message, cb)).start();
        return true;
    }
}
