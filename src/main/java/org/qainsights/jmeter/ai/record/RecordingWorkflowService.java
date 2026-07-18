package org.qainsights.jmeter.ai.record;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import org.apache.jmeter.gui.GuiPackage;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.handlers.FindCorrelationCandidatesHandler;
import org.qainsights.jmeter.ai.gui.CommandCallback;
import org.qainsights.jmeter.ai.service.AiService;
import org.qainsights.jmeter.ai.service.ClaudeService;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.qainsights.jmeter.ai.utils.PlanReplacementGuard;

/**
 * Service to execute the end-to-end recording workflow.
 */
public final class RecordingWorkflowService implements RecordingWorkflowRunnable {

    private final RecordingSessionController controller;
    private final BrowserFlowPlanner planner;
    private final java.util.function.Supplier<PlaywrightBrowserSession> sessionSupplier;

    public RecordingWorkflowService(RecordingSessionController controller, BrowserFlowPlanner planner) {
        this(controller, planner, PlaywrightBrowserSession::new);
    }

    public RecordingWorkflowService(RecordingSessionController controller, BrowserFlowPlanner planner,
                                    java.util.function.Supplier<PlaywrightBrowserSession> sessionSupplier) {
        this.controller = controller;
        this.planner = planner;
        this.sessionSupplier = sessionSupplier;
    }

    @Override
    public void run(String prompt, CommandCallback cb) {
        try {
            executeWorkflow(prompt, cb);
        } catch (Exception e) {
            handleWorkflowFailure(e, cb);
        }
    }

    private void executeWorkflow(String prompt, CommandCallback cb) throws Exception {
        ClaudeService claude = resolveClaudeService(cb);
        SessionConfig config = controller.getSnapshot().config();
        cb.processAiResponse("Preflight checks passed. Initializing browser planner...");

        controller.transitionTo(RecordingSessionState.PLANNING);
        BrowserFlowPlan plan = planner.plan(prompt, config);
        cb.processAiResponse("Plan generated:\n" + formatPlan(plan));

        controller.transitionTo(RecordingSessionState.EXECUTING);
        executeBrowserSession(plan, cb);
    }

    private ClaudeService resolveClaudeService(CommandCallback cb) {
        AiService activeService = cb.resolveAiService(cb.getSelectedModel());
        if (!(activeService instanceof ClaudeService)) {
            throw new RecordingException("Record Mode requires a Claude model.");
        }
        return (ClaudeService) activeService;
    }

