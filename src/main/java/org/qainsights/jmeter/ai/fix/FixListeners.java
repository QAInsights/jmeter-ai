package org.qainsights.jmeter.ai.fix;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

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
        HashTree planChildren = null;
        for (Object key : tree == null ? List.of() : tree.list()) {
            if (element(key) instanceof TestPlan) {
                planChildren = tree.getTree(key);
                break;
            }
        }
        boolean hasView = false;
        boolean hasSummary = false;
        if (planChildren != null) {
            for (Object key : planChildren.list()) {
                TestElement element = element(key);
                String guiClass = element == null ? "" : element.getPropertyAsString(TestElement.GUI_CLASS);
                hasView |= VIEW_RESULTS_GUI.equals(guiClass);
                hasSummary |= SUMMARY_REPORT_GUI.equals(guiClass);
            }
        }
        List<String> missing = new ArrayList<>();
        if (!hasView) {
            missing.add("viewresultstree");
        }
        if (!hasSummary) {
            missing.add("summaryreport");
        }
        return List.copyOf(missing);
    }

    public static void deliverLastResults() {
        GuiPackage gui = GuiPackage.getInstance();
        if (gui == null || gui.getTreeModel() == null) {
            return;
        }
        List<SampleResult> results = TestPlanRunner.lastSampleResults();
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
            if (element != null && gui.getGui(element) instanceof Visualizer visualizer) {
                results.forEach(visualizer::add);
            }
            deliver(tree.getTree(key), gui, results);
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
