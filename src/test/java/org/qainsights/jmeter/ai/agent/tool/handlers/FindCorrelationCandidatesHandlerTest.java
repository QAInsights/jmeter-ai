package org.qainsights.jmeter.ai.agent.tool.handlers;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.correlation.CorrelationCandidateFinder;
import org.qainsights.jmeter.ai.agent.correlation.CorrelationCandidateStore;
import org.qainsights.jmeter.ai.agent.correlation.CorrelationExecutionException;
import org.qainsights.jmeter.ai.agent.jmeter.TestRunController;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.correlation.CorrelationCandidate;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link FindCorrelationCandidatesHandler} using fake run-controller/finder seams. */
class FindCorrelationCandidatesHandlerTest {

    /** Returns a canned candidate list (or throws). */
    private static final class FakeFinder implements CorrelationCandidateFinder {
        List<CorrelationCandidate> toReturn = Collections.emptyList();
        CorrelationExecutionException toThrow;
        int callCount;

        @Override
        public List<CorrelationCandidate> find() {
            callCount++;
            if (toThrow != null) {
                throw toThrow;
            }
            return toReturn;
        }
    }

    /** Fake run controller reporting a fixed running state; dispatch is unused here. */
    private static final class FakeController implements TestRunController {
        boolean running = false;

        @Override
        public boolean dispatch(String actionName) {
            return true;
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }

    private JMeterTreeNode root;
    private FakeController controller;
    private FakeFinder finder;
    private Tool tool;

    @BeforeEach
    void setUp() {
        ConfigTestElement element = new ConfigTestElement();
        element.setName("Test Plan");
        root = new JMeterTreeNode(element, null);
        controller = new FakeController();
        finder = new FakeFinder();
        tool = new FindCorrelationCandidatesHandler(() -> root, controller, finder).tool();
    }

    @AfterEach
    void tearDown() {
        CorrelationCandidateStore.clearForTests();
    }

    private static CorrelationCandidate candidate(String param, String varName, String source, String... targets) {
        CorrelationCandidate c = new CorrelationCandidate();
        c.setParameterName(param);
        c.setVariableName(varName);
        c.setSourceSamplerName(source);
        c.setSourceLocation("Response Body (JSON)");
        for (String t : targets) {
            c.addTargetSamplerName(t);
        }
        return c;
    }

    @Test
    void spec_declaresName_andNoRequiredParameters() {
        assertEquals(FindCorrelationCandidatesHandler.FIND_CORRELATION_CANDIDATES, tool.getSpec().getName());
        assertTrue(tool.getSpec().getRequiredParameters().isEmpty());
    }

    @Test
    void run_noTestPlan_returnsErrorWithoutDelegating() {
        Tool noPlan = new FindCorrelationCandidatesHandler(() -> null, controller, finder).tool();
        ToolResult r = noPlan.execute(Collections.emptyMap());

        assertFalse(r.isSuccess());
        assertEquals(FindCorrelationCandidatesHandler.ERR_NO_TEST_PLAN, r.getErrorCode());
        assertEquals(0, finder.callCount);
    }

    @Test
    void run_alreadyRunning_returnsErrorWithoutDelegating() {
        controller.running = true;
        ToolResult r = tool.execute(Collections.emptyMap());

        assertFalse(r.isSuccess());
        assertEquals(FindCorrelationCandidatesHandler.ERR_ALREADY_RUNNING, r.getErrorCode());
        assertEquals(0, finder.callCount);
    }

    @Test
    void run_finderThrows_returnsExecutionFailedError() {
        finder.toThrow = new CorrelationExecutionException("no engine available");
        ToolResult r = tool.execute(Collections.emptyMap());

        assertFalse(r.isSuccess());
        assertEquals(FindCorrelationCandidatesHandler.ERR_EXECUTION_FAILED, r.getErrorCode());
        assertTrue(r.getMessage().contains("no engine available"));
    }

    @Test
    void run_noCandidatesFound_reportsNoneAndStoresEmpty() {
        ToolResult r = tool.execute(Collections.emptyMap());

        assertTrue(r.isSuccess());
        assertTrue(r.getData().contains("No correlation candidates found"));
        assertTrue(CorrelationCandidateStore.get().isEmpty());
    }

    @Test
    void run_candidatesFound_reportsThemWithOneBasedIdsAndStoresThem() {
        finder.toReturn = List.of(
                candidate("sessionId", "session_id", "Login", "Checkout"),
                candidate("csrf", "csrf", "Login", "Checkout", "Order"));

        ToolResult r = tool.execute(Collections.emptyMap());

        assertTrue(r.isSuccess());
        assertTrue(r.getData().contains("2 correlation candidate(s) found"));
        assertTrue(r.getData().contains("1. sessionId -> ${session_id} from 'Login'"));
        assertTrue(r.getData().contains("reused by: Checkout"));
        assertTrue(r.getData().contains("2. csrf -> ${csrf} from 'Login'"));
        assertTrue(r.getData().contains("reused by: Checkout, Order"));
        assertTrue(r.getData().contains("apply_correlation"));

        assertEquals(2, CorrelationCandidateStore.get().size());
    }

    @Test
    void run_replacesPreviouslyStoredCandidates() {
        CorrelationCandidateStore.set(List.of(candidate("old", "old", "Old")));
        finder.toReturn = List.of(candidate("sessionId", "session_id", "Login", "Checkout"));

        tool.execute(Collections.emptyMap());

        List<CorrelationCandidate> stored = CorrelationCandidateStore.get();
        assertEquals(1, stored.size());
        assertEquals("sessionId", stored.get(0).getParameterName());
    }

    @Test
    void execute_ignoresArguments() {
        Map<String, Object> args = new HashMap<>();
        args.put("unused", "value");
        ToolResult r = tool.execute(args);
        assertTrue(r.isSuccess());
    }
}
