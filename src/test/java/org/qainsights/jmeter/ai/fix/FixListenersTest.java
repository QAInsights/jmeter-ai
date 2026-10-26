package org.qainsights.jmeter.ai.fix;

import java.util.List;

import org.apache.jmeter.assertions.AssertionResult;
import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.JMeterGUIComponent;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.samplers.Clearable;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.visualizers.Visualizer;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FixListenersTest {
    @Test
    void requestsBothListenersWhenPlanHasNone() {
        assertEquals(List.of("viewresultstree", "summaryreport"),
                FixListeners.missingListeners(plan()));
    }

    @Test
    void recognizesListenersAnywhereInTheTree() {
        HashTree tree = plan();
        HashTree testPlan = tree.getTree(tree.list().iterator().next());
        ResultCollector viewResults = listener("org.apache.jmeter.visualizers.ViewResultsFullVisualizer");
        testPlan.add(viewResults);
        ThreadGroup group = new ThreadGroup();
        testPlan.add(group).add(listener("org.apache.jmeter.visualizers.StatVisualizer"));

        assertTrue(FixListeners.missingListeners(tree).isEmpty());
    }

    @Test
    void onlyVisualizerGuiClassesAreEligibleForDelivery() {
        ResultCollector visualizer = listener("org.apache.jmeter.visualizers.ViewResultsFullVisualizer");
        ResultCollector nonVisualizer = listener("org.apache.jmeter.config.gui.SimpleConfigGui");
        ResultCollector empty = listener("");

        assertTrue(FixListeners.isVisualizerElement(visualizer));
        assertFalse(FixListeners.isVisualizerElement(nonVisualizer));
        assertFalse(FixListeners.isVisualizerElement(empty));
    }

    @Test
    void deliveryClearsOnlyMakeItPassListeners() {
        HashTree tree = plan();
        HashTree planChildren = tree.getTree(tree.list().iterator().next());
        ResultCollector ownedListener = listener("org.apache.jmeter.visualizers.ViewResultsFullVisualizer");
        ownedListener.setName("Make it pass - View Results Tree");
        ResultCollector userListener = listener("org.apache.jmeter.visualizers.ViewResultsFullVisualizer");
        userListener.setName("My View Results");
        planChildren.add(ownedListener);
        planChildren.add(userListener);

        JMeterGUIComponent ownedGui = mock(JMeterGUIComponent.class,
                withSettings().extraInterfaces(Visualizer.class, Clearable.class));
        JMeterGUIComponent userGui = mock(JMeterGUIComponent.class,
                withSettings().extraInterfaces(Visualizer.class, Clearable.class));
        GuiPackage gui = mock(GuiPackage.class);
        when(gui.getGui(ownedListener)).thenReturn(ownedGui);
        when(gui.getGui(userListener)).thenReturn(userGui);
        SampleResult sample = new SampleResult();

        FixListeners.deliver(tree, gui, List.of(sample));

        verify((Clearable) ownedGui).clearData();
        verify((Clearable) userGui, never()).clearData();
        verify((Visualizer) ownedGui).add(sample);
        verify((Visualizer) userGui).add(sample);
    }

    @Test
    void marksSoftFailuresAsRedAssertionsBeforeDelivery() throws Exception {
        HTTPSampleResult result = new HTTPSampleResult();
        result.setSampleLabel("Catalog");
        result.setSuccessful(true);
        result.setResponseCode("200");
        result.setContentType("text/html");
        result.setResponseData("<title>Not Found</title>", "UTF-8");

        FixListeners.markSoftFailure(result);

        assertFalse(result.isSuccessful());
        AssertionResult assertion = result.getAssertionResults()[0];
        assertEquals("Make it pass: soft failure", assertion.getName());
        assertTrue(assertion.isFailure());
        assertTrue(assertion.getFailureMessage().contains("Not Found"));
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
