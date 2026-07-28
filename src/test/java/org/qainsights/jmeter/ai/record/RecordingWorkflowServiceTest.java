package org.qainsights.jmeter.ai.record;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.AgentChatModelFactory;
import org.qainsights.jmeter.ai.agent.loop.AssistantTurn;
import org.qainsights.jmeter.ai.agent.loop.ChatModel;
import org.qainsights.jmeter.ai.agent.loop.ToolOutcome;
import org.qainsights.jmeter.ai.agent.tool.ParamType;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolParameter;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.agent.tool.ToolSpec;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Orchestration tests for {@link RecordingWorkflowService}.
 * <p>
 * The browser and the model are both stubbed, so these run with no Node, no network and
 * no LLM. What they verify is the wiring: which tools the model is offered, the order of
 * startup and teardown, and that nothing leaks when a step throws.
 */
class RecordingWorkflowServiceTest {

    private JMeterTreeModel model;
    private RecordingSessionController controller;
    private Path artifactDir;
    private FakeBrowserSession browser;

    @BeforeAll
    static void initJMeter() {
        RecordingTestSupport.initJMeterHome();
    }

    @BeforeEach
    void setUp() {
        model = new JMeterTreeModel();
        controller = new RecordingSessionController();
        controller.startSession(new SessionConfig("Buy a hat", "https://shop.test", "chromium"),
                "artifacts");
        artifactDir = RecordingTestSupport.artifactDir("workflow-" + System.nanoTime());
        browser = new FakeBrowserSession();
    }

    // --- Test doubles -----------------------------------------------------------------

    /** A browser whose single tool records that it was called. */
    private static final class FakeBrowserSession implements BrowserSession {
        private final List<String> calls = new ArrayList<>();
        private boolean closed;
        private RuntimeException failOnOpen;
        private boolean hugeResult;

        /** Stands in for a Playwright accessibility-tree snapshot of a content-heavy page. */
        private String payload() {
            if (!hugeResult) {
                return "Navigated.";
            }
            StringBuilder sb = new StringBuilder(400_000);
            while (sb.length() < 400_000) {
                sb.append("- generic [ref=e").append(sb.length()).append("]: page content\n");
            }
            return sb.toString();
        }

        @Override
        public List<Tool> tools() {
            ToolSpec spec = ToolSpec.builder("browser_navigate")
                    .description("Go to a URL")
                    .addParameter(ToolParameter.builder("url", ParamType.STRING)
                            .required(true).build())
                    .build();
            return List.of(new Tool() {
                @Override
                public ToolSpec getSpec() {
                    return spec;
                }

                @Override
                public ToolResult execute(Map<String, Object> arguments) {
                    calls.add("browser_navigate:" + arguments.get("url"));
                    return ToolResult.ok(payload());
                }
            });
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** Replays a fixed script of tool calls, then a closing message. */
    private static final class ScriptedModel implements ChatModel {
        private final List<Map<String, Object>> script;
        private final List<ToolSpec> offeredSpecs;
        private final String systemPrompt;
        private final List<String> receivedToolContent = new ArrayList<>();
        private int index;

        ScriptedModel(List<Map<String, Object>> script, List<ToolSpec> specs, String systemPrompt) {
            this.script = script;
            this.offeredSpecs = specs;
            this.systemPrompt = systemPrompt;
        }

        @Override
        public AssistantTurn start(String userMessage) {
            return nextTurn();
        }

        @Override
        public AssistantTurn next(List<ToolOutcome> toolOutcomes) {
            // What lands here is exactly what would be sent to the provider.
            for (ToolOutcome outcome : toolOutcomes) {
                receivedToolContent.add(outcome.getContent());
            }
            return nextTurn();
        }

        private AssistantTurn nextTurn() {
            if (index >= script.size()) {
                return new AssistantTurn("Recording complete.", List.of());
            }
            Map<String, Object> step = script.get(index++);
            String name = String.valueOf(step.get("tool"));
            @SuppressWarnings("unchecked")
            Map<String, Object> args = (Map<String, Object>) step.get("args");
            return new AssistantTurn("",
                    List.of(new AssistantTurn.ToolCall("call-" + index, name, args)));
        }
    }

    private static Map<String, Object> call(String tool, Map<String, Object> args) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("tool", tool);
        step.put("args", args);
        return step;
    }

    private ScriptedModel lastModel;

    private AgentChatModelFactory factoryFor(List<Map<String, Object>> script) {
        return (specs, systemPrompt, seed) -> {
            lastModel = new ScriptedModel(script, specs, systemPrompt);
            return lastModel;
        };
    }

