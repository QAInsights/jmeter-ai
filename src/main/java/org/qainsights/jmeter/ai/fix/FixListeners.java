package org.qainsights.jmeter.ai.fix;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.jmeter.assertions.AssertionResult;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.visualizers.Visualizer;
import org.apache.jorphan.collections.HashTree;
import org.qainsights.jmeter.ai.agent.jmeter.EdtExecutor;
import org.qainsights.jmeter.ai.agent.jmeter.GuiElementAdder;
import org.qainsights.jmeter.ai.agent.jmeter.TestPlanRunner;

/** Installs Make it pass result listeners and forwards the final run to them. */
public final class FixListeners {
    private static final String VIEW_RESULTS_GUI =
            "org.apache.jmeter.visualizers.ViewResultsFullVisualizer";
    private static final String SUMMARY_REPORT_GUI = "org.apache.jmeter.visualizers.StatVisualizer";

    private FixListeners() {
    }

    public static List<String> ensurePresent() {
        GuiPackage gui = GuiPackage.getInstance();
        if (gui == null || gui.getTreeModel() == null) {
            return List.of();
        }
        AtomicReference<HashTree> treeRef = new AtomicReference<>();
        EdtExecutor.swing().run(() -> {
            gui.updateCurrentNode();
            treeRef.set(gui.getTreeModel().getTestPlan());
        });
        HashTree tree = treeRef.get();
        JMeterTreeNode planNode = findTestPlanNode(tree);
        if (planNode == null) {
            return List.of();
        }
        List<String> missing = missingListeners(tree);
        List<String> added = new ArrayList<>();
        GuiElementAdder adder = new GuiElementAdder();
        for (String listener : missing) {
            String alias = "viewresultstree".equals(listener) ? "viewresultstree" : "summaryreport";
            String name = "viewresultstree".equals(listener)
                    ? "Make it pass - View Results Tree" : "Make it pass - Summary Report";
            if (adder.add(planNode, alias, name) != null) {
                added.add(name);
            }
        }
        return List.copyOf(added);
    }

    static List<String> missingListeners(HashTree tree) {
        boolean[] found = new boolean[2];
        findListeners(tree, found);
        List<String> missing = new ArrayList<>();
        if (!found[0]) {
            missing.add("viewresultstree");
        }
        if (!found[1]) {
            missing.add("summaryreport");
        }
        return List.copyOf(missing);
    }

    public static void deliverLastResults() {
        List<SampleResult> results = TestPlanRunner.takeLastSampleResults();
        GuiPackage gui = GuiPackage.getInstance();
        if (gui == null || gui.getTreeModel() == null) {
            return;
        }
        results.forEach(FixListeners::markSoftFailure);
        EdtExecutor.swing().run(() -> {
            HashTree tree = gui.getTreeModel().getTestPlan();
            deliver(tree, gui, results);
        });
    }

    private static void deliver(HashTree tree, GuiPackage gui, List<SampleResult> results) {
        if (tree == null) {
            return;
        }
        for (Object key : tree.list()) {
            TestElement element = element(key);
            if (isVisualizerElement(element) && gui.getGui(element) instanceof Visualizer visualizer) {
                results.forEach(visualizer::add);
            }
            deliver(tree.getTree(key), gui, results);
        }
    }

    static boolean isVisualizerElement(TestElement element) {
        if (element == null) {
            return false;
        }
        String guiClass = element.getPropertyAsString(TestElement.GUI_CLASS);
        if (guiClass == null || guiClass.isBlank()) {
            return false;
        }
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            Class<?> type = Class.forName(guiClass, false,
                    loader == null ? FixListeners.class.getClassLoader() : loader);
            return Visualizer.class.isAssignableFrom(type);
        } catch (ClassNotFoundException | LinkageError | SecurityException e) {
            return false;
        }
    }

    static void markSoftFailure(SampleResult result) {
        String reason = SoftFailureDetector.reason(result);
        if (reason == null) {
            return;
        }
        result.setSuccessful(false);
        AssertionResult assertion = new AssertionResult("Make it pass: soft failure");
        assertion.setFailure(true);
        assertion.setFailureMessage(reason);
        result.addAssertionResult(assertion);
    }

    private static void findListeners(HashTree tree, boolean[] found) {
        if (tree == null) {
            return;
        }
        for (Object key : tree.list()) {
            TestElement element = element(key);
            if (element != null) {
                String guiClass = element.getPropertyAsString(TestElement.GUI_CLASS);
                found[0] |= VIEW_RESULTS_GUI.equals(guiClass);
                found[1] |= SUMMARY_REPORT_GUI.equals(guiClass);
            }
            findListeners(tree.getTree(key), found);
        }
    }

    private static JMeterTreeNode findTestPlanNode(HashTree tree) {
        if (tree == null) {
            return null;
        }
        for (Object key : tree.list()) {
            if (key instanceof JMeterTreeNode && element(key) instanceof TestPlan) {
                return (JMeterTreeNode) key;
            }
            JMeterTreeNode found = findTestPlanNode(tree.getTree(key));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static TestElement element(Object key) {
        return key instanceof JMeterTreeNode ? ((JMeterTreeNode) key).getTestElement()
                : key instanceof TestElement ? (TestElement) key : null;
    }
}
