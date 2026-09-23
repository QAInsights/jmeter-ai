package org.qainsights.jmeter.ai.claudecode;

import javax.swing.JTree;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.MainFrame;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.services.FileServer;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TestPlanReloader}, using a real {@link JTree} over a
 * {@link JMeterTreeNode} hierarchy (per {@code TreeActivityGlowControllerTest})
 * and a mocked {@link GuiPackage}/{@link JMeterTreeModel} so the clear/reload
 * path is exercised without a live JMeter GUI.
 */
class TestPlanReloaderTest {

    @TempDir
    File tempDir;

    @AfterEach
    void closeFileServer() throws IOException {
        FileServer.getFileServer().closeFiles();
    }

    private static JMeterTreeNode node(String name) {
        ConfigTestElement element = new ConfigTestElement();
        element.setName(name);
        return new JMeterTreeNode(element, null);
    }

    /** root -> Test Plan -> Thread Group -> (HTTP Request, Assertion); Test Plan -> Listener */
    private static JMeterTreeNode samplePlan() {
        JMeterTreeNode root = node("");
        JMeterTreeNode testPlan = node("Test Plan");
        JMeterTreeNode threadGroup = node("Thread Group");
        threadGroup.add(node("HTTP Request"));
        threadGroup.add(node("Assertion"));
        testPlan.add(threadGroup);
        testPlan.add(node("Listener"));
        root.add(testPlan);
        return root;
    }

    private static JTree collapsedTreeFor(JMeterTreeNode root) {
        JTree tree = new JTree(new DefaultTreeModel(root));
        tree.setRootVisible(true);
        for (int i = tree.getRowCount() - 1; i >= 0; i--) {
            tree.collapseRow(i);
        }
        return tree;
    }

    private static JMeterTreeModel modelWithRoot(JMeterTreeNode root) {
        JMeterTreeModel model = mock(JMeterTreeModel.class);
        when(model.getRoot()).thenReturn(root);
        return model;
    }

    private static JMeterTreeNode child(JMeterTreeNode parent, int index) {
        return (JMeterTreeNode) parent.getChildAt(index);
    }

    private static TreePath pathTo(JMeterTreeNode node) {
        return new TreePath(node.getPath());
    }

    // ---- findNodeByName ----

    @Test
    void findNodeByName_findsNestedNodeDepthFirst() {
        JMeterTreeNode root = samplePlan();

        JMeterTreeNode found = TestPlanReloader.findNodeByName(root, "Assertion");

        assertNotNull(found);
        assertEquals("Assertion", found.getName());
        assertEquals("Thread Group", ((JMeterTreeNode) found.getParent()).getName());
    }

    @Test
    void findNodeByName_returnsParentItselfWhenNameMatches() {
        JMeterTreeNode root = samplePlan();
        JMeterTreeNode testPlan = child(root, 0);

        assertSame(testPlan, TestPlanReloader.findNodeByName(testPlan, "Test Plan"));
    }

    @Test
    void findNodeByName_returnsNullForMissingName() {
        assertNull(TestPlanReloader.findNodeByName(samplePlan(), "Does Not Exist"));
    }

    // ---- capture ----

    @Test
    void captureExpandedNodePaths_recordsNamePathsOfExpandedRowsOnly() {
        JMeterTreeNode root = samplePlan();
        JTree tree = collapsedTreeFor(root);
        JMeterTreeNode testPlan = child(root, 0);
        JMeterTreeNode threadGroup = child(testPlan, 0);
        tree.expandPath(pathTo(testPlan));
        tree.expandPath(pathTo(threadGroup));

        List<List<String>> captured = TestPlanReloader.captureExpandedNodePaths(tree);

        assertEquals(3, captured.size(), "root, Test Plan and Thread Group are expanded");
        assertEquals(List.of(""), captured.get(0));
        assertEquals(List.of("", "Test Plan"), captured.get(1));
        assertEquals(List.of("", "Test Plan", "Thread Group"), captured.get(2));
    }

    @Test
    void captureSelectedNodeName_returnsNameOrNull() {
        JMeterTreeNode root = samplePlan();
        JTree tree = collapsedTreeFor(root);
        assertNull(TestPlanReloader.captureSelectedNodeName(tree));

        JMeterTreeNode listener = child(child(root, 0), 1);
        tree.setSelectionPath(pathTo(listener));

        assertEquals("Listener", TestPlanReloader.captureSelectedNodeName(tree));
    }

    // ---- restoreExpandedPath ----

    @Test
    void restoreExpandedPath_expandsMatchingPathInRebuiltTree() {
        JMeterTreeNode rebuilt = samplePlan();
        JTree tree = collapsedTreeFor(rebuilt);
        JMeterTreeNode threadGroup = child(child(rebuilt, 0), 0);
        assertFalse(tree.isExpanded(pathTo(threadGroup)));

        TestPlanReloader.restoreExpandedPath(tree, modelWithRoot(rebuilt),
                List.of("", "Test Plan", "Thread Group"));

        assertTrue(tree.isExpanded(pathTo(threadGroup)));
        assertTrue(tree.isExpanded(pathTo(child(rebuilt, 0))), "ancestors are expanded too");
    }

    @Test
    void restoreExpandedPath_removedNodeDegradesToDeepestMatchingPrefix() {
        JMeterTreeNode rebuilt = samplePlan();
        JTree tree = collapsedTreeFor(rebuilt);
        JMeterTreeNode testPlan = child(rebuilt, 0);

        assertDoesNotThrow(() -> TestPlanReloader.restoreExpandedPath(tree, modelWithRoot(rebuilt),
                List.of("", "Test Plan", "Renamed Thread Group", "HTTP Request")));

        assertTrue(tree.isExpanded(pathTo(testPlan)));
        assertFalse(tree.isExpanded(pathTo(child(testPlan, 0))));
    }

