package org.qainsights.jmeter.ai.param;

import org.apache.jmeter.config.CSVDataSet;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.threads.AbstractThreadGroup;

import java.nio.file.Path;
import java.util.Enumeration;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class ParameterPlacement {
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
                if (!csv.getFilename().contains("${")
                        && base.resolve(csv.getFilename())
                                .toAbsolutePath()
                                .normalize()
                                .equals(target)) {
                    return csv;
                }
            }
        }
        return null;
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
                reuse == null || reuse.getVariableNames().isBlank()
                        ? List.of()
                        : List.of(reuse.getVariableNames().split(",", -1));
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
        Map<JMeterTreeNode, TestElement> before = new IdentityHashMap<>();
        Enumeration<?> enumeration = group.preorderEnumeration();
        while (enumeration.hasMoreElements()) {
            JMeterTreeNode node = (JMeterTreeNode) enumeration.nextElement();
            before.put(node, (TestElement) node.getTestElement().clone());
        }
        GuiPackage gui = GuiPackage.getInstance();
        if (gui != null) {
            gui.beginUndoTransaction();
        }
        JMeterTreeNode added = null;
        try {
            int changed = ParameterReplacement.replace(scope, chosen, true);
            CSVDataSet csv = reuse == null ? new CSVDataSet() : reuse;
            String names =
                    String.join(",", chosen.stream().map(c -> c.variable).toArray(String[]::new));
            csv.setVariableNames(
                    existingNames.isEmpty()
                            ? names
                            : String.join(",", existingNames) + "," + names);
            csv.setFilename(filename);
            if (reuse == null) {
                csv.setDelimiter(delimiter);
                csv.setFileEncoding("UTF-8");
                csv.setRecycle(recycle);
                csv.setStopThread(stop);
                csv.setShareMode(sharing);
            }
            csv.setQuotedData(true);
            csv.setIgnoreFirstLine(true);
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
}
