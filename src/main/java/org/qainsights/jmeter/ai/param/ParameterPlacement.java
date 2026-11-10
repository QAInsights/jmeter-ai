package org.qainsights.jmeter.ai.param;

import org.apache.jmeter.config.CSVDataSet;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.threads.AbstractThreadGroup;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class ParameterPlacement {
    private static LastApplied lastApplied;

    private record LastApplied(JMeterTreeModel model, JMeterTreeNode group,
            List<ParameterPropertyUndo.Change> changes, JMeterTreeNode added) {
    }

    private ParameterPlacement() {}

    public static JMeterTreeNode threadGroupOf(JMeterTreeNode node) {
        for (JMeterTreeNode n = node; n != null; n = (JMeterTreeNode) n.getParent()) {
            if (n.getTestElement() instanceof AbstractThreadGroup) {
                return n;
            }
        }
        return null;
    }

    public static CSVDataSet reusable(JMeterTreeNode group, Path base, String filename) {
        Path target = base.resolve(filename).toAbsolutePath().normalize();
        Enumeration<?> nodes = group.children();
        while (nodes.hasMoreElements()) {
            JMeterTreeNode node = (JMeterTreeNode) nodes.nextElement();
            if (node.isEnabled() && node.getTestElement() instanceof CSVDataSet) {
                CSVDataSet csv = (CSVDataSet) node.getTestElement();
                String filenameProperty = csv.getPropertyAsString("filename");
                if (!filenameProperty.isBlank() && !filenameProperty.contains("${")
                        && base.resolve(filenameProperty)
                                .toAbsolutePath()
                                .normalize()
                                .equals(target)) {
                    return csv;
                }
            }
        }
        return null;
    }

    public static void validateTarget(Path target, String plan) throws java.io.IOException {
        if (Files.isSymbolicLink(target)) {
            throw new IllegalArgumentException("CSV target must not be a symbolic link.");
        }
        if (plan != null && !plan.isBlank()) {
            Path saved = Path.of(plan).toAbsolutePath().normalize();
            if (target.toAbsolutePath().normalize().equals(saved)
                    || (Files.exists(target) && Files.exists(saved) && Files.isSameFile(target, saved))) {
                throw new IllegalArgumentException("CSV file must differ from the saved .jmx plan.");
            }
        }
    }

    public static boolean utf8Compatible(CSVDataSet csv) {
        String encoding = csv.getPropertyAsString("fileEncoding");
        return encoding.isBlank() ? java.nio.charset.Charset.defaultCharset().equals(java.nio.charset.StandardCharsets.UTF_8)
                : "UTF-8".equalsIgnoreCase(encoding);
    }

    public static int apply(
            JMeterTreeModel model,
            JMeterTreeNode scope,
            CSVDataSet reuse,
            String filename,
            String delimiter,
            boolean recycle,
            boolean stop,
            String sharing,
            List<ParameterScanner.Candidate> chosen)
            throws Exception {
        List<String> existingNames =
                reuse == null || reuse.getPropertyAsString("variableNames").isBlank()
                        ? List.of()
                        : List.of(reuse.getPropertyAsString("variableNames").split(",", -1));
        return apply(
                model,
                scope,
                reuse,
                filename,
                delimiter,
                recycle,
                stop,
                sharing,
                chosen,
                existingNames);
    }

    public static int apply(
            JMeterTreeModel model,
            JMeterTreeNode scope,
            CSVDataSet reuse,
            String filename,
            String delimiter,
            boolean recycle,
            boolean stop,
            String sharing,
            List<ParameterScanner.Candidate> chosen,
            List<String> existingNames)
            throws Exception {
        JMeterTreeNode group = threadGroupOf(scope);
        if (group == null) {
            throw new IllegalArgumentException("Select an element inside a Thread Group.");
        }
        GuiPackage gui = GuiPackage.getInstance();
        if (gui != null) {
            gui.updateCurrentNode();
        }
        Map<JMeterTreeNode, TestElement> before = new IdentityHashMap<>();
        Enumeration<?> enumeration = group.preorderEnumeration();
        while (enumeration.hasMoreElements()) {
            JMeterTreeNode node = (JMeterTreeNode) enumeration.nextElement();
            before.put(node, (TestElement) node.getTestElement().clone());
        }
        if (gui != null) {
            gui.beginUndoTransaction();
        }
        JMeterTreeNode added = null;
        try {
            int changed = ParameterReplacement.replace(scope, chosen, true);
            CSVDataSet csv = reuse == null ? new CSVDataSet() : reuse;
            String names =
                    String.join(",", chosen.stream().map(c -> c.variable).toArray(String[]::new));
            csv.setProperty("variableNames",
                    existingNames.isEmpty()
                            ? names
                            : String.join(",", existingNames) + "," + names);
            csv.setProperty("filename", filename);
            if (reuse == null) {
                csv.setProperty("delimiter", delimiter);
                csv.setProperty("fileEncoding", "UTF-8");
                csv.setProperty("recycle", recycle);
                csv.setProperty("stopThread", stop);
                csv.setProperty("shareMode", sharing);
                csv.setProperty("quotedData", true);
            }
            csv.setProperty("ignoreFirstLine", true);
            if (reuse == null) {
                csv.setName("CSV Data Set Config");
                csv.setEnabled(true);
                csv.setProperty(TestElement.TEST_CLASS, CSVDataSet.class.getName());
                csv.setProperty(
                        TestElement.GUI_CLASS, "org.apache.jmeter.testbeans.gui.TestBeanGUI");
                added = model.addComponent(csv, group);
                model.removeNodeFromParent(added);
                model.insertNodeInto(added, group, 0);
            }
            model.nodeStructureChanged(group);
            if (gui != null) {
                gui.refreshCurrentGui();
                gui.updateCurrentNode();
            }
            lastApplied = new LastApplied(model, group, ParameterPropertyUndo.changes(before), added);
            return changed;
        } catch (Exception failure) {
            for (Map.Entry<JMeterTreeNode, TestElement> entry : before.entrySet()) {
                TestElement original = entry.getKey().getTestElement();
                original.clear();
                PropertyIterator properties = entry.getValue().propertyIterator();
                while (properties.hasNext()) {
                    original.setProperty(properties.next().clone());
                }
            }
            if (added != null && added.getParent() != null) {
                added.removeFromParent();
            }
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                JMeterTreeNode child = (JMeterTreeNode) group.getChildAt(i);
                if (!before.containsKey(child)) {
                    child.removeFromParent();
                }
            }
            try {
                model.nodeStructureChanged(group);
            } catch (Exception refreshFailure) {
                failure.addSuppressed(refreshFailure);
            }
            throw failure;
        } finally {
            if (gui != null) {
                gui.endUndoTransaction();
            }
        }
    }

    public static boolean hasUndo() {
        GuiPackage gui = GuiPackage.getInstance();
        if (lastApplied != null && gui != null && (lastApplied.model != gui.getTreeModel()
                || lastApplied.group.getRoot() != gui.getTreeModel().getRoot())) {
            lastApplied = null;
        }
        return gui != null && lastApplied != null;
    }

    public static String undoLast() {
        GuiPackage gui = GuiPackage.getInstance();
        return undoLast(gui == null ? null : gui.getTreeModel());
    }

    static String undoLast(JMeterTreeModel model) {
        LastApplied edit = lastApplied;
        if (edit == null || model == null || edit.model != model
                || edit.group.getRoot() != model.getRoot()) {
            return "Nothing to undo: the last Parameterize change is no longer in this plan.";
        }
        GuiPackage gui = GuiPackage.getInstance();
        if (gui != null) {
            gui.beginUndoTransaction();
        }
        try {
            if (gui != null) {
                gui.updateCurrentNode();
                gui.getTreeListener().getJTree().setSelectionPath(
                        new javax.swing.tree.TreePath(edit.group.getPath()));
            }
            for (ParameterPropertyUndo.Change change : edit.changes) {
                change.restore();
            }
            if (edit.added != null && edit.added.getParent() != null) {
                model.removeNodeFromParent(edit.added);
            }
            model.nodeStructureChanged(edit.group);
            if (gui != null) {
                gui.refreshCurrentGui();
                gui.updateCurrentNode();
            }
            lastApplied = null;
            return "Restored the plan before Parameterize. The CSV file on disk was not changed.";
        } finally {
            if (gui != null) {
                gui.endUndoTransaction();
            }
        }
    }
}
