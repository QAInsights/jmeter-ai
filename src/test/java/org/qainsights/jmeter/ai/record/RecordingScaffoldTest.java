package org.qainsights.jmeter.ai.record;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.control.CacheManager;
import org.apache.jmeter.protocol.http.control.CookieManager;
import org.apache.jmeter.protocol.http.control.RecordingController;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RecordingScaffold}. Runs against a real {@link JMeterTreeModel}
 * with no GUI, which is the same mode the recorder uses.
 */
class RecordingScaffoldTest {

    private JMeterTreeModel model;

    @BeforeAll
    static void initJMeterProperties() {
        if (JMeterUtils.getJMeterProperties() == null) {
            JMeterUtils.loadJMeterProperties("nonexistent.properties");
        }
    }

    @BeforeEach
    void setUp() {
        model = new JMeterTreeModel();
    }

    private JMeterTreeNode root() {
        return (JMeterTreeNode) model.getRoot();
    }

    /**
     * The Test Plan node the user sees, which is the model root's first child. The root is a
     * separate, invisible node that also wraps a {@code TestPlan}.
     */
    private JMeterTreeNode testPlanNode() {
        for (int i = 0; i < root().getChildCount(); i++) {
            JMeterTreeNode child = (JMeterTreeNode) root().getChildAt(i);
            if (child.getTestElement() instanceof TestPlan) {
                return child;
            }
        }
        return null;
    }

