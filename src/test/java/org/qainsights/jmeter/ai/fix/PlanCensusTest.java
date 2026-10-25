package org.qainsights.jmeter.ai.fix;

import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.sampler.DebugSampler;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlanCensusTest {
    @Test
    void flagsRemovedAndDisabledSamplers() {
        assertTrue(plan(2, 1, true, null, false).violations(plan(1, 1, true, null, false))
                .contains("One or more requests were removed."));
        assertTrue(plan(1, 1, true, null, false).violations(plan(1, 1, false, null, false))
                .contains("One or more requests were disabled."));
    }

    @Test
    void flagsChangedAssertionsAndThreadCounts() {
        assertTrue(plan(1, 1, true, "original", false)
                .violations(plan(1, 1, true, "changed", false))
                .contains("An assertion was removed or changed."));
        assertTrue(plan(1, 1, true, null, false).violations(plan(1, 2, true, null, false))
                .contains("Thread Group load settings were changed."));
    }

    @Test
    void allowsAddingHeaderManager() {
        assertTrue(plan(1, 1, true, null, false)
                .violations(plan(1, 1, true, null, true)).isEmpty());
    }

    private static PlanCensus plan(int samplers, int threads, boolean enabled, String assertionName,
                                   boolean headerManager) {
        TestPlan plan = new TestPlan("Test Plan");
        ThreadGroup group = new ThreadGroup();
        group.setName("Thread Group");
        group.setNumThreads(threads);
        LoopController controller = new LoopController();
        controller.setLoops(1);
        controller.setContinueForever(false);
        controller.setFirst(true);
        controller.initialize();
        group.setSamplerController(controller);

        HashTree root = new ListedHashTree();
        HashTree groupTree = root.add(plan).add(group);
        for (int i = 0; i < samplers; i++) {
            DebugSampler sampler = new DebugSampler();
            sampler.setName("Request " + i);
            sampler.setEnabled(enabled);
            HashTree samplerTree = groupTree.add(sampler);
            if (assertionName != null) {
                ResponseAssertion assertion = new ResponseAssertion();
                assertion.setName(assertionName);
                samplerTree.add(assertion);
            }
            if (headerManager) {
                samplerTree.add(new HeaderManager());
            }
        }
        return PlanCensus.capture(root);
    }
}
