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

    /**
     * Hands an armed session's first message to the recording workflow.
     * <p>
     * The state machine is deliberately NOT advanced here: {@code RecordingWorkflowService}
     * owns every transition, and moving to PREFLIGHT in both places would make the second
     * attempt an illegal PREFLIGHT-to-PREFLIGHT transition.
     *
     * @return true if the message was consumed by Record Mode
     */
    public boolean route(String message, org.qainsights.jmeter.ai.gui.CommandCallback cb) {
        if (controller.getSnapshot().state() != RecordingSessionState.ARMED) {
            return false;
        }
        cb.processAiResponse("Starting Record Mode. Opening a browser and recording what it does\u2026");

        // Recording takes minutes; the event dispatch thread must not block.
        Thread worker = new Thread(() -> workflowRunnable.run(message, cb), "feather-wand-recording");
        worker.setDaemon(true);
        worker.start();
        return true;
    }
}
