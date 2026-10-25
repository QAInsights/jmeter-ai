package org.qainsights.jmeter.ai.fix;

import java.util.List;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FixListenersTest {
    @Test
    void requestsBothListenersWhenPlanHasNone() {
        assertEquals(List.of("viewresultstree", "summaryreport"),
                FixListeners.missingListeners(plan()));
    }

    @Test
    void recognizesDirectListenersButNotListenersNestedUnderAGroup() {
        HashTree tree = plan();
        HashTree testPlan = tree.getTree(tree.list().iterator().next());
        ResultCollector viewResults = listener("org.apache.jmeter.visualizers.ViewResultsFullVisualizer");
        testPlan.add(viewResults);
        ThreadGroup group = new ThreadGroup();
        testPlan.add(group).add(listener("org.apache.jmeter.visualizers.StatVisualizer"));

        assertEquals(List.of("summaryreport"), FixListeners.missingListeners(tree));
    }

    private static HashTree plan() {
        HashTree tree = new ListedHashTree();
        tree.add(new TestPlan("Test Plan"));
        return tree;
    }

    private static ResultCollector listener(String guiClass) {
        ResultCollector listener = new ResultCollector();
        listener.setProperty(ConfigTestElement.GUI_CLASS, guiClass);
        return listener;
    }
}