    @Test
    void restoreExpandedPath_rootOnlyOrUnmatchedPathDoesNotExpandAnything() {
        JMeterTreeNode rebuilt = samplePlan();
        JTree tree = collapsedTreeFor(rebuilt);
        JMeterTreeNode testPlan = child(rebuilt, 0);

        TestPlanReloader.restoreExpandedPath(tree, modelWithRoot(rebuilt), List.of(""));
        TestPlanReloader.restoreExpandedPath(tree, modelWithRoot(rebuilt), List.of("", "Gone"));

        assertFalse(tree.isExpanded(pathTo(testPlan)));
    }

    // ---- restoreSelection ----

    @Test
    void restoreSelection_selectsNodeByName() {
        JMeterTreeNode rebuilt = samplePlan();
        JTree tree = collapsedTreeFor(rebuilt);
        JMeterTreeNode assertion = child(child(child(rebuilt, 0), 0), 1);

        TestPlanReloader.restoreSelection(tree, modelWithRoot(rebuilt), "Assertion");

        assertEquals(pathTo(assertion), tree.getSelectionPath());
    }

    @Test
    void restoreSelection_missingNodeLeavesSelectionUntouched() {
        JMeterTreeNode rebuilt = samplePlan();
        JTree tree = collapsedTreeFor(rebuilt);
        JMeterTreeNode listener = child(child(rebuilt, 0), 1);
        tree.setSelectionPath(pathTo(listener));

        TestPlanReloader.restoreSelection(tree, modelWithRoot(rebuilt), "Removed Sampler");

        assertEquals(pathTo(listener), tree.getSelectionPath());
    }

    // ---- applyReloadedTree ----

    @Test
    void applyReloadedTree_clearsThenAddsAndRestoresFilePathAndTreeState() throws Exception {
        JMeterTreeNode rebuilt = samplePlan();
        JTree tree = collapsedTreeFor(rebuilt);
        JMeterTreeNode testPlan = child(rebuilt, 0);
        JMeterTreeNode threadGroup = child(testPlan, 0);
        JMeterTreeNode httpRequest = child(threadGroup, 0);
        tree.expandPath(pathTo(threadGroup));
        tree.setSelectionPath(pathTo(httpRequest));

        JMeterTreeModel model = modelWithRoot(rebuilt);
        MainFrame mainFrame = mock(MainFrame.class);
        GuiPackage gui = mock(GuiPackage.class);
        when(gui.getTreeModel()).thenReturn(model);
        when(gui.getMainFrame()).thenReturn(mainFrame);

        // Simulate what clearTestPlan() does to the tree view: everything collapses.
        doAnswer(inv -> {
            for (int i = tree.getRowCount() - 1; i >= 0; i--) {
                tree.collapseRow(i);
            }
            tree.clearSelection();
            return null;
        }).when(gui).clearTestPlan();

        HashTree loaded = new ListedHashTree();
        TestPlanReloader.applyReloadedTree(gui, tree, loaded, "/tmp/plan.jmx");

        InOrder inOrder = inOrder(gui, model, mainFrame);
        inOrder.verify(gui).clearTestPlan();
        inOrder.verify(gui).addSubTree(same(loaded));
        inOrder.verify(gui).setTestPlanFile("/tmp/plan.jmx");
        inOrder.verify(model).nodeStructureChanged(rebuilt);
        inOrder.verify(mainFrame).repaint();

        assertTrue(tree.isExpanded(pathTo(threadGroup)), "expansion restored after clear");
        assertEquals(pathTo(httpRequest), tree.getSelectionPath(), "selection restored after clear");
    }

    @Test
    void applyReloadedTree_noPriorSelectionDoesNotSelectAnything() throws Exception {
        JMeterTreeNode rebuilt = samplePlan();
        JTree tree = collapsedTreeFor(rebuilt);
        JMeterTreeModel model = modelWithRoot(rebuilt);
        MainFrame mainFrame = mock(MainFrame.class);
        GuiPackage gui = mock(GuiPackage.class);
        when(gui.getTreeModel()).thenReturn(model);
        when(gui.getMainFrame()).thenReturn(mainFrame);

        TestPlanReloader.applyReloadedTree(gui, tree, new ListedHashTree(), "/tmp/plan.jmx");

        assertNull(tree.getSelectionPath());
        verify(gui).clearTestPlan();
        verify(gui).addSubTree(any(HashTree.class));
    }

    // ---- FileServer-busy guard ----

    @Test
    void isFileServerBusy_falseWhenNoFilesOpen() {
        assertFalse(TestPlanReloader.isFileServerBusy());
    }

    @Test
    void isFileServerBusy_trueWhileFileServerHasOpenFiles_andFalseAfterClose() throws IOException {
        File csv = new File(tempDir, "data.csv");
        Files.writeString(csv.toPath(), "a,b\n1,2\n", StandardCharsets.UTF_8);
        FileServer fileServer = FileServer.getFileServer();
        fileServer.reserveFile(csv.getAbsolutePath());
        assertEquals("a,b", fileServer.readLine(csv.getAbsolutePath()));

        assertTrue(TestPlanReloader.isFileServerBusy(), "open FileServer files must block the reload");

        fileServer.closeFiles();
        assertFalse(TestPlanReloader.isFileServerBusy());
    }
}
