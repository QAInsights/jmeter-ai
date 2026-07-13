package org.qainsights.jmeter.ai.agent.jmeter;

import java.util.ArrayList;
import java.util.List;

import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;

import org.apache.jmeter.gui.UndoHistory;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.threads.ThreadGroup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves {@link JMeterTreeMutator}'s mutations fire the same standard
 * {@code javax.swing.event.TreeModelEvent}s that JMeter's own GUI actions
 * fire - the mechanism {@code org.apache.jmeter.gui.UndoHistory} (registered
 * generically as a {@code TreeModelListener} on {@code GuiPackage}'s tree
 * model) relies on to record undo steps. Uses a real, headless-safe
 * {@link JMeterTreeModel} (no {@code GuiPackage}/live GUI needed) with a
 * plain recording listener, so these tests are independent of whether
 * {@code undo.history.size} happens to be enabled in the test JVM.
 */
class JMeterTreeMutatorUndoIntegrationTest {

    /** Records which TreeModelListener callback fired for each mutation. */
    private static final class RecordingListener implements TreeModelListener {
        final List<String> events = new ArrayList<>();

        @Override
        public void treeNodesChanged(TreeModelEvent e) {
            events.add("changed");
        }

        @Override
        public void treeNodesInserted(TreeModelEvent e) {
            events.add("inserted");
        }

        @Override
        public void treeNodesRemoved(TreeModelEvent e) {
            events.add("removed");
        }

        @Override
        public void treeStructureChanged(TreeModelEvent e) {
            events.add("structureChanged");
        }
    }

    private final JMeterTreeMutator mutator = new JMeterTreeMutator(EdtExecutor.direct());

    @Test
    void updateProperty_firesTreeNodesChanged() {
        JMeterTreeModel model = new JMeterTreeModel();
        JMeterTreeNode root = (JMeterTreeNode) model.getRoot();
        RecordingListener listener = new RecordingListener();
        model.addTreeModelListener(listener);

        assertTrue(mutator.updateProperty(model, root, "TestPlan.comments", "hi"));

        assertTrue(listener.events.contains("changed"));
    }

    @Test
    void deleteElement_firesTreeNodesRemoved() {
        JMeterTreeModel model = new JMeterTreeModel();
        JMeterTreeNode root = (JMeterTreeNode) model.getRoot();
        JMeterTreeNode child = new JMeterTreeNode(new ThreadGroup(), null);
        model.insertNodeInto(child, root, 0);

        RecordingListener listener = new RecordingListener();
        model.addTreeModelListener(listener);

        assertTrue(mutator.deleteElement(model, child, false));

        assertTrue(listener.events.contains("removed"));
    }

    @Test
    void moveElement_firesRemovedThenInserted_asTwoSeparateSteps() {
        JMeterTreeModel model = new JMeterTreeModel();
        JMeterTreeNode root = (JMeterTreeNode) model.getRoot();
        JMeterTreeNode parentA = new JMeterTreeNode(new ThreadGroup(), null);
        JMeterTreeNode parentB = new JMeterTreeNode(new ThreadGroup(), null);
        model.insertNodeInto(parentA, root, 0);
        model.insertNodeInto(parentB, root, 1);
        JMeterTreeNode child = new JMeterTreeNode(new ThreadGroup(), null);
        model.insertNodeInto(child, parentA, 0);

        RecordingListener listener = new RecordingListener();
        model.addTreeModelListener(listener);

        assertTrue(mutator.moveElement(model, child, parentB));

        // Documented caveat: a move is two undo-recordable steps (remove + insert),
        // not one atomic step - JMeter's own compound-transaction API for grouping
        // them (beginUndoTransaction/endUndoTransaction) is package-private to
        // org.apache.jmeter.gui, so our plugin can't call it.
        assertEquals(List.of("removed", "inserted"), listener.events);
    }

    @Test
    void renameElement_firesTreeNodesChanged() {
        JMeterTreeModel model = new JMeterTreeModel();
        JMeterTreeNode root = (JMeterTreeNode) model.getRoot();
        JMeterTreeNode child = new JMeterTreeNode(new ThreadGroup(), null);
        model.insertNodeInto(child, root, 0);

        RecordingListener listener = new RecordingListener();
        model.addTreeModelListener(listener);

        assertTrue(mutator.renameElement(model, child, "Renamed"));

        assertEquals("Renamed", child.getName());
        assertTrue(listener.events.contains("changed"));
    }

    @Test
    void duplicateElement_firesTreeNodesInserted() {
        JMeterTreeModel model = new JMeterTreeModel();
        JMeterTreeNode root = (JMeterTreeNode) model.getRoot();
        JMeterTreeNode child = new JMeterTreeNode(new ThreadGroup(), null);
        model.insertNodeInto(child, root, 0);

        RecordingListener listener = new RecordingListener();
        model.addTreeModelListener(listener);

        assertNotNull(mutator.duplicateElement(model, child));

        assertTrue(listener.events.contains("inserted"));
    }

    @Test
    void undoHistory_isDisabledByDefaultInStockJMeter() {
        // Documents JMeter's own default: undo.history.size defaults to 0, so
        // Undo/Redo is off out of the box for native GUI edits too, not just the
        // agent's. JMeterAgent nudges the user to opt in via that property.
        assertFalse(UndoHistory.isEnabled());
    }
}
