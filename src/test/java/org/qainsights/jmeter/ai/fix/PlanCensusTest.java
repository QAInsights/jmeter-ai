package org.qainsights.jmeter.ai.fix;

import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.config.Arguments;
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
    void flagsAddedOrChangedScriptsButAllowsUnchangedScripts() {
        String violation = "A script was added or changed. Make it pass can't verify scripts, so it doesn't change them.";
        assertTrue(withChild(script("vars.put('id', '1');"))
                .violations(withChild(script("prev.successful = true;"))).contains(violation));
        assertTrue(withChild(script("vars.put('id', '1');"))
                .violations(withChild(script("vars.put('id', '2');"))).contains(violation));
        assertTrue(withChild(script("vars.put('id', '1');"))
                .violations(withChild(script("vars.put('id', '1');"))).isEmpty());
    }

    @Test
    void flagsJMeterFunctionsInAnyElementProperty() {
        Arguments arguments = new Arguments();
        arguments.addArgument("payload", "${__groovy(prev.setSuccessful(true))}");

        assertTrue(plan(1, 1, true, null, false).violations(withChild(arguments))
                .contains("A script was added or changed. Make it pass can't verify scripts, so it doesn't change them."));
    }

    @Test
    void flagsAddedNonHttpSamplers() {
        assertTrue(withHttpSampler(false).violations(withHttpSampler(true))
                .contains("A non-HTTP request was added or changed."));
    }

    @Test
    void flagsRequestRepointedToUnconfirmedHost() {
        assertTrue(withHost("confirmed.example.test").violations(withHost("new.example.test"))
                .contains("A request now targets a host you did not confirm: new.example.test."));
    }

    @Test
    void flagsHostDriftInAbsoluteSamplerPath() {
        assertTrue(withAbsolutePath("https://confirmed.example.test/path")
                .violations(withAbsolutePath("https://new.example.test/path"))
                .contains("A request now targets a host you did not confirm: new.example.test."));
    }

    @Test
    void flagsUserDefinedHostVariableChanges() {
        assertTrue(withHostVariable("first.example.test").violations(withHostVariable("second.example.test"))
                .contains("A request now targets a host you did not confirm: second.example.test."));
    }

    @Test
    void flagsRampTimeAndDelayChanges() {
        assertTrue(withLoadSettings(1, 0).violations(withLoadSettings(2, 0))
                .contains("Thread Group load settings were changed."));
        assertTrue(withLoadSettings(1, 0).violations(withLoadSettings(1, 2))
                .contains("Thread Group load settings were changed."));
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

    private static PlanCensus withAbsolutePath(String path) {
        HashTree root = new ListedHashTree();
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setDomain("");
        sampler.setPath(path);
        root.add(new TestPlan("Test Plan")).add(sampler);
        return PlanCensus.capture(root);
    }

    private static PlanCensus withHttpSampler(boolean addNonHttpSampler) {
        HashTree root = new ListedHashTree();
        HashTree planTree = root.add(new TestPlan("Test Plan"));
        HTTPSamplerProxy httpSampler = new HTTPSamplerProxy();
        planTree.add(httpSampler);
        if (addNonHttpSampler) {
            planTree.add(new DebugSampler());
        }
        return PlanCensus.capture(root);
    }

    private static PlanCensus withHostVariable(String value) {
        TestPlan testPlan = new TestPlan("Test Plan");
        Arguments arguments = new Arguments();
        arguments.addArgument("HOST", value);
        HashTree root = new ListedHashTree();
        HashTree planTree = root.add(testPlan);
        planTree.add(arguments);
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setDomain("${HOST}");
        planTree.add(sampler);
        return PlanCensus.capture(root);
    }

    private static PlanCensus withLoadSettings(int rampTime, int delay) {
        TestPlan testPlan = new TestPlan("Test Plan");
        ThreadGroup group = new ThreadGroup();
        group.setNumThreads(1);
        group.setRampUp(rampTime);
        group.setDelay(delay);
        LoopController controller = new LoopController();
        controller.setLoops(1);
        controller.setFirst(true);
        controller.initialize();
        group.setSamplerController(controller);
        HashTree root = new ListedHashTree();
        root.add(testPlan).add(group);
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
