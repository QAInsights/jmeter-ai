package org.qainsights.jmeter.ai.record;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.control.gui.LoopControlPanel;
import org.apache.jmeter.control.gui.TransactionControllerGui;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.control.CacheManager;
import org.apache.jmeter.protocol.http.control.CookieManager;
import org.apache.jmeter.protocol.http.control.RecordingController;
import org.apache.jmeter.protocol.http.config.gui.HttpDefaultsGui;
import org.apache.jmeter.protocol.http.gui.CacheManagerGui;
import org.apache.jmeter.protocol.http.gui.CookiePanel;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.threads.gui.ThreadGroupGui;

/**
 * Builds the test-plan skeleton a recording is captured into, and creates the
 * Transaction Controller for each business step.
 * <p>
 * The skeleton is constructed programmatically rather than loaded from
 * {@code ${JMETER_HOME}/bin/templates/recording.jmx} for three reasons: the template
 * contains unsubstituted {@code [=hostToRecord]} placeholders that only JMeter's own
 * template engine resolves; it ships a disabled {@code HTTP(S) Test Script Recorder}
 * element we would have to strip; and depending on a file inside the user's JMeter
 * install makes the behaviour vary with their local edits. Building it here keeps the
 * result deterministic and unit-testable.
 * <p>
 * Every element gets {@code TestElement.TEST_CLASS} and {@code TestElement.GUI_CLASS}
 * set, without which JMeter cannot render the node.
 */
public final class RecordingScaffold {

    public static final String THREAD_GROUP_NAME = "Feather Wand Recording";
    public static final String RECORDING_CONTROLLER_NAME = "Recording Controller";

    private final JMeterTreeModel model;
    private final JMeterTreeNode threadGroupNode;
    private final JMeterTreeNode recordingControllerNode;
    private final List<JMeterTreeNode> businessSteps = new ArrayList<>();

    private RecordingScaffold(JMeterTreeModel model, JMeterTreeNode threadGroupNode,
                              JMeterTreeNode recordingControllerNode) {
        this.model = model;
        this.threadGroupNode = threadGroupNode;
        this.recordingControllerNode = recordingControllerNode;
    }

    /**
     * Appends a recording skeleton to the Test Plan, leaving any existing content untouched:
     * Thread Group -> (Cookie Manager, Cache Manager, [HTTP Request Defaults], Recording
     * Controller).
     *
     * @param model   the tree model to build into
     * @param baseUri the site being recorded; when parseable, seeds HTTP Request Defaults
     *                so recorded samplers can be re-pointed at another environment. May be
     *                null or blank to skip that element.
     * @return a scaffold handle for creating business steps
     * @throws RecordingException if {@code model} is null or has no usable root
     */
    public static RecordingScaffold createIn(JMeterTreeModel model, String baseUri) {
        if (model == null) {
            throw new RecordingException("Cannot build a recording scaffold without a tree model");
        }
        Object rawRoot = model.getRoot();
        if (!(rawRoot instanceof JMeterTreeNode)) {
            throw new RecordingException("Tree model has no JMeter root node");
        }
        JMeterTreeNode planNode = testPlanNode((JMeterTreeNode) rawRoot);

        JMeterTreeNode threadGroupNode =
                insert(model, planNode, buildThreadGroup(), planNode.getChildCount());
        insert(model, threadGroupNode, buildCookieManager(), threadGroupNode.getChildCount());
        insert(model, threadGroupNode, buildCacheManager(), threadGroupNode.getChildCount());

        ConfigTestElement defaults = buildHttpDefaults(baseUri);
        if (defaults != null) {
            insert(model, threadGroupNode, defaults, threadGroupNode.getChildCount());
        }

        RecordingController controller = new RecordingController();
        controller.setName(RECORDING_CONTROLLER_NAME);
        // RecordingController's GUI class is org.apache.jmeter.protocol.http.control.gui.RecordController,
        // referenced by name because it is not part of the compile-time API surface we rely on elsewhere.
        controller.setProperty(TestElement.TEST_CLASS, RecordingController.class.getName());
        controller.setProperty(TestElement.GUI_CLASS,
                "org.apache.jmeter.protocol.http.control.gui.RecordController");
        JMeterTreeNode recordingControllerNode =
                insert(model, threadGroupNode, controller, threadGroupNode.getChildCount());

        return new RecordingScaffold(model, threadGroupNode, recordingControllerNode);
    }

