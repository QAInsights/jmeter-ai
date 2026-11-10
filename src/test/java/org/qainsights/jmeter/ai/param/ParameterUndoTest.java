package org.qainsights.jmeter.ai.param;

import org.apache.jmeter.gui.UndoHistory;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import javax.swing.undo.UndoManager;
import javax.swing.undo.UndoableEdit;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ParameterUndoTest {
    @Test
    void featherWandUndoRestoresPropertiesAndRemovesAddedCsvAfterLaterTreeEvents() throws Exception {
        JMeterTreeModel model = model();
        JMeterTreeNode plan = (JMeterTreeNode) ((JMeterTreeNode) model.getRoot()).getChildAt(0);
        JMeterTreeNode group = model.addComponent(group(), plan);
        HTTPSamplerProxy http = sampler();
        JMeterTreeNode scope = model.addComponent(http, group);
        TestElement before = (TestElement) http.clone();
        ParameterPlacement.apply(model, scope, null, "data.csv", ",", true, false,
                "shareMode.all", ParameterScanner.scan(scope));
        JMeterTreeNode csv = (JMeterTreeNode) group.getChildAt(0);
        model.nodeChanged(csv);
        model.nodeStructureChanged(group);
        assertTrue(ParameterPlacement.undoLast(model).startsWith("Restored"));
        assertEquals(1, group.getChildCount());
        assertSame(scope, group.getChildAt(0));
        assertNull(csv.getParent());
        assertEquals(before.getProperty("HTTPsampler.Arguments"),
                http.getProperty("HTTPsampler.Arguments"));
        assertEquals("alice", http.getArguments().getArgument(0).getValue());
        assertTrue(ParameterPlacement.undoLast(model).startsWith("Nothing to undo"));
    }

    @Test
    void featherWandUndoRestoresEveryReusedConfigPropertyAndRejectsOtherPlans() throws Exception {
        JMeterTreeModel model = model();
        JMeterTreeNode plan = (JMeterTreeNode) ((JMeterTreeNode) model.getRoot()).getChildAt(0);
        JMeterTreeNode group = model.addComponent(group(), plan);
        org.apache.jmeter.config.CSVDataSet csv = new org.apache.jmeter.config.CSVDataSet();
        csv.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.testbeans.gui.TestBeanGUI");
        csv.setProperty("filename", "data.csv");
        csv.setProperty("variableNames", "old");
        csv.setProperty("quotedData", false);
        csv.setProperty("ignoreFirstLine", false);
        model.addComponent(csv, group);
        HTTPSamplerProxy http = sampler();
        JMeterTreeNode scope = model.addComponent(http, group);
        ParameterPlacement.apply(model, scope, csv, "data.csv", ",", true, false,
                "shareMode.all", ParameterScanner.scan(scope));
        assertEquals("old,user", csv.getPropertyAsString("variableNames"));
        assertTrue(ParameterPlacement.undoLast(model()).startsWith("Nothing to undo"));
        assertTrue(ParameterPlacement.undoLast(model).startsWith("Restored"));
        assertEquals("old", csv.getPropertyAsString("variableNames"));
        assertFalse(csv.getPropertyAsBoolean("quotedData"));
        assertFalse(csv.getPropertyAsBoolean("ignoreFirstLine"));
        assertEquals("alice", http.getArguments().getArgument(0).getValue());
        assertEquals(2, group.getChildCount());
    }

    private static JMeterTreeModel model() {
        if (JMeterUtils.getJMeterProperties() == null) {
            JMeterUtils.loadJMeterProperties("nonexistent.properties");
        }
        return new JMeterTreeModel();
    }

    private static ThreadGroup group() {
        ThreadGroup group = new ThreadGroup();
        group.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.threads.gui.ThreadGroupGui");
        return group;
    }

    private static HTTPSamplerProxy sampler() {
        HTTPSamplerProxy http = new HTTPSamplerProxy();
        http.addArgument("user", "alice");
        http.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui");
        return http;
    }

    @Test
    void allPlacementTreeEventsFormOneUndoHistoryEntry() throws Exception {
        if (JMeterUtils.getJMeterProperties() == null) {
            JMeterUtils.loadJMeterProperties("nonexistent.properties");
        }
        JMeterTreeModel model = new JMeterTreeModel();
        JMeterTreeNode plan = (JMeterTreeNode) ((JMeterTreeNode) model.getRoot()).getChildAt(0);
        ThreadGroup tg = new ThreadGroup();
        tg.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.threads.gui.ThreadGroupGui");
        JMeterTreeNode group = model.addComponent(tg, plan);
        HTTPSamplerProxy http = new HTTPSamplerProxy();
        http.addArgument("user", "alice");
        http.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui");
        JMeterTreeNode scope = model.addComponent(http, group);
        try (MockedStatic<UndoHistory> enabled = mockStatic(UndoHistory.class, CALLS_REAL_METHODS)) {
            enabled.when(UndoHistory::isEnabled).thenReturn(true);
            UndoHistory history = new UndoHistory();
            UndoManager manager = spy(new UndoManager());
            Field managerField = UndoHistory.class.getDeclaredField("manager");
            managerField.setAccessible(true);
            managerField.set(history, manager);
            history.add(model, "before");
            model.addTreeModelListener(history);
            Method begin = UndoHistory.class.getDeclaredMethod("beginUndoTransaction");
            Method end = UndoHistory.class.getDeclaredMethod("endUndoTransaction");
            begin.setAccessible(true);
            end.setAccessible(true);
            begin.invoke(history);
            ParameterPlacement.apply(model, scope, null, "data.csv", ",", true, false,
                    "shareMode.all", ParameterScanner.scan(scope));
            assertFalse(history.canUndo());
            end.invoke(history);
            assertTrue(history.canUndo());
            verify(manager, times(1)).addEdit(any(UndoableEdit.class));
            assertEquals("${user}", http.getArguments().getArgument(0).getValue());
            assertEquals(2, group.getChildCount());
        }
    }
}
