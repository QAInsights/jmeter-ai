package org.qainsights.jmeter.ai.record;

import java.util.List;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.proxy.ProxyControl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RecordingSession}, the business-step boundary logic.
 * <p>
 * Uses a mock {@link ProxyControl} so no port is bound and no certificate is generated;
 * what matters here is that the capture target moves at the right moment.
 */
class RecordingSessionTest {

    private JMeterTreeModel model;
    private RecordingScaffold scaffold;
    private ProxyControl proxy;
    private JMeterProxyRecorder recorder;
    private RecordingSession session;

    @BeforeAll
    static void initJMeter() {
        RecordingTestSupport.initJMeterHome();
    }

    @BeforeEach
    void setUp() {
        model = new JMeterTreeModel();
        scaffold = RecordingScaffold.createIn(model, "https://shop.test");
        proxy = mock(ProxyControl.class);
        recorder = new JMeterProxyRecorder(model, proxy);
        // Short waits: quiescence behaviour is covered by RecordingSampleCounterTest.
        session = new RecordingSession(scaffold, recorder, 10, 200);
    }

    @Test
    void should_createTransactionControllerAndRetargetRecorder_when_stepBegins() {
        session.beginStep("Add To Cart");

        assertEquals("Add To Cart", session.currentStepName());
        assertEquals(1, scaffold.businessSteps().size());

        ArgumentCaptor<JMeterTreeNode> target = ArgumentCaptor.forClass(JMeterTreeNode.class);
        verify(proxy).setTarget(target.capture());
        assertEquals("Add To Cart",
                ((TransactionController) target.getValue().getTestElement()).getName());
    }

    @Test
    void should_recordStepsInOrder() {
        session.beginStep("Open Home Page");
        session.endStep();
        session.beginStep("Search");
        session.endStep();

        assertEquals(List.of("Open Home Page", "Search"), session.stepNames());
        assertNull(session.currentStepName(), "no step should be open after endStep");
    }

    @Test
    void should_closePreviousStepImplicitly_when_agentForgetsToEndIt() {
        session.beginStep("Search");
        session.beginStep("Add To Cart");

        assertEquals("Add To Cart", session.currentStepName());
        assertEquals(2, scaffold.businessSteps().size(),
                "a forgotten end must not nest or lose the step");
    }

    @Test
    void should_disambiguateRepeatedStepNames() {
        session.beginStep("Search");
        session.beginStep("Search");

        List<JMeterTreeNode> steps = scaffold.businessSteps();
        assertEquals("Search", ((TransactionController) steps.get(0).getTestElement()).getName());
        assertEquals("Search 2", ((TransactionController) steps.get(1).getTestElement()).getName(),
                "two identically named controllers would be indistinguishable in the plan");
    }

    @Test
    void should_waitForQuiescenceBeforeMovingTheTarget() {
        JMeterProxyRecorder spyRecorder = spy(new JMeterProxyRecorder(model, proxy));
        RecordingSession spied = new RecordingSession(scaffold, spyRecorder, 10, 200);

        spied.beginStep("Checkout");

        // In-flight requests must land in the previous step before the target moves.
        InOrder inOrder = inOrder(spyRecorder, proxy);
        inOrder.verify(spyRecorder).awaitQuiescence(anyLong(), anyLong());
        inOrder.verify(proxy).setTarget(any(JMeterTreeNode.class));
    }

    @Test
    void should_rejectBlankStepName() {
        assertThrows(RecordingException.class, () -> session.beginStep("  "));
        assertThrows(RecordingException.class, () -> session.beginStep(null));
    }

    @Test
    void should_markFinishedAndKeepSummary() {
        session.beginStep("Checkout");
        session.finish("Recorded a browse and checkout journey.");

        assertTrue(session.isFinished());
        assertEquals("Recorded a browse and checkout journey.", session.summary());
        assertNull(session.currentStepName());
    }

    @Test
    void should_rejectFurtherWork_when_finished() {
        session.finish("done");

        assertThrows(RecordingException.class, () -> session.beginStep("Too Late"));
        assertThrows(RecordingException.class, () -> session.endStep());
        assertThrows(RecordingException.class, () -> session.finish("again"));
    }

    @Test
    void should_toleratePersistentTraffic_when_quiescenceTimesOut() {
        JMeterProxyRecorder stubborn = mock(JMeterProxyRecorder.class);
        when(stubborn.awaitQuiescence(anyLong(), anyLong())).thenReturn(false);
        RecordingSession noisy = new RecordingSession(scaffold, stubborn, 10, 50);

        // A streaming or long-polling endpoint may never go quiet; the boundary is then
        // approximate, but the session must not stall or fail.
        assertDoesNotThrow(() -> noisy.beginStep("Live Feed"));
        assertEquals("Live Feed", noisy.currentStepName());
    }

    @Test
    void should_rejectMissingCollaborators() {
        assertThrows(RecordingException.class, () -> new RecordingSession(null, recorder));
        assertThrows(RecordingException.class, () -> new RecordingSession(scaffold, null));
    }
}
