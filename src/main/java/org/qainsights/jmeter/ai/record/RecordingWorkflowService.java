package org.qainsights.jmeter.ai.record;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.qainsights.jmeter.ai.agent.AgentChatModelFactory;
import org.qainsights.jmeter.ai.agent.loop.AgentLoop;
import org.qainsights.jmeter.ai.agent.loop.ChatModel;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolExecutor;
import org.qainsights.jmeter.ai.agent.tool.ToolRegistry;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one recording end to end: build the plan skeleton, start the proxy, open the
 * browser, let the agent drive, then tear everything down.
 * <p>
 * The division of labour is the whole point of the design. The agent decides <em>where to
 * go</em> and <em>what to call each step</em>; JMeter's {@code ProxyControl} decides
 * <em>what gets recorded</em>. Because no part of the test plan's content originates with
 * the model, the output cannot contain a hallucinated request - the previous
 * implementation's central failure.
 * <p>
 * Teardown is unconditional and ordered: the browser is closed before the proxy, so its
 * last requests are captured, and the proxy is stopped even if the agent threw. Leaking
 * either would leave an orphaned browser and a bound port.
 */
public final class RecordingWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(RecordingWorkflowService.class);

    private static final int DEFAULT_MAX_ITERATIONS = 60;

    /** Outcome of a recording run. */
    public record RecordingOutcome(
            boolean completed,
            String finalText,
            int sampleCount,
            List<String> stepNames) {

        public RecordingOutcome {
            finalText = finalText == null ? "" : finalText;
            stepNames = stepNames == null ? List.of() : List.copyOf(stepNames);
        }
    }

    private final JMeterTreeModel treeModel;
    private final AgentChatModelFactory chatModelFactory;
    private final BrowserSession.Factory browserSessionFactory;
    private final RecordingSessionController controller;
    private final int maxIterations;

    public RecordingWorkflowService(JMeterTreeModel treeModel,
                                    AgentChatModelFactory chatModelFactory,
                                    BrowserSession.Factory browserSessionFactory,
                                    RecordingSessionController controller) {
        this(treeModel, chatModelFactory, browserSessionFactory, controller, DEFAULT_MAX_ITERATIONS);
    }

    public RecordingWorkflowService(JMeterTreeModel treeModel,
                                    AgentChatModelFactory chatModelFactory,
                                    BrowserSession.Factory browserSessionFactory,
                                    RecordingSessionController controller,
                                    int maxIterations) {
        if (treeModel == null || chatModelFactory == null
                || browserSessionFactory == null || controller == null) {
            throw new IllegalArgumentException("RecordingWorkflowService needs all collaborators");
        }
        this.treeModel = treeModel;
        this.chatModelFactory = chatModelFactory;
        this.browserSessionFactory = browserSessionFactory;
        this.controller = controller;
        this.maxIterations = maxIterations < 1 ? 1 : maxIterations;
    }

    /**
     * Records the journey described by {@code config.prompt()}.
     *
     * @param config      the session settings, including the prompt and base URI
     * @param artifactDir a writable directory for the session's files
     * @param progress    receives human-readable progress lines; may be null
     * @return the outcome
     * @throws RecordingException if the recording could not be started or completed
     */
    public RecordingOutcome record(SessionConfig config, Path artifactDir, Consumer<String> progress) {
        if (config == null) {
            throw new RecordingException("Cannot record without a session configuration");
        }
        Consumer<String> sink = progress == null ? s -> { } : progress;

        try {
            controller.transitionTo(RecordingSessionState.PREFLIGHT);
            preflight(sink);

            controller.transitionTo(RecordingSessionState.PLANNING);
            return runRecording(config, artifactDir, sink);
        } catch (RecordingException e) {
            failSession(e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            log.error("Recording failed unexpectedly", e);
            failSession(e.getMessage());
            throw new RecordingException("The recording failed: " + e.getMessage(), e);
        }
    }

    private void preflight(Consumer<String> progress) {
        progress.accept("Checking the recorder prerequisites\u2026");
        JMeterProxyRecorder.checkPrerequisites();
    }

    private RecordingOutcome runRecording(SessionConfig config, Path artifactDir,
                                          Consumer<String> progress) {
        RecordingScaffold scaffold = RecordingScaffold.createIn(treeModel, config.baseUri());
        JMeterProxyRecorder recorder = new JMeterProxyRecorder(treeModel);
        int port = JMeterProxyRecorder.findFreePort();

        // Traffic before the first business step lands directly under the Recording
        // Controller rather than being lost.
        progress.accept("Starting the recording proxy on port " + port + "\u2026");
        List<String> includes = includePatterns(config, progress);
        recorder.start(scaffold.recordingControllerNode(), artifactDir.resolve("recording.jtl"),
                port, includes);

        RecordingSession session = new RecordingSession(scaffold, recorder);
        BrowserSession browser = null;
        try {
            progress.accept("Opening the browser\u2026");
            browser = browserSessionFactory.open(port, artifactDir, allowedOrigins(config));

            controller.transitionTo(RecordingSessionState.EXECUTING);
            AgentLoop.AgentResult result = drive(config, session, browser, progress);

            // The browser must stop before the proxy so its final requests are captured.
            browser.close();
            browser = null;

            recorder.stop();

            // Only safe once the proxy is stopped: its target points inside this subtree.
            int promoted = RecordedPlanFinalizer.promoteRecordedSteps(treeModel, scaffold);
            if (promoted > 0) {
                progress.accept("Moved the recorded steps into the "
                        + scaffold.threadGroupNode().getName() + " thread group.");
            }

            controller.transitionTo(RecordingSessionState.AWAITING_CORRELATION);

            // The count must come from the plan, not from the sample listener: ProxyControl
            // notifies listeners for requests it filters out, so the listener count once
            // reported 1,557 "captured" requests while every step was empty.
            int captured = RecordedPlanFinalizer.countSamplers(scaffold.threadGroupNode());
            int observed = recorder.sampleCount();
            if (captured == 0 && observed > 0) {
                progress.accept("Warning: " + observed + " requests went through the proxy but "
                        + "none were captured, which means every one was rejected by the "
                        + "recording scope. Check that the target site's hostname is correct.");
            }

            RecordingOutcome outcome = new RecordingOutcome(
                    result.isCompleted() && session.isFinished(),
                    result.getFinalText(),
                    captured,
                    session.stepNames());
            progress.accept("Recorded " + outcome.sampleCount() + " requests across "
                    + outcome.stepNames().size() + " steps.");
            return outcome;
        } finally {
            closeQuietly(browser);
            recorder.stop();
        }
    }

    /**
     * Restricts capture to the target site's host.
     * <p>
     * Without this, the recorder captures every host the browser touches - CDNs, analytics,
     * font providers - and the resulting plan is mostly third-party noise.
     * <p>
     * The pattern is reported through {@code progress} because the failure mode is silent:
     * if a journey legitimately spans hosts, such as an SSO redirect to an identity
     * provider, those requests are dropped and the user needs to see why.
     */
    private static List<String> includePatterns(SessionConfig config, Consumer<String> progress) {
        String host = RecordingFilters.hostOf(config.baseUri());
        if (host == null) {
            progress.accept("Recording every host: no hostname could be read from "
                    + config.baseUri() + ".");
            return Collections.emptyList();
        }
        progress.accept("Recording only requests to " + host
                + " (other hosts, including any external login provider, are skipped).");
        return List.of(RecordingFilters.includeForHost(host));
    }

    private AgentLoop.AgentResult drive(SessionConfig config, RecordingSession session,
                                        BrowserSession browser, Consumer<String> progress) {
        ToolRegistry registry = new ToolRegistry();
        // Page snapshots are huge and every turn is re-sent on the next request, so an
        // unbounded result exhausts the context window mid-recording.
        int budget = maxToolOutputChars();
        for (Tool tool : browser.tools()) {
            registry.register(new BoundedBrowserTool(tool, budget));
        }
        for (Tool tool : new RecordingStepTools(session).tools()) {
            registry.register(tool);
        }

        String systemPrompt = RecordingSystemPrompt.build(config.baseUri());
        ChatModel chat = chatModelFactory.create(registry.getSpecs(), systemPrompt,
                Collections.emptyList());
        return new AgentLoop(chat, new ToolExecutor(registry), maxIterations)
                .run(config.prompt(), progress);
    }

    /**
     * The per-result output budget, tunable through
     * {@code jmeter.ai.record.tool.output.max.chars}.
     * <p>
     * Configurable because the right value depends on the site: too low and the agent
     * cannot see the element it needs on an element-heavy page, too high and the context
     * window is exhausted. A bad value falls back to the default rather than failing the
     * recording, which would be a worse outcome than a suboptimal budget.
     */
    private static int maxToolOutputChars() {
        String configured = AiConfig.getProperty("jmeter.ai.record.tool.output.max.chars", "");
        if (configured == null || configured.trim().isEmpty()) {
            return BoundedBrowserTool.DEFAULT_MAX_CHARS;
        }
        try {
            int value = Integer.parseInt(configured.trim());
            return value > 0 ? value : BoundedBrowserTool.DEFAULT_MAX_CHARS;
        } catch (NumberFormatException e) {
            log.warn("Ignoring invalid jmeter.ai.record.tool.output.max.chars '{}'", configured);
            return BoundedBrowserTool.DEFAULT_MAX_CHARS;
        }
    }

    private static List<String> allowedOrigins(SessionConfig config) {
        // The scaffold's base URI is implicitly allowed by the browser; an empty list here
        // means unrestricted, which is the current behaviour until the UI exposes a limit.
        return Collections.emptyList();
    }

    private void failSession(String message) {
        try {
            controller.transitionTo(RecordingSessionState.FAILED, message);
        } catch (IllegalStateException e) {
            log.debug("Could not mark the session failed from its current state", e);
        }
    }

    private static void closeQuietly(BrowserSession browser) {
        if (browser == null) {
            return;
        }
        try {
            browser.close();
        } catch (RuntimeException e) {
            log.warn("The browser did not close cleanly", e);
        }
    }
}