    private static <T> T childOfType(JMeterTreeNode parent, Class<T> type) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            JMeterTreeNode child = (JMeterTreeNode) parent.getChildAt(i);
            if (type.isInstance(child.getTestElement())) {
                return type.cast(child.getTestElement());
            }
        }
        return null;
    }

    /**
     * Exact-class lookup. {@code CookieManager} and {@code CacheManager} both extend
     * {@link ConfigTestElement}, so an {@code isInstance} check would find one of them
     * instead of the HTTP Request Defaults element.
     */
    private static ConfigTestElement httpDefaults(JMeterTreeNode parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            JMeterTreeNode child = (JMeterTreeNode) parent.getChildAt(i);
            if (ConfigTestElement.class.equals(child.getTestElement().getClass())) {
                return (ConfigTestElement) child.getTestElement();
            }
        }
        return null;
    }

    @Test
    void should_buildThreadGroupWithRecordingController() {
        RecordingScaffold scaffold = RecordingScaffold.createIn(model, null);

        assertNotNull(scaffold.threadGroupNode());
        assertInstanceOf(ThreadGroup.class, scaffold.threadGroupNode().getTestElement());
        assertEquals(RecordingScaffold.THREAD_GROUP_NAME, scaffold.threadGroupNode().getName());

        assertInstanceOf(RecordingController.class, scaffold.recordingControllerNode().getTestElement());
        assertSame(scaffold.threadGroupNode(), scaffold.recordingControllerNode().getParent());
    }

    @Test
    void should_addCookieAndCacheManagers() {
        RecordingScaffold scaffold = RecordingScaffold.createIn(model, null);

        assertNotNull(childOfType(scaffold.threadGroupNode(), CookieManager.class),
                "recorded plans need a Cookie Manager to replay sessions");
        assertNotNull(childOfType(scaffold.threadGroupNode(), CacheManager.class));
    }

    @Test
    void should_setGuiAndTestClassOnEveryElement() {
        RecordingScaffold scaffold = RecordingScaffold.createIn(model, "https://shop.test");

        assertGuiRenderable(scaffold.threadGroupNode());
        assertGuiRenderable(scaffold.recordingControllerNode());
        for (int i = 0; i < scaffold.threadGroupNode().getChildCount(); i++) {
            assertGuiRenderable((JMeterTreeNode) scaffold.threadGroupNode().getChildAt(i));
        }
    }

    private static void assertGuiRenderable(JMeterTreeNode node) {
        TestElement element = node.getTestElement();
        assertFalse(element.getPropertyAsString(TestElement.GUI_CLASS).isEmpty(),
                "JMeter cannot render " + element.getClass().getSimpleName() + " without GUI_CLASS");
        assertFalse(element.getPropertyAsString(TestElement.TEST_CLASS).isEmpty(),
                "missing TEST_CLASS on " + element.getClass().getSimpleName());
    }

    @Test
    void should_seedHttpDefaults_when_baseUriIsGiven() {
        RecordingScaffold scaffold = RecordingScaffold.createIn(model, "https://shop.test:8443/catalog");

        ConfigTestElement defaults = httpDefaults(scaffold.threadGroupNode());
        assertNotNull(defaults);
        assertEquals("shop.test", defaults.getPropertyAsString(HTTPSamplerBase.DOMAIN));
        assertEquals("https", defaults.getPropertyAsString(HTTPSamplerBase.PROTOCOL));
        assertEquals("8443", defaults.getPropertyAsString(HTTPSamplerBase.PORT));
    }

    @Test
    void should_omitPort_when_baseUriHasNoExplicitPort() {
        RecordingScaffold scaffold = RecordingScaffold.createIn(model, "https://shop.test");

        ConfigTestElement defaults = httpDefaults(scaffold.threadGroupNode());
        assertNotNull(defaults);
        assertEquals("", defaults.getPropertyAsString(HTTPSamplerBase.PORT));
    }

    @Test
    void should_skipHttpDefaults_when_baseUriIsBlankOrUnparseable() {
        assertNull(httpDefaults(RecordingScaffold.createIn(new JMeterTreeModel(), "  ").threadGroupNode()));
        assertNull(httpDefaults(RecordingScaffold.createIn(new JMeterTreeModel(), "not a uri").threadGroupNode()));
    }

    @Test
    void should_appendBusinessStepsInOrder() {
        RecordingScaffold scaffold = RecordingScaffold.createIn(model, null);

        JMeterTreeNode browse = scaffold.addBusinessStep("Browse Electronics");
        JMeterTreeNode cart = scaffold.addBusinessStep("Add To Cart");

        assertInstanceOf(TransactionController.class, browse.getTestElement());
        assertEquals("Browse Electronics", browse.getName());
        assertEquals(2, scaffold.recordingControllerNode().getChildCount());
        assertSame(browse, scaffold.recordingControllerNode().getChildAt(0));
        assertSame(cart, scaffold.recordingControllerNode().getChildAt(1));
        assertEquals(2, scaffold.businessSteps().size());
    }

    @Test
    void should_trimBusinessStepName() {
        RecordingScaffold scaffold = RecordingScaffold.createIn(model, null);
        assertEquals("Checkout", scaffold.addBusinessStep("  Checkout  ").getName());
    }

    @Test
    void should_exposeBusinessStepsAsDefensiveCopy() {
        RecordingScaffold scaffold = RecordingScaffold.createIn(model, null);
        scaffold.addBusinessStep("Home");

        assertThrows(UnsupportedOperationException.class, () -> scaffold.businessSteps().clear());
    }

    @Test
    void should_leaveExistingPlanContentUntouched() {
        int childrenBefore = testPlanNode().getChildCount();
        RecordingScaffold.createIn(model, null);
        assertEquals(childrenBefore + 1, testPlanNode().getChildCount(),
                "scaffolding should append one Thread Group, not rewrite the plan");
    }

    @Test
    void should_attachThreadGroupInsideTheTestPlan() {
        // Regression: attaching to the model root made the Thread Group a SIBLING of the Test
        // Plan, so JMeter neither ran it nor saved it and the recording silently disappeared.
        RecordingScaffold scaffold = RecordingScaffold.createIn(model, null);

        assertSame(testPlanNode(), scaffold.threadGroupNode().getParent(),
                "the Thread Group must be part of the Test Plan, not a sibling of it");
        assertNotSame(root(), scaffold.threadGroupNode().getParent());
    }

    @Test
    void should_rejectInvalidInput() {
        assertThrows(RecordingException.class, () -> RecordingScaffold.createIn(null, null));

        RecordingScaffold scaffold = RecordingScaffold.createIn(model, null);
        assertThrows(RecordingException.class, () -> scaffold.addBusinessStep(null));
        assertThrows(RecordingException.class, () -> scaffold.addBusinessStep("   "));
    }
}
