package org.qainsights.jmeter.ai.param;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.config.CSVDataSet;
import org.apache.jmeter.services.FileServer;
import org.apache.jmeter.save.CSVSaveService;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;

import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.Charset;
import java.io.BufferedReader;
import java.io.IOException;

/** Conservatively reserves names in the Thread Group and ancestor configuration scopes. */
public final class ParameterNames {
    private ParameterNames() {}

    public static Set<String> used(JMeterTreeNode scope) {
        Set<String> names = new HashSet<>();
        JMeterTreeNode group = ParameterPlacement.threadGroupOf(scope);
        if (group == null) {
            group = scope;
        }
        if (group == null) {
            return names;
        }
        Enumeration<?> nodes = group.preorderEnumeration();
        while (nodes.hasMoreElements()) {
            collect(((JMeterTreeNode) nodes.nextElement()).getTestElement(), names);
        }
        for (JMeterTreeNode ancestor = (JMeterTreeNode) group.getParent(); ancestor != null;
                ancestor = (JMeterTreeNode) ancestor.getParent()) {
            collect(ancestor.getTestElement(), names);
            Enumeration<?> children = ancestor.children();
            while (children.hasMoreElements()) {
                collect(((JMeterTreeNode) children.nextElement()).getTestElement(), names);
            }
        }
        return names;
    }

    private static void collect(TestElement element, Set<String> names) {
        if (element instanceof CSVDataSet) {
            String explicit = element.getPropertyAsString("variableNames");
            Arrays.stream(explicit.isBlank() ? headerNames(element) : explicit.split(","))
                    .map(String::trim).filter(name -> !name.isEmpty()).forEach(names::add);
        } else if (element instanceof Arguments) {
            ((Arguments) element).getArgumentsAsMap().keySet().forEach(names::add);
        } else if (element instanceof TestPlan) {
            names.addAll(((TestPlan) element).getUserDefinedVariables().keySet());
        }
    }

    private static String[] headerNames(TestElement csv) {
        String filename = csv.getPropertyAsString("filename").trim();
        if (filename.isEmpty() || filename.contains("${")) {
            return new String[0];
        }
        try {
            Path path = Path.of(filename);
            if (!path.isAbsolute() && !Files.exists(path)) {
                path = Path.of(FileServer.getFileServer().getBaseDir()).resolve(path);
            }
            String encoding = csv.getPropertyAsString("fileEncoding");
            Charset charset = encoding.isBlank() ? Charset.defaultCharset() : Charset.forName(encoding);
            String separator = csv.getPropertyAsString("delimiter", ",");
            char delimiter = separator.equals("\\t") ? '\t' : separator.isEmpty() ? ',' : separator.charAt(0);
            try (BufferedReader reader = Files.newBufferedReader(path, charset)) {
                String header = reader.readLine();
                return header == null ? new String[0] : CSVSaveService.csvSplitString(header, delimiter);
            }
        } catch (IOException | IllegalArgumentException | SecurityException ignored) {
            return new String[0];
        }
    }

    public static boolean valid(String name, Set<String> used) {
        return name.matches("[A-Za-z_][A-Za-z0-9_]*") && used.add(name);
    }
}
