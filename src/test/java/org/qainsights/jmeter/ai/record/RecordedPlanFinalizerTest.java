package org.qainsights.jmeter.ai.record;

import java.util.ArrayList;
import java.util.List;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.control.RecordingController;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RecordedPlanFinalizer}. Uses a real {@link JMeterTreeModel} because
 * the behaviour under test is tree mutation, which a mock would not exercise.
 */
class RecordedPlanFinalizerTest {

    private JMeterTreeModel model;
    private RecordingScaffold scaffold;

    @BeforeAll
    static void initJMeterProperties() {
        if (JMeterUtils.getJMeterProperties() == null) {
            JMeterUtils.loadJMeterProperties("nonexistent.properties");
        }
    }

    @BeforeEach
    void setUp() {
        model = new JMeterTreeModel();
        scaffold = RecordingScaffold.createIn(model, "https://petstore.octoperf.com/x");
    }

    /** Stands in for a sampler the proxy would have delivered into the target node. */
    private JMeterTreeNode captureSampler(JMeterTreeNode target, String name) {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setName(name);
        sampler.setProperty(TestElement.TEST_CLASS, HTTPSamplerProxy.class.getName());
        JMeterTreeNode node = new JMeterTreeNode(sampler, model);
        model.insertNodeInto(node, target, target.getChildCount());
        return node;
    }

