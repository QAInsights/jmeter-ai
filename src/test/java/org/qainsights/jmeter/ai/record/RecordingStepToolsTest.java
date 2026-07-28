package org.qainsights.jmeter.ai.record;

import java.util.List;
import java.util.Map;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.protocol.http.proxy.ProxyControl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolExecutor;
import org.qainsights.jmeter.ai.agent.tool.ToolRegistry;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link RecordingStepTools}, the agent's view of the recording.
 */
class RecordingStepToolsTest {

    private RecordingSession session;
    private ToolRegistry registry;
    private ToolExecutor executor;

    @BeforeAll
    static void initJMeter() {
        RecordingTestSupport.initJMeterHome();
    }

    @BeforeEach
    void setUp() {
        JMeterTreeModel model = new JMeterTreeModel();
        RecordingScaffold scaffold = RecordingScaffold.createIn(model, "https://shop.test");
        JMeterProxyRecorder recorder = new JMeterProxyRecorder(model, mock(ProxyControl.class));
        session = new RecordingSession(scaffold, recorder, 10, 200);

        registry = new ToolRegistry();
        for (Tool tool : new RecordingStepTools(session).tools()) {
            registry.register(tool);
        }
        executor = new ToolExecutor(registry);
    }

    @Test
    void should_exposeTheThreeControlTools() {
        assertEquals(3, registry.size());
        assertTrue(registry.isRegistered(RecordingStepTools.BEGIN_STEP));
        assertTrue(registry.isRegistered(RecordingStepTools.END_STEP));
        assertTrue(registry.isRegistered(RecordingStepTools.FINISH));
    }

    @Test
    void should_requireAStepName() {
        // Enforced by the spec so the model gets a schema error, not a runtime one.
        ToolResult result = executor.execute(RecordingStepTools.BEGIN_STEP, Map.of());

        assertFalse(result.isSuccess());
        assertEquals(ToolExecutor.ERR_MISSING_PARAMETER, result.getErrorCode());
    }

    @Test
    void should_openStep_when_begun() {
        ToolResult result = executor.execute(RecordingStepTools.BEGIN_STEP,
                Map.of("name", "Add To Cart"));

        assertTrue(result.isSuccess());
        assertTrue(result.getData().contains("Add To Cart"));
        assertEquals("Add To Cart", session.currentStepName());
    }

    @Test
    void should_closeStep_when_ended() {
        executor.execute(RecordingStepTools.BEGIN_STEP, Map.of("name", "Search"));

        ToolResult result = executor.execute(RecordingStepTools.END_STEP, Map.of());

        assertTrue(result.isSuccess());
        assertNull(session.currentStepName());
    }

    @Test
    void should_finishWithoutSummary_when_noneGiven() {
        // summary is optional; the model often omits it.
        ToolResult result = executor.execute(RecordingStepTools.FINISH, Map.of());

        assertTrue(result.isSuccess());
        assertTrue(session.isFinished());
        assertEquals("", session.summary());
    }

    @Test
    void should_reportStepsAndCount_when_finishing() {
        executor.execute(RecordingStepTools.BEGIN_STEP, Map.of("name", "Open Home Page"));
        executor.execute(RecordingStepTools.END_STEP, Map.of());
        executor.execute(RecordingStepTools.BEGIN_STEP, Map.of("name", "Checkout"));

        ToolResult result = executor.execute(RecordingStepTools.FINISH,
                Map.of("summary", "Browsed and checked out."));

        assertTrue(result.isSuccess());
        assertTrue(result.getData().contains("Open Home Page"));
        assertTrue(result.getData().contains("Checkout"));
        assertEquals("Browsed and checked out.", session.summary());
    }

    @Test
    void should_instructTheModelToStop_when_finished() {
        // AgentLoop has no early-stop signal, so the tool result is what ends the run.
        ToolResult finish = executor.execute(RecordingStepTools.FINISH, Map.of());
        assertTrue(finish.getData().contains("Do not call any more tools"));

        ToolResult afterwards = executor.execute(RecordingStepTools.BEGIN_STEP,
                Map.of("name", "Too Late"));

        assertFalse(afterwards.isSuccess());
        assertEquals(RecordingStepTools.ERR_ALREADY_FINISHED, afterwards.getErrorCode());
        assertTrue(afterwards.getMessage().contains("Do not call any more tools"));
    }

    @Test
    void should_returnReadableError_when_stepNameIsBlank() {
        ToolResult result = executor.execute(RecordingStepTools.BEGIN_STEP, Map.of("name", "   "));

        assertFalse(result.isSuccess());
        assertEquals(RecordingStepTools.ERR_STEP_FAILED, result.getErrorCode(),
                "a bad argument must not surface as a generic tool exception");
        assertTrue(result.getMessage().contains("needs a name"));
    }

    @Test
    void should_describeStepsInBusinessLanguage() {
        // The step description is the only guidance the model gets on naming, and those
        // names become the Transaction Controllers a performance engineer reads.
        String description = registry.get(RecordingStepTools.BEGIN_STEP)
                .getSpec().getDescription();

        assertTrue(description.contains("BEFORE"), "ordering is the most common mistake");
        assertTrue(description.contains("Add To Cart"), "a concrete example beats an abstract rule");
    }

    @Test
    void should_rejectNullSession() {
        assertThrows(IllegalArgumentException.class, () -> new RecordingStepTools(null));
    }

    @Test
    void should_notCollideWithBrowserToolNames() {
        List<String> names = List.of(RecordingStepTools.BEGIN_STEP,
                RecordingStepTools.END_STEP, RecordingStepTools.FINISH);

        for (String name : names) {
            assertFalse(name.startsWith("browser_"),
                    "control tools must not shadow a Playwright MCP tool");
        }
    }
}
