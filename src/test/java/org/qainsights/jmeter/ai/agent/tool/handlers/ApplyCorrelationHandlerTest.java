package org.qainsights.jmeter.ai.agent.tool.handlers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.agent.correlation.CorrelationApplier;
import org.qainsights.jmeter.ai.agent.correlation.CorrelationCandidateStore;
import org.qainsights.jmeter.ai.agent.tool.Tool;
import org.qainsights.jmeter.ai.agent.tool.ToolResult;
import org.qainsights.jmeter.ai.correlation.CorrelationCandidate;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link ApplyCorrelationHandler} using a fake applier seam. */
class ApplyCorrelationHandlerTest {

    /** Records the candidates it was called with and returns a canned applied count. */
    private static final class FakeApplier implements CorrelationApplier {
        List<CorrelationCandidate> lastCandidates;
        int toReturn = 0;

        @Override
        public int apply(List<CorrelationCandidate> approvedCandidates) {
            this.lastCandidates = approvedCandidates;
            return toReturn == 0 ? approvedCandidates.size() : toReturn;
        }
    }

    private JMeterTreeNode root;
    private FakeApplier applier;
    private Tool tool;

    @BeforeEach
    void setUp() {
        ConfigTestElement element = new ConfigTestElement();
        element.setName("Test Plan");
        root = new JMeterTreeNode(element, null);
        applier = new FakeApplier();
        tool = new ApplyCorrelationHandler(() -> root, applier).tool();
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
        for (String t : targets) {
            c.addTargetSamplerName(t);
        }
        return c;
    }

    private static Map<String, Object> args(List<String> candidateIds, Boolean applyAll) {
        Map<String, Object> map = new HashMap<>();
        if (candidateIds != null) {
            map.put("candidate_ids", candidateIds);
        }
        if (applyAll != null) {
            map.put("apply_all", applyAll);
        }
        return map;
    }

    @Test
    void spec_declaresName_andNoRequiredParameters() {
        assertEquals(ApplyCorrelationHandler.APPLY_CORRELATION, tool.getSpec().getName());
        assertTrue(tool.getSpec().getRequiredParameters().isEmpty());
    }

    @Test
    void run_noTestPlan_returnsErrorWithoutDelegating() {
        Tool noPlan = new ApplyCorrelationHandler(() -> null, applier).tool();
        ToolResult r = noPlan.execute(args(List.of("1"), null));

        assertFalse(r.isSuccess());
        assertEquals(ApplyCorrelationHandler.ERR_NO_TEST_PLAN, r.getErrorCode());
        assertNull(applier.lastCandidates);
    }

    @Test
    void run_noCandidatesStored_returnsError() {
        ToolResult r = tool.execute(args(List.of("1"), null));

        assertFalse(r.isSuccess());
        assertEquals(ApplyCorrelationHandler.ERR_NO_CANDIDATES, r.getErrorCode());
        assertNull(applier.lastCandidates);
    }

    @Test
    void run_noSelection_returnsError() {
        CorrelationCandidateStore.set(List.of(candidate("sessionId", "session_id", "Login")));

        ToolResult r = tool.execute(args(null, null));

        assertFalse(r.isSuccess());
        assertEquals(ApplyCorrelationHandler.ERR_NO_SELECTION, r.getErrorCode());
        assertNull(applier.lastCandidates);
    }

    @Test
    void run_invalidCandidateId_returnsError() {
        CorrelationCandidateStore.set(List.of(candidate("sessionId", "session_id", "Login")));

        ToolResult r = tool.execute(args(List.of("99"), null));

        assertFalse(r.isSuccess());
        assertEquals(ApplyCorrelationHandler.ERR_INVALID_CANDIDATE_ID, r.getErrorCode());
        assertNull(applier.lastCandidates);
    }

    @Test
    void run_nonNumericCandidateId_returnsError() {
        CorrelationCandidateStore.set(List.of(candidate("sessionId", "session_id", "Login")));

        ToolResult r = tool.execute(args(List.of("abc"), null));

        assertFalse(r.isSuccess());
        assertEquals(ApplyCorrelationHandler.ERR_INVALID_CANDIDATE_ID, r.getErrorCode());
    }

    @Test
    void run_validCandidateIds_appliesOnlySelectedAndApprovesThem() {
        CorrelationCandidate first = candidate("sessionId", "session_id", "Login", "Checkout");
        CorrelationCandidate second = candidate("csrf", "csrf", "Login", "Checkout");
        CorrelationCandidateStore.set(List.of(first, second));

        ToolResult r = tool.execute(args(List.of("2"), null));

        assertTrue(r.isSuccess());
        assertEquals(1, applier.lastCandidates.size());
        assertSame(second, applier.lastCandidates.get(0));
        assertEquals(CorrelationCandidate.Status.APPROVED, second.getStatus());
        assertEquals(CorrelationCandidate.Status.PENDING, first.getStatus());
    }

    @Test
    void run_duplicateCandidateIds_areDeduplicated() {
        CorrelationCandidate first = candidate("sessionId", "session_id", "Login", "Checkout");
        CorrelationCandidateStore.set(List.of(first));

        tool.execute(args(List.of("1", "1"), null));

        assertEquals(1, applier.lastCandidates.size());
    }

    @Test
    void run_applyAll_appliesEveryStoredCandidate() {
        CorrelationCandidate first = candidate("sessionId", "session_id", "Login", "Checkout");
        CorrelationCandidate second = candidate("csrf", "csrf", "Login", "Checkout");
        CorrelationCandidateStore.set(List.of(first, second));

        ToolResult r = tool.execute(args(null, true));

        assertTrue(r.isSuccess());
        assertEquals(2, applier.lastCandidates.size());
        assertEquals(CorrelationCandidate.Status.APPROVED, first.getStatus());
        assertEquals(CorrelationCandidate.Status.APPROVED, second.getStatus());
    }

    @Test
    void run_applyAllTrue_ignoresCandidateIds() {
        CorrelationCandidate first = candidate("sessionId", "session_id", "Login", "Checkout");
        CorrelationCandidateStore.set(List.of(first));

        ToolResult r = tool.execute(args(List.of("99"), true));

        assertTrue(r.isSuccess());
        assertEquals(1, applier.lastCandidates.size());
    }

    @Test
    void run_successfulApply_reportsAppliedCountAndDetails() {
        CorrelationCandidate first = candidate("sessionId", "session_id", "Login", "Checkout", "Order");
        CorrelationCandidateStore.set(List.of(first));
        applier.toReturn = 1;

        ToolResult r = tool.execute(args(List.of("1"), null));

        assertTrue(r.isSuccess());
        assertTrue(r.getData().contains("Applied 1 correlation extractor(s)."));
        assertTrue(r.getData().contains("sessionId -> ${session_id} extracted from 'Login'"));
        assertTrue(r.getData().contains("replaced in: Checkout, Order"));
    }

    @Test
    void run_stringListArgument_withNonStringItems_isCoerced() {
        CorrelationCandidateStore.set(List.of(candidate("sessionId", "session_id", "Login")));
        List<Object> ids = new ArrayList<>();
        ids.add(1);
        Map<String, Object> map = new HashMap<>();
        map.put("candidate_ids", ids);

        ToolResult r = tool.execute(map);

        assertTrue(r.isSuccess());
        assertEquals(1, applier.lastCandidates.size());
    }
}
