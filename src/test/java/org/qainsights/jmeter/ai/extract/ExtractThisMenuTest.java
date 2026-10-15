package org.qainsights.jmeter.ai.extract;

import org.apache.jmeter.samplers.SampleResult;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ExtractThisMenuTest {

    @Test
    void subResultSelectionKeepsTheTopLevelResult() {
        SampleResult top = new SampleResult();
        SampleResult sub = new SampleResult();
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("root");
        DefaultMutableTreeNode topNode = new DefaultMutableTreeNode(top);
        DefaultMutableTreeNode subNode = new DefaultMutableTreeNode(sub);
        root.add(topNode);
        topNode.add(subNode);
        JTree tree = new JTree(root);
        JPanel holder = new JPanel();
        holder.add(new JPanel());
        holder.add(tree);
        assertSame(tree, ExtractThisMenu.findTree(holder));

        tree.setSelectionPath(new TreePath(subNode.getPath()));
        SampleResult[] picked = ExtractThisMenu.selectedResults(tree);
        assertSame(top, picked[0]);
        assertSame(sub, picked[1]);

        tree.setSelectionPath(new TreePath(topNode.getPath()));
        picked = ExtractThisMenu.selectedResults(tree);
        assertSame(top, picked[0]);
        assertSame(top, picked[1]);
    }

    @Test
    void noSelectionMeansNoResults() {
        assertNull(ExtractThisMenu.selectedResults(new JTree(new DefaultMutableTreeNode("root"))));
        assertNull(ExtractThisMenu.selectedResults(null));
    }
}