    private RecordingWorkflowService serviceFor(List<Map<String, Object>> script) {
        return new RecordingWorkflowService(model, factoryFor(script),
                (port, dir, origins) -> {
                    if (browser.failOnOpen != null) {
                        throw browser.failOnOpen;
                    }
                    return browser;
                }, controller);
    }

    // --- Tests ------------------------------------------------------------------------

    @Test
    void should_recordAJourneyIntoTheTree() {
        RecordingWorkflowService service = serviceFor(List.of(
                call(RecordingStepTools.BEGIN_STEP, Map.of("name", "Open Home Page")),
                call("browser_navigate", Map.of("url", "https://shop.test")),
                call(RecordingStepTools.END_STEP, Map.of()),
                call(RecordingStepTools.FINISH, Map.of("summary", "Opened the home page."))));

        RecordingWorkflowService.RecordingOutcome outcome =
                service.record(new SessionConfig("Open the home page", "https://shop.test", "chromium"),
                        artifactDir, null);

        assertTrue(outcome.completed());
        assertEquals(List.of("Open Home Page"), outcome.stepNames());
        assertEquals(List.of("browser_navigate:https://shop.test"), browser.calls);
    }

    @Test
    void should_offerBrowserAndControlToolsTogether() {
        serviceFor(List.of(call(RecordingStepTools.FINISH, Map.of())))
                .record(new SessionConfig("Do nothing", "https://shop.test", "chromium"),
                        artifactDir, null);

        List<String> offered = lastModel.offeredSpecs.stream().map(ToolSpec::getName).toList();

        assertTrue(offered.contains("browser_navigate"), "the agent must be able to drive");
        assertTrue(offered.contains(RecordingStepTools.BEGIN_STEP),
                "the agent must be able to structure the plan");
        assertTrue(offered.contains(RecordingStepTools.FINISH));
    }

    @Test
    void should_groundThePromptInTheTargetSite() {
        serviceFor(List.of(call(RecordingStepTools.FINISH, Map.of())))
                .record(new SessionConfig("Do nothing", "https://shop.test", "chromium"),
                        artifactDir, null);

        assertTrue(lastModel.systemPrompt.contains("https://shop.test"));
        assertTrue(lastModel.systemPrompt.contains("browser_snapshot"),
                "the observe-act loop is the core instruction");
    }

    @Test
    void should_buildTheScaffoldInTheTree() {
        serviceFor(List.of(call(RecordingStepTools.BEGIN_STEP, Map.of("name", "Search")),
                call(RecordingStepTools.FINISH, Map.of())))
                .record(new SessionConfig("Search", "https://shop.test", "chromium"),
                        artifactDir, null);

        assertFalse(model.getNodesOfType(org.apache.jmeter.control.TransactionController.class).isEmpty(),
                "each business step becomes a Transaction Controller in the plan");
    }

    @Test
    void should_finalizeThePlanIntoTheThreadGroup() {
        // The user's requirement: the recorded samplers end up in the new thread group, not
        // buried in the Recording Controller used to capture them.
        List<String> progress = new ArrayList<>();

        serviceFor(List.of(call(RecordingStepTools.BEGIN_STEP, Map.of("name", "Search")),
                call(RecordingStepTools.FINISH, Map.of())))
                .record(new SessionConfig("Search", "https://shop.test", "chromium"),
                        artifactDir, progress::add);

        assertTrue(model.getNodesOfType(
                        org.apache.jmeter.protocol.http.control.RecordingController.class).isEmpty(),
                "the Recording Controller must be dissolved once the recording is done");
        org.apache.jmeter.gui.tree.JMeterTreeNode step = model.getNodesOfType(
                org.apache.jmeter.control.TransactionController.class).get(0);
        assertTrue(((org.apache.jmeter.gui.tree.JMeterTreeNode) step.getParent())
                        .getTestElement() instanceof org.apache.jmeter.threads.ThreadGroup,
                "the step must hang directly off the thread group");
        assertTrue(progress.stream().anyMatch(p -> p.contains("thread group")),
                "the user needs to be told the plan was reorganised");
    }

    @Test
    void should_closeTheBrowser_when_runCompletes() {
        serviceFor(List.of(call(RecordingStepTools.FINISH, Map.of())))
                .record(new SessionConfig("Do nothing", "https://shop.test", "chromium"),
                        artifactDir, null);

        assertTrue(browser.closed, "a leaked browser would strand a window and a port");
    }