    private static List<String> childNames(JMeterTreeNode parent) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < parent.getChildCount(); i++) {
            names.add(((JMeterTreeNode) parent.getChildAt(i)).getName());
        }
        return names;
    }

    private static boolean hasElementOfType(JMeterTreeNode parent, Class<?> type) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            JMeterTreeNode child = (JMeterTreeNode) parent.getChildAt(i);
            if (type.isInstance(child.getTestElement())) {
                return true;
            }
        }
        return false;
    }

    @Test
    void should_moveStepsIntoThreadGroup() {
        scaffold.addBusinessStep("Login");
        scaffold.addBusinessStep("Search");

        int promoted = RecordedPlanFinalizer.promoteRecordedSteps(model, scaffold);

        assertEquals(2, promoted);
        assertTrue(childNames(scaffold.threadGroupNode()).containsAll(List.of("Login", "Search")),
                "the recorded steps must end up directly under the thread group");
    }

    @Test
    void should_removeTheEmptiedRecordingController() {
        // A Recording Controller is capture scaffolding; shipping it in the plan is the
        // manual tidy-up this class exists to remove.
        scaffold.addBusinessStep("Login");

        RecordedPlanFinalizer.promoteRecordedSteps(model, scaffold);

        assertFalse(hasElementOfType(scaffold.threadGroupNode(), RecordingController.class),
                "the Recording Controller must not survive into the finished plan");
    }

    @Test
    void should_preserveRecordedOrder() {
        // Order is the recorded journey; a shuffled plan does not reproduce the user's flow.
        scaffold.addBusinessStep("Login");
        scaffold.addBusinessStep("Search");
        scaffold.addBusinessStep("Checkout");

        RecordedPlanFinalizer.promoteRecordedSteps(model, scaffold);

        List<String> names = childNames(scaffold.threadGroupNode());
        assertTrue(names.indexOf("Login") < names.indexOf("Search"));
        assertTrue(names.indexOf("Search") < names.indexOf("Checkout"));
    }

    @Test
    void should_keepSamplersInsideTheirStep() {
        JMeterTreeNode login = scaffold.addBusinessStep("Login");
        captureSampler(login, "/login.action");
        captureSampler(login, "/home.action");

        RecordedPlanFinalizer.promoteRecordedSteps(model, scaffold);

        assertEquals(List.of("/login.action", "/home.action"), childNames(login),
                "promotion must move whole subtrees, not flatten them");
        assertSame(scaffold.threadGroupNode(), login.getParent());
    }

    @Test
    void should_promoteSamplersRecordedBeforeAnyStep() {
        // Traffic that arrives before the agent opens a step lands on the Recording
        // Controller itself. Dropping it would silently lose the initial page load.
        captureSampler(scaffold.recordingControllerNode(), "/Account.action");
        scaffold.addBusinessStep("Login");

        int promoted = RecordedPlanFinalizer.promoteRecordedSteps(model, scaffold);

        assertEquals(2, promoted);
        assertTrue(childNames(scaffold.threadGroupNode()).contains("/Account.action"));
    }

    @Test
    void should_placeStepsAfterTheConfigElements() {
        scaffold.addBusinessStep("Login");

        RecordedPlanFinalizer.promoteRecordedSteps(model, scaffold);

        List<String> names = childNames(scaffold.threadGroupNode());
        assertTrue(names.indexOf("HTTP Cookie Manager") < names.indexOf("Login"),
                "config elements should still read first in the plan");
    }

    @Test
    void should_leaveTreeUntouched_when_nothingWasRecorded() {
        // An empty scaffold tells a user diagnosing a dead recording more than a bare
        // thread group does.
        List<String> before = childNames(scaffold.threadGroupNode());

        int promoted = RecordedPlanFinalizer.promoteRecordedSteps(model, scaffold);

        assertEquals(0, promoted);
        assertEquals(before, childNames(scaffold.threadGroupNode()));
        assertTrue(hasElementOfType(scaffold.threadGroupNode(), RecordingController.class));
    }

    @Test
    void should_keepThreadGroupAttachedToTheTestPlan() {
        scaffold.addBusinessStep("Login");
        Object parentBefore = scaffold.threadGroupNode().getParent();

        RecordedPlanFinalizer.promoteRecordedSteps(model, scaffold);

        assertSame(parentBefore, scaffold.threadGroupNode().getParent(),
                "finalizing must not detach the thread group from the plan");
        assertInstanceOf(org.apache.jmeter.testelement.TestPlan.class,
                ((JMeterTreeNode) scaffold.threadGroupNode().getParent()).getTestElement(),
                "the thread group must remain inside the Test Plan");
    }

    @Test
    void should_produceRunnableStepsUnderTheThreadGroup() {
        // The point of the exercise: the steps must be real controllers in a thread group.
        scaffold.addBusinessStep("Login");

        RecordedPlanFinalizer.promoteRecordedSteps(model, scaffold);

        assertTrue(hasElementOfType(scaffold.threadGroupNode(), TransactionController.class));
    }

    @Test
    void should_countOnlySamplersActuallyInThePlan() {
        // The honest metric. ProxyControl notifies sample listeners even for requests it
        // rejects, so the listener count once reported 1,557 captures against 0 real samplers.
        JMeterTreeNode login = scaffold.addBusinessStep("Login");
        captureSampler(login, "/login.action");
        captureSampler(login, "/home.action");
        scaffold.addBusinessStep("Search");

        assertEquals(2, RecordedPlanFinalizer.countSamplers(scaffold.threadGroupNode()),
                "controllers and config elements are not samplers");
    }

    @Test
    void should_countZero_when_stepsAreEmpty() {
        scaffold.addBusinessStep("Login");

        assertEquals(0, RecordedPlanFinalizer.countSamplers(scaffold.threadGroupNode()),
                "an empty step must not be reported as captured traffic");
    }

    @Test
    void should_countSamplersAfterPromotion() {
        JMeterTreeNode login = scaffold.addBusinessStep("Login");
        captureSampler(login, "/login.action");

        RecordedPlanFinalizer.promoteRecordedSteps(model, scaffold);

        assertEquals(1, RecordedPlanFinalizer.countSamplers(scaffold.threadGroupNode()),
                "reorganising the plan must not change what was captured");
    }

    @Test
    void should_countZero_when_nodeIsNull() {
        assertEquals(0, RecordedPlanFinalizer.countSamplers(null));
    }

    @Test
    void should_rejectMissingArguments() {
        assertThrows(RecordingException.class,
                () -> RecordedPlanFinalizer.promoteRecordedSteps(null, scaffold));
        assertThrows(RecordingException.class,
                () -> RecordedPlanFinalizer.promoteRecordedSteps(model, null));
    }
}