    private String formatPlan(BrowserFlowPlan plan) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < plan.steps().size(); i++) {
            BrowserStep step = plan.steps().get(i);
            sb.append(i + 1).append(". ").append(step.action());
            String target = (step.role() != null && !step.role().isEmpty() ? step.role() + " " : "") + step.text();
            if (!target.trim().isEmpty()) {
                sb.append(" -> ").append(target);
            }
            if (!step.value().isEmpty()) sb.append(" (").append(step.value()).append(")");
            sb.append("\n");
        }
        return sb.toString();
    }

    private void executeBrowserSession(BrowserFlowPlan plan, CommandCallback cb) throws Exception {
        File sessionDir = new File(controller.getSnapshot().artifactDirectory());
        String harPath = new File(sessionDir, "recording.har").getAbsolutePath();
        String jmxPath = new File(sessionDir, "recording.jmx").getAbsolutePath();
        String recordXmlPath = new File(sessionDir, "recording.xml").getAbsolutePath();
        String stepMarkersPath = new File(sessionDir, "step-markers.json").getAbsolutePath();

        List<StepMarker> markers = new ArrayList<>();
        boolean success = runStepsInBrowser(plan, harPath, markers, cb);

        controller.transitionTo(RecordingSessionState.FLUSHING_HAR);
        if (!success) {
            handleStepFailure(harPath, jmxPath, recordXmlPath, stepMarkersPath, markers, cb);
            return;
        }

        performConversionAndLoad(harPath, jmxPath, recordXmlPath, stepMarkersPath, markers, cb);
    }

    private boolean runStepsInBrowser(BrowserFlowPlan plan, String harPath, List<StepMarker> markers, CommandCallback cb) {
        SessionConfig config = controller.getSnapshot().config();
        try (PlaywrightBrowserSession session = sessionSupplier.get()) {
            session.start(config, harPath);
            for (BrowserStep step : plan.steps()) {
                String targetDesc;
                if ("navigate".equals(step.action()) || "wait".equals(step.action())) {
                    targetDesc = step.value();
                } else {
                    targetDesc = (step.role() != null && !step.role().isEmpty() ? step.role() + " " : "") + step.text();
                }
                String desc = step.action() + " " + targetDesc;
                cb.processAiResponse("Executing browser action: " + step.action() + " -> " + targetDesc);
                markers.add(new StepMarker(desc, "start", System.currentTimeMillis()));
                StepExecutionResult result = session.executeStep(step);
                markers.add(new StepMarker(desc, "end", System.currentTimeMillis()));

                if (!result.success()) {
                    cb.processAiResponse("Failed: " + result.error() + ". Screenshot: " + result.screenshotPath());
                    return false;
                }
            }
            return true;
        }
    }

    private void handleStepFailure(String har, String jmx, String rx, String sm, List<StepMarker> m, CommandCallback cb) throws Exception {
        controller.transitionTo(RecordingSessionState.FAILED, "Step execution failed");
        if (hasHttpEntries(har)) {
            int option = showConfirmDialog();
            if (option == JOptionPane.YES_OPTION) {
                controller.transitionTo(RecordingSessionState.CONVERT_PARTIAL);
                performConversionAndLoad(har, jmx, rx, sm, m, cb);
                return;
            }
        }
        controller.transitionTo(RecordingSessionState.OFF);
        cb.processAiResponse("Recording aborted by user or failed execution.");
    }

    private int showConfirmDialog() throws Exception {
        final int[] result = new int[1];
        SwingUtilities.invokeAndWait(() -> {
            result[0] = JOptionPane.showOptionDialog(
                GuiPackage.getInstance().getMainFrame(),
                "A step failed, but traffic was captured. Convert captured traffic?",
                "Step Failed",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                new String[]{"Convert Captured Traffic", "Abort"},
                "Convert Captured Traffic"
            );
        });
        return result[0];
    }

    private boolean hasHttpEntries(String harPath) {
        File file = new File(harPath);
        return file.exists() && file.length() > 100;
    }

    private void performConversionAndLoad(String har, String jmx, String rx, String sm, List<StepMarker> m, CommandCallback cb) throws Exception {
        controller.transitionTo(RecordingSessionState.CONVERTING);
        new ObjectMapper().writeValue(new File(sm), m);

        HarToJmxConverter converter = new VdaburonHarToJmxConverter();
        SessionConfig config = controller.getSnapshot().config();
        converter.convert(new HarConversionRequest(har, sm, rx, jmx, config.baseUri()));

        new ThinkTimeInjector().injectThinkTimes(new File(jmx), m);

        controller.transitionTo(RecordingSessionState.LOADING);
        new JmxValidator().validate(new File(jmx));
        
        loadJmxOnEdt(jmx);

        controller.transitionTo(RecordingSessionState.AWAITING_CORRELATION);
        cb.processAiResponse("Recording successfully converted and loaded into JMeter.");
        
        suggestCorrelations(cb);
        controller.transitionTo(RecordingSessionState.DONE);
    }

    private void loadJmxOnEdt(String jmxPath) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                PlanReplacementGuard.live().loadPlan(new File(jmxPath));
            } catch (Exception e) {
                throw new RuntimeException("Failed to load JMX", e);
            }
        });
    }

    private void suggestCorrelations(CommandCallback cb) {
        try {
            FindCorrelationCandidatesHandler handler = new FindCorrelationCandidatesHandler();
            ToolResult res = handler.tool().execute(new java.util.HashMap<>());
            if (res.isSuccess()) {
                cb.processAiResponse("Auto-detected Correlation Candidates:\n" + res.getData());
            }
        } catch (Exception e) {
            cb.processAiResponse("Could not auto-detect correlation candidates: " + e.getMessage());
        }
    }

    private void handleWorkflowFailure(Exception e, CommandCallback cb) {
        controller.transitionTo(RecordingSessionState.FAILED, e.getMessage());
        cb.processAiResponse("Recording workflow failed: " + e.getMessage());
        controller.transitionTo(RecordingSessionState.OFF);
    }
}
