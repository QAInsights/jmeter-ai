package org.qainsights.jmeter.ai.agent.jmeter;

import java.util.Enumeration;

import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;

/**
 * Deep-clones a {@link JMeterTreeNode} subtree - its {@link TestElement} and
 * every descendant's - mirroring JMeter's own {@code Copy}/{@code Duplicate}
 * menu commands ({@code org.apache.jmeter.gui.action.Copy#cloneTreeNode}).
 * The clone is detached (no parent, not yet inserted into any model); callers
 * are responsible for inserting it via {@code JMeterTreeModel} so the correct
 * tree-model events fire.
 */
final class TreeNodeCloner {

    private TreeNodeCloner() {
    }

    /** Deep-clones {@code node} and all of its descendants. */
    static JMeterTreeNode cloneSubtree(JMeterTreeNode node) {
        JMeterTreeNode clone = (JMeterTreeNode) node.clone();
        clone.setUserObject(((TestElement) node.getUserObject()).clone());
        cloneChildren(clone, node);
        return clone;
    }

    @SuppressWarnings("JdkObsolete")
    private static void cloneChildren(JMeterTreeNode to, JMeterTreeNode from) {
        Enumeration<?> children = from.children();
        while (children.hasMoreElements()) {
            JMeterTreeNode child = (JMeterTreeNode) children.nextElement();
            JMeterTreeNode childClone = (JMeterTreeNode) child.clone();
            childClone.setUserObject(((TestElement) child.getUserObject()).clone());
            to.add(childClone);
            cloneChildren((JMeterTreeNode) to.getLastChild(), child);
        }
    }
}
