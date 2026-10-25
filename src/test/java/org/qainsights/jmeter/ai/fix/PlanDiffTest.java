package org.qainsights.jmeter.ai.fix;

import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;

import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
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
}
