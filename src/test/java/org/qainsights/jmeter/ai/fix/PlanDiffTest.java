package org.qainsights.jmeter.ai.fix;

import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;

import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlanDiffTest {
    @Test
    void preservesTreeInsertionOrderInSnapshotsAndDiffs() {
        HashTree tree = plan();
        HashTree children = tree.getTree(tree.list().iterator().next());
        children.add(sampler("First", "/one"));
        children.add(sampler("Second", "/two"));

        Map<String, PlanDiff.ElementSnapshot> snapshot = PlanDiff.snapshot(tree);

        assertEquals(List.of("Test Plan", "Test Plan/First", "Test Plan/Second"),
                List.copyOf(snapshot.keySet()));

        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("first", "1");
        properties.put("second", "2");
        PlanDiff.ElementSnapshot element = new PlanDiff.ElementSnapshot("Sampler", properties);
        assertEquals(List.of("first", "second"), List.copyOf(element.properties().keySet()));
    }

    @Test
    void reportsAddedAndModifiedElements() {
        HashTree beforeTree = plan();
        HashTree beforeChildren = beforeTree.getTree(beforeTree.list().iterator().next());
        HTTPSamplerProxy original = sampler("Catalog", "/old");
        beforeChildren.add(original);

        HashTree afterTree = plan();
        HashTree afterChildren = afterTree.getTree(afterTree.list().iterator().next());
        afterChildren.add(sampler("Catalog", "/new"));
        afterChildren.add(sampler("New Request", "/new"));

        PlanDiff diff = PlanDiff.between(PlanDiff.snapshot(beforeTree), PlanDiff.snapshot(afterTree));

        assertEquals(1, diff.added().size());
        assertTrue(diff.added().get(0).path().contains("New Request"));
        assertEquals(1, diff.modified().size());
        assertEquals("/old", diff.modified().get(0).beforeProperties().get("HTTPSampler.path"));
        assertEquals("/new", diff.modified().get(0).afterProperties().get("HTTPSampler.path"));
    }

    @Test
    void missingEnabledPropertyIsEquivalentToTrueInSnapshots() {
        HashTree beforeTree = plan();
        HTTPSamplerProxy original = sampler("Catalog", "/same");
        original.setProperty(TestElement.ENABLED, "true");
        beforeTree.getTree(beforeTree.list().iterator().next()).add(original);

        HashTree afterTree = plan();
        HTTPSamplerProxy withoutEnabled = sampler("Catalog", "/same");
        withoutEnabled.removeProperty(TestElement.ENABLED);
        afterTree.getTree(afterTree.list().iterator().next()).add(withoutEnabled);

        Map<String, PlanDiff.ElementSnapshot> before = PlanDiff.snapshot(beforeTree);
        Map<String, PlanDiff.ElementSnapshot> after = PlanDiff.snapshot(afterTree);
        PlanDiff diff = PlanDiff.between(before, after);

        assertEquals("true", before.get("Test Plan/Catalog").properties().get(TestElement.ENABLED));
        assertFalse(after.get("Test Plan/Catalog").properties().containsKey(TestElement.ENABLED));
        assertTrue(diff.modified().isEmpty());
    }

    @Test
    void explicitEnabledChangesStillAppearAsModified() {
        HashTree beforeTree = plan();
        HTTPSamplerProxy original = sampler("Catalog", "/same");
        original.setProperty(TestElement.ENABLED, "true");
        beforeTree.getTree(beforeTree.list().iterator().next()).add(original);

        HashTree afterTree = plan();
        HTTPSamplerProxy disabled = sampler("Catalog", "/same");
        disabled.setProperty(TestElement.ENABLED, "false");
        afterTree.getTree(afterTree.list().iterator().next()).add(disabled);

        PlanDiff diff = PlanDiff.between(PlanDiff.snapshot(beforeTree), PlanDiff.snapshot(afterTree));

        assertEquals(1, diff.modified().size());
        assertEquals("true", diff.modified().get(0).beforeProperties().get(TestElement.ENABLED));
        assertEquals("false", diff.modified().get(0).afterProperties().get(TestElement.ENABLED));
    }

    @Test
    void explicitEmptyEnabledPropertyRemainsDifferentFromTrue() {
        HashTree beforeTree = plan();
        HTTPSamplerProxy original = sampler("Catalog", "/same");
        original.setProperty(TestElement.ENABLED, "true");
        beforeTree.getTree(beforeTree.list().iterator().next()).add(original);

        HashTree afterTree = plan();
        HTTPSamplerProxy withEmptyEnabled = sampler("Catalog", "/same");
        withEmptyEnabled.setProperty(TestElement.ENABLED, "");
        afterTree.getTree(afterTree.list().iterator().next()).add(withEmptyEnabled);

        PlanDiff diff = PlanDiff.between(PlanDiff.snapshot(beforeTree), PlanDiff.snapshot(afterTree));

        assertEquals(1, diff.modified().size());
        assertEquals("", diff.modified().get(0).afterProperties().get(TestElement.ENABLED));
    }

    @Test
    void removedElementsKeepTheirSnapshotProperties() {
        HashTree beforeTree = plan();
        HashTree beforeChildren = beforeTree.getTree(beforeTree.list().iterator().next());
        beforeChildren.add(listener("Results", "org.apache.jmeter.visualizers.SummaryReport"));

        PlanDiff diff = PlanDiff.between(PlanDiff.snapshot(beforeTree), PlanDiff.snapshot(plan()));

        PlanDiff.ElementChange removed = diff.removed().get(0);
        assertEquals("org.apache.jmeter.visualizers.SummaryReport", removed.guiClass());
        assertEquals("org.apache.jmeter.visualizers.SummaryReport",
                removed.beforeProperties().get(TestElement.GUI_CLASS));
        assertTrue(removed.afterProperties().isEmpty());
    }

    @Test
    void masksSensitivePropertyValues() {
        HashTree tree = plan();
        HashTree children = tree.getTree(tree.list().iterator().next());
        HTTPSamplerProxy sampler = sampler("Login", "/login");
        sampler.setProperty("password", "do-not-show");
        children.add(sampler);

        Map<String, PlanDiff.ElementSnapshot> snapshot = PlanDiff.snapshot(tree);
        PlanDiff.ElementSnapshot element = snapshot.get("Test Plan/Login");

        assertNotNull(element);
        assertEquals("[REDACTED]", element.properties().get("password"));
        assertFalse(element.properties().containsValue("do-not-show"));
    }

    @Test
    void identifiesResultCollectorsAndAssertionsByGuiClass() {
        HashTree tree = plan();
        HashTree children = tree.getTree(tree.list().iterator().next());
        children.add(listener("Make it pass - View Results Tree",
                "org.apache.jmeter.visualizers.ViewResultsFullVisualizer"));
        children.add(listener("Make it pass - Summary Report",
                "org.apache.jmeter.visualizers.SummaryReport"));
        ResponseAssertion assertion = new ResponseAssertion();
        assertion.setName("Make it pass: page check");
        assertion.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.assertions.gui.AssertionGui");
        children.add(assertion);

        PlanDiff diff = PlanDiff.between(Map.of(), PlanDiff.snapshot(tree));

        List<PlanDiff.ElementChange> listeners = diff.added().stream()
                .filter(PlanDiff.ElementChange::isResultListener).toList();
        List<PlanDiff.ElementChange> assertions = diff.added().stream()
                .filter(PlanDiff.ElementChange::isResponseAssertion).toList();
        assertEquals(2, listeners.size());
        assertEquals(ResultCollector.class.getName(), listeners.get(0).className());
        assertEquals("org.apache.jmeter.visualizers.ViewResultsFullVisualizer", listeners.get(0).guiClass());
        assertEquals("org.apache.jmeter.visualizers.SummaryReport", listeners.get(1).guiClass());
        assertEquals(1, assertions.size());
        assertEquals("org.apache.jmeter.assertions.gui.AssertionGui", assertions.get(0).guiClass());
    }

    private static HashTree plan() {
        HashTree tree = new ListedHashTree();
        tree.add(new TestPlan("Test Plan"));
        return tree;
    }

    private static HTTPSamplerProxy sampler(String name, String path) {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setName(name);
        sampler.setPath(path);
        return sampler;
    }

    private static ResultCollector listener(String name, String guiClass) {
        ResultCollector listener = new ResultCollector();
        listener.setName(name);
        listener.setProperty(TestElement.GUI_CLASS, guiClass);
        return listener;
    }
}
