package org.qainsights.jmeter.ai.fix;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.regex.Pattern;

import org.apache.jmeter.assertions.Assertion;
import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jorphan.collections.HashTree;

/** Small safety snapshot used to reject unsafe agent edits. */
public final class PlanCensus {
    private static final Pattern SCRIPT_FUNCTION =
            Pattern.compile("\\$\\{__(groovy|jexl2|jexl3|javascript|beanshell)\\(", Pattern.CASE_INSENSITIVE);

    private final int samplerCount;
    private final int enabledSamplerCount;
    private final List<String> assertions;
    private final List<String> loadSettings;
    private final List<String> successAssertions;
    private final List<String> scripts;
    private final List<String> nonHttpSamplers;
    private final Set<String> hosts;

    private PlanCensus(int samplerCount, int enabledSamplerCount, List<String> assertions,
                       List<String> loadSettings, List<String> successAssertions, List<String> scripts,
                       List<String> nonHttpSamplers, Set<String> hosts) {
        this.samplerCount = samplerCount;
        this.enabledSamplerCount = enabledSamplerCount;
        this.assertions = List.copyOf(assertions);
        this.loadSettings = List.copyOf(loadSettings);
        this.successAssertions = List.copyOf(successAssertions);
        this.scripts = List.copyOf(scripts);
        this.nonHttpSamplers = List.copyOf(nonHttpSamplers);
        this.hosts = Set.copyOf(hosts);
    }

    public static PlanCensus live() {
        GuiPackage gui = GuiPackage.getInstance();
        return capture(gui == null ? null : gui.getTreeModel().getTestPlan());
    }

    public static PlanCensus capture(HashTree tree) {
        Counts counts = new Counts();
        walk(tree, true, counts);
        Collections.sort(counts.assertions);
        return new PlanCensus(counts.samplers, counts.enabledSamplers, counts.assertions, counts.loadSettings,
                counts.successAssertions, counts.scripts, counts.nonHttpSamplers, PlanHosts.collect(tree));
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
        if (hasNewEntry(successAssertions, after.successAssertions)) {
            violations.add("A new element forces requests to pass.");
        }
        if (hasNewEntry(scripts, after.scripts)) {
            violations.add("A script was added or changed. Make it pass can't verify scripts, so it doesn't change them.");
        }
        if (hasNewEntry(nonHttpSamplers, after.nonHttpSamplers)) {
            violations.add("A non-HTTP request was added or changed.");
        }
        after.hosts.stream().filter(host -> !hosts.contains(host))
                .sorted().forEach(host -> violations.add("A request now targets a host you did not confirm: "
                        + host + "."));
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
                if (!(element instanceof HTTPSamplerBase)) {
                    counts.nonHttpSamplers.add(fingerprint(element));
                }
            }
            if (element instanceof Assertion) {
                counts.assertions.add(fingerprint(element));
            }
            if (element instanceof ResponseAssertion
                    && ((ResponseAssertion) element).getAssumeSuccess()) {
                counts.successAssertions.add(fingerprint(element));
            }
            addScripts(element, counts);
            if (element instanceof ThreadGroup) {
                ThreadGroup group = (ThreadGroup) element;
                int loops = -1;
                if (group.getSamplerController() instanceof LoopController) {
                    loops = ((LoopController) group.getSamplerController()).getLoops();
                }
                List<String> properties = new ArrayList<>();
                Iterator<JMeterProperty> iterator = group.propertyIterator();
                while (iterator.hasNext()) {
                    JMeterProperty property = iterator.next();
                    if (property.getName().startsWith("ThreadGroup.")
                            && !"ThreadGroup.main_controller".equals(property.getName())) {
                        properties.add(property.getName() + "=" + property.getStringValue());
                    }
                }
                Collections.sort(properties);
                counts.loadSettings.add(counts.loadSettings.size() + ":"
                        + group.getNumThreads() + ":" + loops + ":"
                        + group.getScheduler() + ":" + group.getDuration() + ":"
                        + String.join("|", properties));
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
        return element.getClass().getSimpleName() + "|" + element.getName() + "|" + String.join("|", values);
    }

    private static void addScripts(TestElement element, Counts counts) {
        if (element == null) {
            return;
        }
        String type = element.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if (type.contains("jsr223") || type.contains("beanshell")) {
            counts.scripts.add(fingerprint(element));
        }
        Iterator<JMeterProperty> properties = element.propertyIterator();
        while (properties.hasNext()) {
            JMeterProperty property = properties.next();
            String value = property.getStringValue();
            if (value != null && SCRIPT_FUNCTION.matcher(value).find()) {
                counts.scripts.add(element.getClass().getSimpleName() + "|" + element.getName() + "|"
                        + property.getName() + "=" + value);
            }
        }
    }

    private static boolean hasNewEntry(List<String> before, List<String> after) {
        List<String> remaining = new ArrayList<>(before);
        for (String entry : after) {
            if (!remaining.remove(entry)) {
                return true;
            }
        }
        return false;
    }

    private static final class Counts {
        int samplers;
        int enabledSamplers;
        final List<String> assertions = new ArrayList<>();
        final List<String> loadSettings = new ArrayList<>();
        final List<String> successAssertions = new ArrayList<>();
        final List<String> scripts = new ArrayList<>();
        final List<String> nonHttpSamplers = new ArrayList<>();
    }
}