    @Test
    void should_reachAwaitingCorrelation_when_successful() {
        serviceFor(List.of(call(RecordingStepTools.FINISH, Map.of())))
                .record(new SessionConfig("Do nothing", "https://shop.test", "chromium"),
                        artifactDir, null);

        assertEquals(RecordingSessionState.AWAITING_CORRELATION, controller.getSnapshot().state());
    }

    @Test
    void should_markIncomplete_when_agentNeverFinished() {
        // The model stopped talking without calling finish_recording: whatever was captured
        // is still in the tree, but the journey cannot be claimed as complete.
        RecordingWorkflowService.RecordingOutcome outcome =
                serviceFor(List.of()).record(
                        new SessionConfig("Do nothing", "https://shop.test", "chromium"),
                        artifactDir, null);

        assertFalse(outcome.completed());
    }

    @Test
    void should_failSessionAndCloseBrowser_when_agentThrows() {
        RecordingWorkflowService service = new RecordingWorkflowService(model,
                (specs, prompt, seed) -> {
                    throw new IllegalStateException("model exploded");
                },
                (port, dir, origins) -> browser, controller);

        assertThrows(RecordingException.class, () -> service.record(
                new SessionConfig("Do nothing", "https://shop.test", "chromium"),
                artifactDir, null));

        assertEquals(RecordingSessionState.FAILED, controller.getSnapshot().state());
        assertTrue(browser.closed, "teardown must run even on failure");
    }

    @Test
    void should_failSession_when_browserCannotStart() {
        browser.failOnOpen = new RecordingException("Node.js is not installed");

        RecordingException e = assertThrows(RecordingException.class,
                () -> serviceFor(List.of()).record(
                        new SessionConfig("Do nothing", "https://shop.test", "chromium"),
                        artifactDir, null));

        assertTrue(e.getMessage().contains("Node.js"), "the cause must reach the user");
        assertEquals(RecordingSessionState.FAILED, controller.getSnapshot().state());
    }

    @Test
    void should_reportProgress() {
        List<String> progress = new ArrayList<>();

        serviceFor(List.of(call(RecordingStepTools.FINISH, Map.of())))
                .record(new SessionConfig("Do nothing", "https://shop.test", "chromium"),
                        artifactDir, progress::add);

        assertTrue(progress.stream().anyMatch(p -> p.contains("recording proxy")));
        assertTrue(progress.stream().anyMatch(p -> p.contains("browser")));
    }

    @Test
    void should_rejectMissingCollaborators() {
        assertThrows(IllegalArgumentException.class, () -> new RecordingWorkflowService(
                null, (s, p, t) -> null, (port, dir, o) -> browser, controller));
        assertThrows(IllegalArgumentException.class, () -> new RecordingWorkflowService(
                model, null, (port, dir, o) -> browser, controller));
        assertThrows(IllegalArgumentException.class, () -> new RecordingWorkflowService(
                model, (s, p, t) -> null, null, controller));
        assertThrows(IllegalArgumentException.class, () -> new RecordingWorkflowService(
                model, (s, p, t) -> null, (port, dir, o) -> browser, null));
    }

    @Test
    void should_boundBrowserToolOutput_soContextCannotBeExhausted() {
        // Regression: an unbounded page snapshot produced
        // "prompt is too long: 3653143 tokens > 1000000 maximum" and killed the recording.
        browser.hugeResult = true;

        serviceFor(List.of(
                call("browser_navigate", Map.of("url", "https://shop.test")),
                call(RecordingStepTools.FINISH, Map.of())))
                .record(new SessionConfig("Open the page", "https://shop.test", "chromium"),
                        artifactDir, null);

        String sentToModel = lastModel.receivedToolContent.get(0);
        assertTrue(sentToModel.length() < 20_000,
                "a 400KB snapshot reached the model unbounded: " + sentToModel.length());
        assertTrue(sentToModel.contains("partial view"),
                "the model must be told the page view was cut short");
    }

    @Test
    void should_scopeCaptureToTheTargetHost() {
        List<String> progress = new ArrayList<>();

        serviceFor(List.of(call(RecordingStepTools.FINISH, Map.of())))
                .record(new SessionConfig("Do nothing",
                                "https://petstore.octoperf.com/actions/Account.action?signonForm=",
                                "chromium"),
                        artifactDir, progress::add);

        // Reported rather than silent, because a cross-host SSO redirect would be dropped.
        assertTrue(progress.stream().anyMatch(p -> p.contains("petstore.octoperf.com")),
                "the user must be told which host is being recorded: " + progress);
    }

    @Test
    void should_rejectMissingConfig() {
        assertThrows(RecordingException.class,
                () -> serviceFor(List.of()).record(null, artifactDir, null));
    }
}
