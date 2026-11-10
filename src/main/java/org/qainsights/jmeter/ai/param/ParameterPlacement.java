package org.qainsights.jmeter.ai.param;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.POJONode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.databind.util.RawValue;
import org.apache.jmeter.config.Argument;
import org.apache.jmeter.config.CSVDataSet;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.qainsights.jmeter.ai.extract.ExtractPlacement;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

public final class ParameterPlacement {
    private static final ObjectMapper JSON = new ObjectMapper();
    private ParameterPlacement() { }

    public static JMeterTreeNode threadGroupOf(JMeterTreeNode node) {
        for (JMeterTreeNode n = node; n != null; n = (JMeterTreeNode) n.getParent()) {
            if (n.getTestElement() instanceof AbstractThreadGroup) { return n; }
        }
        return null;
    }

    public static CSVDataSet reusable(JMeterTreeNode group, Path base, String filename) {
        Path target = base.resolve(filename).toAbsolutePath().normalize();
        Enumeration<?> nodes = group.preorderEnumeration();
        while (nodes.hasMoreElements()) {
            JMeterTreeNode node = (JMeterTreeNode) nodes.nextElement();
            if (node.isEnabled() && node.getTestElement() instanceof CSVDataSet) {
                CSVDataSet csv = (CSVDataSet) node.getTestElement();
                if (!csv.getFilename().contains("${")
                        && base.resolve(csv.getFilename()).toAbsolutePath().normalize().equals(target)) {
                    return csv;
                }
            }
        }
        return null;
    }

    public static int apply(JMeterTreeModel model, JMeterTreeNode scope, CSVDataSet reuse, String filename,
            String delimiter, boolean recycle, boolean stop, String sharing,
            List<ParameterScanner.Candidate> chosen) throws Exception {
        List<String> existingNames = reuse == null || reuse.getVariableNames().isBlank() ? List.of()
                : List.of(reuse.getVariableNames().split(",", -1));
        return apply(model, scope, reuse, filename, delimiter, recycle, stop, sharing, chosen, existingNames);
    }

    public static int apply(JMeterTreeModel model, JMeterTreeNode scope, CSVDataSet reuse, String filename,
            String delimiter, boolean recycle, boolean stop, String sharing,
            List<ParameterScanner.Candidate> chosen, List<String> existingNames) throws Exception {
        JMeterTreeNode group = threadGroupOf(scope);
        if (group == null) { throw new IllegalArgumentException("Select an element inside a Thread Group."); }
        List<JMeterTreeNode> nodes = new ArrayList<>();
        Enumeration<?> enumeration = scope.preorderEnumeration();
        while (enumeration.hasMoreElements()) { nodes.add((JMeterTreeNode) enumeration.nextElement()); }
        GuiPackage gui = GuiPackage.getInstance();
        if (gui != null) { gui.beginUndoTransaction(); }
        try {
            int changed = 0;
            for (JMeterTreeNode node : nodes) {
                if (node.getTestElement() instanceof HTTPSamplerBase) {
                    HTTPSamplerBase http = (HTTPSamplerBase) node.getTestElement();
                    if (http.getPostBodyRaw()) {
                        for (JMeterProperty property : http.getArguments().getArguments()) {
                            Argument argument = (Argument) property.getObjectValue();
                            JsonNode body;
                            try { body = JSON.readTree(argument.getValue()); }
                            catch (Exception ignored) { continue; }
                            boolean[] replaced = {false};
                            JsonNode result = replaceJson(body, chosen, replaced);
                            if (replaced[0]) {
                                argument.setValue(JSON.writeValueAsString(result));
                                changed++;
                            }
                        }
                    }
                }
            }
            for (ParameterScanner.Candidate candidate : chosen) {
                changed += ExtractPlacement.replace(nodes, candidate.value, candidate.variable);
            }
            CSVDataSet csv = reuse == null ? new CSVDataSet() : reuse;
            String names = String.join(",", chosen.stream().map(c -> c.variable).toArray(String[]::new));
            csv.setVariableNames(existingNames.isEmpty() ? names : String.join(",", existingNames) + "," + names);
            csv.setFilename(filename);
            csv.setDelimiter(delimiter);
            csv.setFileEncoding("UTF-8");
            csv.setQuotedData(true);
            csv.setIgnoreFirstLine(true);
            csv.setRecycle(recycle);
            csv.setStopThread(stop);
            csv.setShareMode(sharing);
            if (reuse == null) {
                csv.setName("CSV Data Set Config");
                csv.setEnabled(true);
                csv.setProperty(TestElement.TEST_CLASS, CSVDataSet.class.getName());
                csv.setProperty(TestElement.GUI_CLASS, "org.apache.jmeter.testbeans.gui.TestBeanGUI");
                JMeterTreeNode added = model.addComponent(csv, group);
                model.removeNodeFromParent(added);
                model.insertNodeInto(added, group, 0);
            }
            model.nodeStructureChanged(group);
            return changed;
        } finally {
            if (gui != null) { gui.endUndoTransaction(); }
        }
    }

    private static JsonNode replaceJson(JsonNode node, List<ParameterScanner.Candidate> chosen, boolean[] replaced) {
        if (node == null || node.isNull()) { return node; }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            List<String> keys = new ArrayList<>();
            object.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) { object.set(key, replaceJson(object.get(key), chosen, replaced)); }
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) { array.set(i, replaceJson(array.get(i), chosen, replaced)); }
        } else {
            for (ParameterScanner.Candidate candidate : chosen) {
                if (node.asText().equals(candidate.value)) {
                    replaced[0] = true;
                    String variable = "${" + candidate.variable + "}";
                    return node.isTextual() ? TextNode.valueOf(variable) : new POJONode(new RawValue(variable));
                }
            }
        }
        return node;
    }
}
