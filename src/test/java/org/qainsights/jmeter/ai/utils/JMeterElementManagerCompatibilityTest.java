package org.qainsights.jmeter.ai.utils;

import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.assertions.gui.AssertionGui;
import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.config.gui.ArgumentsPanel;
import org.apache.jmeter.control.GenericController;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.control.gui.LogicControllerGui;
import org.apache.jmeter.control.gui.LoopControlPanel;
import org.apache.jmeter.control.gui.TestPlanGui;
import org.apache.jmeter.extractor.JSR223PostProcessor;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.modifiers.JSR223PreProcessor;
import org.apache.jmeter.protocol.http.config.gui.HttpDefaultsGui;
import org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.testbeans.gui.TestBeanGUI;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.threads.gui.ThreadGroupGui;
import org.apache.jmeter.timers.ConstantTimer;
import org.apache.jmeter.timers.gui.ConstantTimerGui;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.visualizers.ViewResultsFullVisualizer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Placement matrix of {@link JMeterElementManager#isNodeCompatible}: which element
 * types may be added under which parent node, mirroring JMeter's own hierarchy rules.
 */
class JMeterElementManagerCompatibilityTest {

    private static final List<String> ALL_TYPES = List.of(
            "testplan", "threadgroup", "httpsampler", "httprequest", "loopcontroller", "constanttimer",
            "jsr223preprocessor", "regexextractor", "headermanager", "csvdataset", "viewresultstree",
            "aggregatereport", "responseassert", "jsonassertion");

    private static final List<String> STRUCTURAL_TYPES = List.of("testplan", "threadgroup");

    private static final List<String> EXECUTABLE_TYPES = List.of("httpsampler", "httprequest", "loopcontroller");

    @BeforeAll
    static void initJMeterProperties() {
        if (JMeterUtils.getJMeterProperties() == null) {
            JMeterUtils.loadJMeterProperties("nonexistent.properties");
        }
    }

    private static JMeterTreeNode node(TestElement element, Class<?> guiClass) {
        element.setProperty(TestElement.GUI_CLASS, guiClass.getName());
        return new JMeterTreeNode(element, null);
    }

    private static JMeterTreeNode testPlanNode() {
        return node(new TestPlan(), TestPlanGui.class);
    }

    private static JMeterTreeNode threadGroupNode() {
        return node(new ThreadGroup(), ThreadGroupGui.class);
    }

    private static JMeterTreeNode samplerNode() {
        return node(new HTTPSamplerProxy(), HttpTestSampleGui.class);
    }

    private static JMeterTreeNode loopControllerNode() {
        return node(new LoopController(), LoopControlPanel.class);
    }

    private static JMeterTreeNode simpleControllerNode() {
        return node(new GenericController(), LogicControllerGui.class);
    }

    private static Stream<Arguments> leafNodes() {
        return Stream.of(
                Arguments.of("timer", node(new ConstantTimer(), ConstantTimerGui.class)),
                Arguments.of("preprocessor", node(new JSR223PreProcessor(), TestBeanGUI.class)),
                Arguments.of("postprocessor", node(new JSR223PostProcessor(), TestBeanGUI.class)),
                Arguments.of("config", node(new ConfigTestElement(), HttpDefaultsGui.class)),
                Arguments.of("listener", node(new ResultCollector(), ViewResultsFullVisualizer.class)),
                Arguments.of("assertion", node(new ResponseAssertion(), AssertionGui.class)));
    }

    private static Stream<Arguments> leafNodesByElementType() {
        return leafNodes().flatMap(parent -> ALL_TYPES.stream()
                .map(type -> Arguments.of(parent.get()[0], parent.get()[1], type)));
    }

    private static Stream<String> allTypes() {
        return ALL_TYPES.stream();
    }

    private static Stream<String> nonStructuralTypes() {
        return ALL_TYPES.stream().filter(t -> !STRUCTURAL_TYPES.contains(t));
    }

    private static Stream<String> samplerChildTypes() {
        return ALL_TYPES.stream()
                .filter(t -> !STRUCTURAL_TYPES.contains(t))
                .filter(t -> !EXECUTABLE_TYPES.contains(t));
    }

    @ParameterizedTest(name = "test plan accepts {0}")
    @MethodSource("allTypes")
    void testPlanAcceptsEveryElementType(String elementType) {
        assertTrue(JMeterElementManager.isNodeCompatible(testPlanNode(), elementType));
    }

    @ParameterizedTest(name = "thread group accepts {0}")
    @MethodSource("nonStructuralTypes")
    void threadGroupAcceptsNonStructuralElements(String elementType) {
        assertTrue(JMeterElementManager.isNodeCompatible(threadGroupNode(), elementType));
    }

    @Test
    void threadGroupRejectsThreadGroupAndTestPlan() {
        assertFalse(JMeterElementManager.isNodeCompatible(threadGroupNode(), "threadgroup"));
        assertFalse(JMeterElementManager.isNodeCompatible(threadGroupNode(), "Thread Group"));
        assertFalse(JMeterElementManager.isNodeCompatible(threadGroupNode(), "testplan"));
    }

    @ParameterizedTest(name = "sampler accepts {0}")
    @MethodSource("samplerChildTypes")
    void samplerAcceptsAssertionsTimersProcessorsConfigAndListeners(String elementType) {
        assertTrue(JMeterElementManager.isNodeCompatible(samplerNode(), elementType));
    }

    @Test
    void samplerRejectsSamplersControllersThreadGroupsAndTestPlan() {
        assertFalse(JMeterElementManager.isNodeCompatible(samplerNode(), "httpsampler"));
        assertFalse(JMeterElementManager.isNodeCompatible(samplerNode(), "httprequest"));
        assertFalse(JMeterElementManager.isNodeCompatible(samplerNode(), "loopcontroller"));
        assertFalse(JMeterElementManager.isNodeCompatible(samplerNode(), "threadgroup"));
        assertFalse(JMeterElementManager.isNodeCompatible(samplerNode(), "testplan"));
    }

    @ParameterizedTest(name = "controller accepts {0}")
    @MethodSource("nonStructuralTypes")
    void controllersAcceptNonStructuralElements(String elementType) {
        assertTrue(JMeterElementManager.isNodeCompatible(loopControllerNode(), elementType));
        assertTrue(JMeterElementManager.isNodeCompatible(simpleControllerNode(), elementType));
    }

    @Test
    void controllersRejectThreadGroupAndTestPlan() {
        assertFalse(JMeterElementManager.isNodeCompatible(loopControllerNode(), "threadgroup"));
        assertFalse(JMeterElementManager.isNodeCompatible(loopControllerNode(), "testplan"));
        assertFalse(JMeterElementManager.isNodeCompatible(simpleControllerNode(), "threadgroup"));
        assertFalse(JMeterElementManager.isNodeCompatible(simpleControllerNode(), "testplan"));
    }

    @ParameterizedTest(name = "{0} node rejects {2}")
    @MethodSource("leafNodesByElementType")
    void leafNodesAcceptNothing(String category, JMeterTreeNode parent, String elementType) {
        assertFalse(JMeterElementManager.isNodeCompatible(parent, elementType),
                category + " node must not accept " + elementType);
    }

    @Test
    void unrecognisedParentIsRejectedConservatively() {
        JMeterTreeNode parent = node(new org.apache.jmeter.config.Arguments(), ArgumentsPanel.class);
        for (String type : ALL_TYPES) {
            assertFalse(JMeterElementManager.isNodeCompatible(parent, type), type);
        }
    }

    @Test
    void elementTypeIsNormalisedBeforeMatching() {
        assertTrue(JMeterElementManager.isNodeCompatible(samplerNode(), "Constant Timer"));
        assertTrue(JMeterElementManager.isNodeCompatible(samplerNode(), "HEADER-MANAGER"));
        assertFalse(JMeterElementManager.isNodeCompatible(samplerNode(), "HTTP Sampler"));
    }

    @Test
    void categoryIsDerivedFromGuiClassWhenModelClassNameIsGeneric() {
        JMeterTreeNode httpDefaults = node(new ConfigTestElement(), HttpDefaultsGui.class);
        assertEquals("ConfigTestElement", httpDefaults.getTestElement().getClass().getSimpleName());
        assertFalse(JMeterElementManager.isNodeCompatible(httpDefaults, "constanttimer"));

        JMeterTreeNode listener = node(new ResultCollector(), ViewResultsFullVisualizer.class);
        assertFalse(JMeterElementManager.isNodeCompatible(listener, "responseassert"));
    }
}
