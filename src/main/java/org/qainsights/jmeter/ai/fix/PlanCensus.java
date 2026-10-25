package org.qainsights.jmeter.ai.fix;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.apache.jmeter.assertions.Assertion;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jorphan.collections.HashTree;

/** Small safety snapshot used to reject unsafe agent edits. */
public final class PlanCensus {
    private final int samplerCount;
    private final int enabledSamplerCount;
    private final List<String> assertions;
    private final List<String> loadSettings;

    private PlanCensus(int samplerCount, int enabledSamplerCount, List<String> assertions,
                       List<String> loadSettings) {
        this.samplerCount = samplerCount;
        this.enabledSamplerCount = enabledSamplerCount;
        this.assertions = List.copyOf(assertions);
        this.loadSettings = List.copyOf(loadSettings);
    }

    public static PlanCensus live() {
        GuiPackage gui = GuiPackage.getInstance();
        return capture(gui == null ? null : gui.getTreeModel().getTestPlan());
    }

    public static PlanCensus capture(HashTree tree) {
        Counts counts = new Counts();
        walk(tree, true, counts);
        Collections.sort(counts.assertions);
        return new PlanCensus(counts.samplers, counts.enabledSamplers, counts.assertions, counts.loadSettings);
    }

    public List<String> violations(PlanCensus after) {
        if (after == null) {
            return List.of("Could not inspect the updated plan.");
        }
        List<String> violations = new ArrayList<>();
        if (after.samplerCount < samplerCount) {
            violations.add("One or more requests were removed.");
        } else if (after.enabledSamplerCount < enabledSamplerCount) {
            violations.add("One or more requests were disabled.");
        }
        List<String> remaining = new ArrayList<>(after.assertions);
        for (String assertion : assertions) {
            if (!remaining.remove(assertion)) {
                violations.add("An assertion was removed or changed.");
                break;
            }
        }
        if (!loadSettings.equals(after.loadSettings)) {
            violations.add("Thread Group load settings were changed.");
        }
        return List.copyOf(violations);
    }

    public int samplerCount() {
        return samplerCount;
    }

    public int enabledSamplerCount() {
        return enabledSamplerCount;
    }

    public int assertionCount() {
        return assertions.size();
    }

    private static void walk(HashTree tree, boolean ancestorsEnabled, Counts counts) {
        if (tree == null) {
            return;
        }
        for (Object key : tree.list()) {
            TestElement element = elementOf(key);
            boolean enabled = ancestorsEnabled && element != null && element.isEnabled();
            if (element instanceof Sampler) {
                counts.samplers++;
                if (enabled) {
                    counts.enabledSamplers++;
                }
            }
            if (element instanceof Assertion) {
                counts.assertions.add(fingerprint(element));
            }
            if (element instanceof ThreadGroup) {
                ThreadGroup group = (ThreadGroup) element;
                int loops = -1;
                if (group.getSamplerController() instanceof LoopController) {
                    loops = ((LoopController) group.getSamplerController()).getLoops();
                }
                counts.loadSettings.add(counts.loadSettings.size() + ":"
                        + group.getNumThreads() + ":" + loops + ":"
                        + group.getScheduler() + ":" + group.getDuration());
            }
            walk(tree.getTree(key), enabled, counts);
        }
    }

    private static TestElement elementOf(Object key) {
        return key instanceof JMeterTreeNode ? ((JMeterTreeNode) key).getTestElement()
                : key instanceof TestElement ? (TestElement) key : null;
    }

    private static String fingerprint(TestElement element) {
        List<String> values = new ArrayList<>();
        Iterator<JMeterProperty> properties = element.propertyIterator();
        while (properties.hasNext()) {
            JMeterProperty property = properties.next();
            values.add(property.getName() + "=" + property.getStringValue());
        }
        Collections.sort(values);
        return element.getName() + "|" + String.join("|", values);
    }

    private static final class Counts {
        int samplers;
        int enabledSamplers;
        final List<String> assertions = new ArrayList<>();
        final List<String> loadSettings = new ArrayList<>();
    }
}
