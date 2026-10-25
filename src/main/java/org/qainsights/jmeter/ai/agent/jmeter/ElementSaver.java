package org.qainsights.jmeter.ai.agent.jmeter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jorphan.collections.HashTree;

/**
 * Seam over "persist the current test plan tree to a .jmx file" - the same
 * {@link SaveService#saveTree} JMeter's own Save/Save As actions use under
 * the hood (minus the {@code JFileChooser} dialog, since the agent supplies
 * the path directly). Kept behind an interface so the {@code save_plan} tool
 * logic can be unit-tested without a running JMeter GUI.
 */
@FunctionalInterface
public interface ElementSaver {

    /**
     * Saves the whole current test plan to {@code file}, overwriting it if it
     * already exists, and records it as the plan's associated file (as
     * JMeter's own Save action does), so the title bar and subsequent
     * argument-less saves pick it up.
     *
     * @return {@code true} if a live JMeter GUI with a non-empty test plan
     *         was available to save; {@code false} otherwise (nothing was
     *         written)
     * @throws IOException if writing the file failed
     */
    boolean save(File file) throws IOException;

    /**
     * Live saver: pulls the whole tree from {@link GuiPackage#getTreeModel()},
     * writes it via {@link SaveService#saveTree} and records the file path via
     * {@link GuiPackage#setTestPlanFile} - performed on the EDT, since it
     * reads live Swing tree-model state.
     */
    static ElementSaver live() {
        EdtExecutor edt = EdtExecutor.swing();
        return file -> {
            GuiPackage gui = GuiPackage.getInstance();
            if (gui == null) {
                return false;
            }
            JMeterTreeModel model = gui.getTreeModel();
            if (model == null) {
                return false;
            }
            AtomicReference<IOException> error = new AtomicReference<>();
            AtomicBoolean saved = new AtomicBoolean(false);
            edt.run(() -> {
                HashTree testPlanTree = model.getTestPlan();
                if (testPlanTree == null || testPlanTree.size() == 0) {
                    return;
                }
                try {
                    writeTree(testPlanTree, file);
                    gui.setTestPlanFile(file.getAbsolutePath());
                    gui.setDirty(false);
                    saved.set(true);
                } catch (IOException e) {
                    error.set(e);
                }
            });
            IOException failure = error.get();
            if (failure != null) {
                throw failure;
            }
            return saved.get();
        };
    }

    /** Writes a cloned tree after unwrapping any live Swing tree nodes. */
    public static void writeTree(HashTree testPlanTree, File file) throws IOException {
        HashTree serializableTree = (HashTree) testPlanTree.clone();
        unwrapTreeNodes(serializableTree);
        HttpDefaultsSupport.normalizeSerializedTree(serializableTree);
        try (OutputStream out = new FileOutputStream(file)) {
            SaveService.saveTree(serializableTree, out);
        }
    }

    /**
     * Replaces every {@link JMeterTreeNode} key in {@code tree} (recursively) with its
     * underlying {@link TestElement}, in place - the same conversion JMeter's own
     * (package-private) {@code Save.convertSubTree} performs before handing a tree to
     * {@link SaveService#saveTree}.
     */
    private static void unwrapTreeNodes(HashTree tree) {
        for (Object key : new LinkedList<>(tree.list())) {
            HashTree children = tree.getTree(key);
            if (children != null) {
                unwrapTreeNodes(children);
            }
            if (key instanceof JMeterTreeNode) {
                JMeterTreeNode item = (JMeterTreeNode) key;
                tree.replaceKey(item, item.getTestElement());
            }
        }
    }
}