    /**
     * Creates the Transaction Controller for one business step and appends it to the
     * Recording Controller. The recorder's target is then re-pointed at the returned node
     * so subsequent traffic is captured inside it.
     *
     * @param name human-readable step name, e.g. {@code "Add To Cart"}
     * @return the new Transaction Controller node
     * @throws RecordingException if {@code name} is null or blank
     */
    public JMeterTreeNode addBusinessStep(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new RecordingException("A business step needs a name");
        }
        TransactionController controller = new TransactionController();
        controller.setName(name.trim());
        controller.setIncludeTimers(false);
        controller.setProperty(TestElement.TEST_CLASS, TransactionController.class.getName());
        controller.setProperty(TestElement.GUI_CLASS, TransactionControllerGui.class.getName());
        JMeterTreeNode node = insert(model, recordingControllerNode, controller,
                recordingControllerNode.getChildCount());
        businessSteps.add(node);
        return node;
    }

    public JMeterTreeNode threadGroupNode() {
        return threadGroupNode;
    }

    public JMeterTreeNode recordingControllerNode() {
        return recordingControllerNode;
    }

    /**
     * @return the business-step nodes created so far, in creation order
     */
    public List<JMeterTreeNode> businessSteps() {
        return Collections.unmodifiableList(new ArrayList<>(businessSteps));
    }

    /**
     * The node a Thread Group must hang from.
     * <p>
     * {@code JMeterTreeModel}'s root is an invisible node that itself wraps a
     * {@code TestPlan}, and the Test Plan the user sees is its first child. Attaching to the
     * root therefore makes the Thread Group a <em>sibling</em> of the Test Plan rather than
     * part of it - JMeter neither runs nor saves it, so the recording silently vanishes when
     * the plan is saved. Falls back to the root for a model shaped unexpectedly, which is
     * still better than refusing to record.
     */
    private static JMeterTreeNode testPlanNode(JMeterTreeNode root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            JMeterTreeNode child = (JMeterTreeNode) root.getChildAt(i);
            if (child.getTestElement() instanceof TestPlan) {
                return child;
            }
        }
        return root;
    }

    private static JMeterTreeNode insert(JMeterTreeModel model, JMeterTreeNode parent,
                                         TestElement element, int index) {
        JMeterTreeNode node = new JMeterTreeNode(element, model);
        model.insertNodeInto(node, parent, index);
        return node;
    }

    private static ThreadGroup buildThreadGroup() {
        LoopController loop = new LoopController();
        loop.setLoops(1);
        loop.setFirst(true);
        loop.setProperty(TestElement.TEST_CLASS, LoopController.class.getName());
        loop.setProperty(TestElement.GUI_CLASS, LoopControlPanel.class.getName());

        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setName(THREAD_GROUP_NAME);
        threadGroup.setNumThreads(1);
        threadGroup.setRampUp(1);
        threadGroup.setSamplerController(loop);
        threadGroup.setProperty(TestElement.TEST_CLASS, ThreadGroup.class.getName());
        threadGroup.setProperty(TestElement.GUI_CLASS, ThreadGroupGui.class.getName());
        return threadGroup;
    }

    private static CookieManager buildCookieManager() {
        CookieManager cookieManager = new CookieManager();
        cookieManager.setName("HTTP Cookie Manager");
        cookieManager.setClearEachIteration(true);
        cookieManager.setProperty(TestElement.TEST_CLASS, CookieManager.class.getName());
        cookieManager.setProperty(TestElement.GUI_CLASS, CookiePanel.class.getName());
        return cookieManager;
    }

    private static CacheManager buildCacheManager() {
        CacheManager cacheManager = new CacheManager();
        cacheManager.setName("HTTP Cache Manager");
        cacheManager.setClearEachIteration(true);
        cacheManager.setProperty(TestElement.TEST_CLASS, CacheManager.class.getName());
        cacheManager.setProperty(TestElement.GUI_CLASS, CacheManagerGui.class.getName());
        return cacheManager;
    }

    /**
     * @return HTTP Request Defaults seeded from {@code baseUri}, or null when the URI is
     *         absent or unparseable (in which case the plan simply has no defaults element)
     */
    private static ConfigTestElement buildHttpDefaults(String baseUri) {
        if (baseUri == null || baseUri.trim().isEmpty()) {
            return null;
        }
        String host;
        String scheme;
        int port;
        try {
            URI uri = new URI(baseUri.trim());
            host = uri.getHost();
            scheme = uri.getScheme();
            port = uri.getPort();
        } catch (Exception e) {
            return null;
        }
        if (host == null || host.isEmpty()) {
            return null;
        }
        ConfigTestElement defaults = new ConfigTestElement();
        defaults.setName("HTTP Request Defaults");
        defaults.setProperty(HTTPSamplerBase.DOMAIN, host);
        if (scheme != null && !scheme.isEmpty()) {
            defaults.setProperty(HTTPSamplerBase.PROTOCOL, scheme);
        }
        if (port > 0) {
            defaults.setProperty(HTTPSamplerBase.PORT, String.valueOf(port));
        }
        defaults.setProperty(TestElement.TEST_CLASS, ConfigTestElement.class.getName());
        defaults.setProperty(TestElement.GUI_CLASS, HttpDefaultsGui.class.getName());
        return defaults;
    }
}
