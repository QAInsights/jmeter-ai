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
