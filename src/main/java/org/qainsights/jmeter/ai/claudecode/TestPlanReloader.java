package org.qainsights.jmeter.ai.claudecode;

import org.apache.jmeter.exceptions.IllegalUserActionException;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.services.FileServer;
import org.apache.jorphan.collections.HashTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JTree;
import javax.swing.tree.TreePath;
import java.util.ArrayList;
import java.util.List;

/**
 * Swaps a freshly loaded test plan into JMeter's live GUI tree while preserving
 * the user's expanded/selected node state (matched by node name).
 * Must be called on the Swing EDT.
 */
final class TestPlanReloader {

    private static final Logger log = LoggerFactory.getLogger(TestPlanReloader.class);

    private TestPlanReloader() {
    }

    /**
     * Returns true when {@link FileServer} has open files (a test is running).
     * {@code GuiPackage.clearTestPlan()} would crash in that state, wiping the
     * UI without reloading, so callers must skip the reload.
     */
    static boolean isFileServerBusy() {
        try {
            FileServer fileServer = FileServer.getFileServer();
            fileServer.setBasedir(fileServer.getBaseDir());
            return false;
        } catch (IllegalStateException ex) {
            return true;
        }
    }

    static List<List<String>> captureExpandedNodePaths(JTree jTree) {
        List<List<String>> expandedNodePaths = new ArrayList<>();
        int rowCount = jTree.getRowCount();
        for (int i = 0; i < rowCount; i++) {
            TreePath path = jTree.getPathForRow(i);
            if (!jTree.isExpanded(path)) {
                continue;
            }
            List<String> nodeNames = new ArrayList<>();
            for (Object component : path.getPath()) {
                if (component instanceof JMeterTreeNode) {
                    nodeNames.add(((JMeterTreeNode) component).getName());
                }
            }
            expandedNodePaths.add(nodeNames);
        }
        return expandedNodePaths;
    }

    static String captureSelectedNodeName(JTree jTree) {
        TreePath selectedPath = jTree.getSelectionPath();
        if (selectedPath == null) {
            return null;
        }
        Object lastComponent = selectedPath.getLastPathComponent();
        if (lastComponent instanceof JMeterTreeNode) {
            return ((JMeterTreeNode) lastComponent).getName();
        }
        return null;
    }

    /**
     * Clears the open plan, adds {@code tree} in its place, restores the file
     * path and re-applies the previously captured expansion/selection state.
     */
    static void applyReloadedTree(GuiPackage guiPackage, JTree jTree, HashTree tree, String filePath)
            throws IllegalUserActionException {
        List<List<String>> expandedNodePaths = captureExpandedNodePaths(jTree);
        String selectedNodeName = captureSelectedNodeName(jTree);

        guiPackage.clearTestPlan();
        guiPackage.addSubTree(tree);
        guiPackage.setTestPlanFile(filePath);

        JMeterTreeModel newTreeModel = guiPackage.getTreeModel();
        JMeterTreeNode root = (JMeterTreeNode) newTreeModel.getRoot();
        newTreeModel.nodeStructureChanged(root);

        for (List<String> nodeNames : expandedNodePaths) {
            restoreExpandedPath(jTree, newTreeModel, nodeNames);
        }
        if (selectedNodeName != null) {
            restoreSelection(jTree, newTreeModel, selectedNodeName);
        }

        guiPackage.getMainFrame().repaint();
        log.info("Test plan silently reloaded with tree state preserved");
    }

    /**
     * Restores an expanded path by matching node names from root to leaf.
     * Expands the deepest matching prefix when part of the path no longer exists.
     */
    static void restoreExpandedPath(JTree jTree, JMeterTreeModel treeModel, List<String> nodeNames) {
        JMeterTreeNode current = (JMeterTreeNode) treeModel.getRoot();
        List<Object> pathComponents = new ArrayList<>();
        pathComponents.add(current);

        for (int i = 1; i < nodeNames.size(); i++) {
            String targetName = nodeNames.get(i);
            boolean found = false;
            for (int j = 0; j < current.getChildCount(); j++) {
                JMeterTreeNode child = (JMeterTreeNode) current.getChildAt(j);
                if (child.getName().equals(targetName)) {
                    pathComponents.add(child);
                    current = child;
                    found = true;
                    break;
                }
            }
            if (!found) {
                break;
            }
        }

        if (pathComponents.size() > 1) {
            jTree.expandPath(new TreePath(pathComponents.toArray()));
        }
    }

    /**
     * Restores the selected node by name; leaves selection untouched if no node matches.
     */
    static void restoreSelection(JTree jTree, JMeterTreeModel treeModel, String nodeName) {
        JMeterTreeNode root = (JMeterTreeNode) treeModel.getRoot();
        JMeterTreeNode target = findNodeByName(root, nodeName);
        if (target != null) {
            TreePath path = new TreePath(target.getPath());
            jTree.setSelectionPath(path);
            jTree.scrollPathToVisible(path);
        }
    }

    /**
     * Finds a node by name in the tree (depth-first).
     */
    static JMeterTreeNode findNodeByName(JMeterTreeNode parent, String name) {
        if (parent.getName().equals(name)) {
            return parent;
        }
        for (int i = 0; i < parent.getChildCount(); i++) {
            JMeterTreeNode child = (JMeterTreeNode) parent.getChildAt(i);
            JMeterTreeNode result = findNodeByName(child, name);
            if (result != null) {
                return result;
            }
        }
        return null;
    }
}
