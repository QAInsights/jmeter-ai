package org.qainsights.jmeter.ai.record;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.qainsights.jmeter.ai.agent.AgentChatModelFactory;
import org.qainsights.jmeter.ai.agent.JMeterAgent;
import org.qainsights.jmeter.ai.gui.CommandCallback;
import org.qainsights.jmeter.ai.service.AiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Binds {@link RecordingWorkflowService} to the live JMeter GUI and the chat panel.
 * <p>
 * Everything host-specific lives here - the tree model, the configured AI provider, the
 * artifact directory, and reporting back to the chat - so the workflow service itself
 * stays free of Swing and of the singleton {@link GuiPackage} and can be unit-tested.
 * <p>
 * Runs on a background thread supplied by {@link RecordingPromptRouter}: a recording takes
 * minutes, and blocking the event dispatch thread would freeze JMeter.
 */
public final class DefaultRecordingWorkflow implements RecordingWorkflowRunnable {

    private static final Logger log = LoggerFactory.getLogger(DefaultRecordingWorkflow.class);

    private final RecordingSessionController controller;
    private final RecordingArtifactStore artifactStore;

    public DefaultRecordingWorkflow(RecordingSessionController controller,
                                    RecordingArtifactStore artifactStore) {
        this.controller = controller;
        this.artifactStore = artifactStore;
    }

    @Override
    public void run(String prompt, CommandCallback cb) {
        RecordingSessionSnapshot snapshot = controller.getSnapshot();
        try {
            SessionConfig config = configFor(snapshot, prompt);
            AgentChatModelFactory chatModelFactory = resolveChatModelFactory(cb);
            JMeterTreeModel treeModel = resolveTreeModel();
            Path artifactDir = resolveArtifactDir(snapshot);

            RecordingWorkflowService service = new RecordingWorkflowService(
                    treeModel, chatModelFactory, PlaywrightMcpSession.factory(), controller);

            RecordingWorkflowService.RecordingOutcome outcome =
                    service.record(config, artifactDir, line -> cb.processAiResponse(line));

            cb.processAiResponse(report(outcome));
        } catch (RecordingException e) {
            log.warn("Recording failed", e);
            cb.processAiResponse("Recording failed: " + e.getMessage());
        } catch (RuntimeException e) {
            log.error("Recording failed unexpectedly", e);
            cb.processAiResponse("Recording failed unexpectedly: " + e.getMessage());
        } finally {
            // Correlation is not wired yet, so nothing else will move the session on from
            // AWAITING_CORRELATION or FAILED. Reset it here or the Record toggle stays stuck.
            controller.resetToOff();
            cb.setInputEnabled(true);
        }
    }

    /**
     * The chat message is the recording instruction, because the user types it after
     * arming. The dialog's prompt is the fallback for an empty message.
     */
    private static SessionConfig configFor(RecordingSessionSnapshot snapshot, String prompt) {
        SessionConfig configured = snapshot.config();
        if (configured == null) {
            throw new RecordingException("No recording session is configured. Toggle Record off "
                    + "and on again to set the target site.");
        }
        String effectivePrompt = prompt == null || prompt.trim().isEmpty()
                ? configured.prompt()
                : prompt.trim();
        return new SessionConfig(effectivePrompt, configured.baseUri(), configured.browser());
    }

    private static AgentChatModelFactory resolveChatModelFactory(CommandCallback cb) {
        AiService service = cb.resolveAiService(cb.getSelectedModel());
        AgentChatModelFactory factory = JMeterAgent.chatModelFactoryFor(service);
        if (factory == null) {
            throw new RecordingException("Record Mode needs a tool-calling model. Select a Claude "
                    + "or OpenAI model and try again.");
        }
        return factory;
    }

    private static JMeterTreeModel resolveTreeModel() {
        GuiPackage gui = GuiPackage.getInstance();
        if (gui == null || gui.getTreeModel() == null) {
            throw new RecordingException("Record Mode needs the JMeter GUI; no test plan tree is "
                    + "available in this process.");
        }
        return gui.getTreeModel();
    }

    private Path resolveArtifactDir(RecordingSessionSnapshot snapshot) {
        if (snapshot.artifactDirectory() != null && !snapshot.artifactDirectory().isEmpty()) {
            return Paths.get(snapshot.artifactDirectory());
        }
        return artifactStore.getRootDirectory();
    }

    private static String report(RecordingWorkflowService.RecordingOutcome outcome) {
        StringBuilder message = new StringBuilder();
        if (outcome.completed()) {
            message.append("Recording complete. ");
        } else {
            message.append("Recording stopped before the agent finished the scenario. "
                    + "Whatever was captured is still in your test plan. ");
        }
        message.append("Captured ").append(outcome.sampleCount()).append(" requests");
        if (!outcome.stepNames().isEmpty()) {
            message.append(" across ").append(outcome.stepNames().size()).append(" steps: ")
                    .append(String.join(", ", outcome.stepNames()));
        }
        message.append(".");
        if (!outcome.finalText().isEmpty()) {
            message.append("\n\n").append(outcome.finalText());
        }
        message.append("\n\nReview the recorded samplers, then run Correlation Studio to "
                + "parameterise any dynamic values.");
        return message.toString();
    }
}
