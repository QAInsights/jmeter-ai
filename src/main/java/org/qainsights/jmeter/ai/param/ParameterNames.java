package org.qainsights.jmeter.ai.param;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.config.CSVDataSet;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;

import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;

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
            Arrays.stream(((CSVDataSet) element).getVariableNames().split(","))
                    .map(String::trim).filter(name -> !name.isEmpty()).forEach(names::add);
        } else if (element instanceof Arguments) {
            ((Arguments) element).getArgumentsAsMap().keySet().forEach(names::add);
        } else if (element instanceof TestPlan) {
            names.addAll(((TestPlan) element).getUserDefinedVariables().keySet());
        }
    }

    public static boolean valid(String name, Set<String> used) {
        return name.matches("[A-Za-z_][A-Za-z0-9_]*") && used.add(name);
    }
}
