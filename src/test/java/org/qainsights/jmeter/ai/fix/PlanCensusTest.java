package org.qainsights.jmeter.ai.fix;

import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.extractor.JSR223PostProcessor;
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

    @Test
    void flagsNewIgnoreStatusAssertion() {
        ResponseAssertion assertion = new ResponseAssertion();
        assertion.setAssumeSuccess(true);
        assertTrue(plan(1, 1, true, null, false).violations(withChild(assertion))
                .contains("A new element forces requests to pass."));
    }

    @Test
    void flagsNewScriptThatForcesSuccessButAllowsExtractorScript() {
        JSR223PostProcessor forced = script("prev.setSuccessful(true);");
        assertTrue(plan(1, 1, true, null, false).violations(withChild(forced))
                .contains("A new element forces requests to pass."));

        JSR223PostProcessor extractor = script("vars.put('id', prev.getResponseDataAsString());");
        assertTrue(plan(1, 1, true, null, false).violations(withChild(extractor)).isEmpty());
    }

    @Test
    void flagsRequestRepointedToUnconfirmedHost() {
        assertTrue(withHost("confirmed.example.test").violations(withHost("new.example.test"))
                .contains("A request now targets a host you did not confirm: new.example.test."));
    }

    private static PlanCensus withChild(org.apache.jmeter.testelement.TestElement child) {
        HashTree root = new ListedHashTree();
        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setNumThreads(1);
        LoopController controller = new LoopController();
        controller.setLoops(1);
        controller.setContinueForever(false);
        controller.setFirst(true);
        controller.initialize();
        threadGroup.setSamplerController(controller);
        HashTree group = root.add(new TestPlan("Test Plan")).add(threadGroup);
        group.add(new DebugSampler()).add(child);
        return PlanCensus.capture(root);
    }

    private static JSR223PostProcessor script(String source) {
        JSR223PostProcessor processor = new JSR223PostProcessor();
        processor.setProperty("script", source);
        return processor;
    }

    private static PlanCensus withHost(String host) {
        HashTree root = new ListedHashTree();
        ThreadGroup threadGroup = new ThreadGroup();
        threadGroup.setNumThreads(1);
        LoopController controller = new LoopController();
        controller.setLoops(1);
        controller.setContinueForever(false);
        controller.setFirst(true);
        controller.initialize();
        threadGroup.setSamplerController(controller);
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setDomain(host);
        root.add(new TestPlan("Test Plan")).add(threadGroup).add(sampler);
        return PlanCensus.capture(root);
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
