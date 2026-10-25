package org.qainsights.jmeter.ai.record;

import java.io.File;
import java.nio.file.Path;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.exceptions.IllegalUserActionException;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.config.gui.HttpDefaultsGui;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBaseSchema;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.qainsights.jmeter.ai.agent.jmeter.ElementSaver;
import org.qainsights.jmeter.ai.agent.jmeter.HttpDefaultsSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class RecordedPlanRoundTripTest {
    @TempDir
    Path directory;

    @BeforeAll
    static void initJMeterProperties() throws Exception {
        if (JMeterUtils.getJMeterProperties() == null) {
            JMeterUtils.loadJMeterProperties("nonexistent.properties");
        }
        SaveService.loadProperties();
    }

    @Test
    void missingArgumentsReproducesGuiFailureAndIsRejectedByValidation() {
        ConfigTestElement defaults = new ConfigTestElement();
        defaults.setName("HTTP Request Defaults");
        defaults.setProperty(TestElement.TEST_CLASS, ConfigTestElement.class.getName());
        defaults.setProperty(TestElement.GUI_CLASS, HttpDefaultsGui.class.getName());
        HashTree tree = new ListedHashTree();
        tree.add(defaults);

        assertNull(defaults.getPropertyOrNull(HTTPSamplerBaseSchema.INSTANCE.getArguments()));
        RuntimeException guiFailure = assertThrows(RuntimeException.class,
                () -> new HttpDefaultsGui().configure(defaults));
        String failureText = guiFailure.toString();
        for (Throwable cause = guiFailure.getCause(); cause != null; cause = cause.getCause()) {
            failureText += "\n" + cause;
        }
        assertTrue(failureText.contains("HTTPsampler.Arguments"), failureText);
        assertThrows(IllegalUserActionException.class, () -> HttpDefaultsSupport.validateTree(tree));
    }

    @Test
    void recordingScaffoldSaveServiceRoundTripKeepsHttpDefaultsConfigurable() throws Exception {
        JMeterTreeModel model = recordingModel();
        ConfigTestElement liveDefaults = findHttpDefaults(model.getTestPlan());
        assertNotNull(liveDefaults);
        assertNotNull(liveDefaults.getPropertyOrNull(HTTPSamplerBaseSchema.INSTANCE.getArguments()));

        File saved = directory.resolve("recorded-plan.jmx").toFile();
        ElementSaver.writeTree(model.getTestPlan(), saved);
        ConfigTestElement reopenedDefaults = findHttpDefaults(SaveService.loadTree(saved));

        assertNotNull(reopenedDefaults);
        assertNotNull(reopenedDefaults.getPropertyOrNull(HTTPSamplerBaseSchema.INSTANCE.getArguments()));
        assertDoesNotThrow(() -> new HttpDefaultsGui().configure(reopenedDefaults));
    }

    @Test
    void saverRepairsLegacyRecordedDefaultsWithoutMutatingTheLiveTree() throws Exception {
        JMeterTreeModel model = recordingModel();
        ConfigTestElement originalDefaults = findHttpDefaults(model.getTestPlan());
        originalDefaults.removeProperty(HTTPSamplerBaseSchema.INSTANCE.getArguments().getName());
        File saved = directory.resolve("repaired-plan.jmx").toFile();

        ElementSaver.writeTree(model.getTestPlan(), saved);

        assertNull(originalDefaults.getPropertyOrNull(HTTPSamplerBaseSchema.INSTANCE.getArguments()));
        ConfigTestElement reopenedDefaults = findHttpDefaults(SaveService.loadTree(saved));
        assertNotNull(reopenedDefaults);
        assertNotNull(reopenedDefaults.getPropertyOrNull(HTTPSamplerBaseSchema.INSTANCE.getArguments()));
        assertDoesNotThrow(() -> new HttpDefaultsGui().configure(reopenedDefaults));
    }

    private static JMeterTreeModel recordingModel() {
        JMeterTreeModel model = new JMeterTreeModel();
        RecordingScaffold scaffold = RecordingScaffold.createIn(model, "https://petstore.test/catalog");
        JMeterTreeNode step = scaffold.addBusinessStep("Open catalog");
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setName("Open catalog");
        sampler.setPath("/catalog");
        sampler.setProperty(TestElement.TEST_CLASS, HTTPSamplerProxy.class.getName());
        sampler.setProperty(TestElement.GUI_CLASS,
                "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui");
        model.insertNodeInto(new JMeterTreeNode(sampler, model), step, 0);
        return model;
    }

    private static ConfigTestElement findHttpDefaults(HashTree tree) {
        for (Object key : tree.list()) {
            TestElement candidate = key instanceof JMeterTreeNode node
                    ? node.getTestElement() : key instanceof TestElement element ? element : null;
            if (candidate instanceof ConfigTestElement element
                    && HttpDefaultsGui.class.getName().equals(element.getPropertyAsString(TestElement.GUI_CLASS))) {
                return element;
            }
            HashTree children = tree.getTree(key);
            if (children != null) {
                ConfigTestElement found = findHttpDefaults(children);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
