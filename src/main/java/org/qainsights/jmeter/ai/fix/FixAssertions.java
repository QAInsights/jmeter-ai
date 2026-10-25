package org.qainsights.jmeter.ai.fix;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.jmeter.assertions.Assertion;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jorphan.collections.HashTree;
import org.qainsights.jmeter.ai.agent.jmeter.EdtExecutor;
import org.qainsights.jmeter.ai.agent.jmeter.GuiElementAdder;
import org.qainsights.jmeter.ai.agent.jmeter.JMeterTreeMutator;
import org.qainsights.jmeter.ai.agent.jmeter.TestRunSummary.Observation;

/** Adds deterministic page-title assertions to successful HTML samplers. */
public final class FixAssertions implements FixLoop.AssertionInstaller {
    private static final String ASSERTION_ALIAS = "responseassert";
    private static final String ASSERTION_NAME = "Make it pass: page check";
    private static final String TEST_FIELD = "Assertion.test_field";
    private static final String TEST_TYPE = "Assertion.test_type";
    private static final String TEST_STRINGS = "Asserion.test_strings";
    private static final Set<String> GENERIC_TITLES = Set.of(
            "home", "welcome", "index", "untitled", "test page");

    private final GuiElementAdder adder;
    private final JMeterTreeMutator mutator;

    public FixAssertions() {
        this(new GuiElementAdder(), new JMeterTreeMutator());
    }

    FixAssertions(GuiElementAdder adder, JMeterTreeMutator mutator) {
        this.adder = adder;
        this.mutator = mutator;
    }

    @Override
    public List<String> addMissing(List<Observation> observations) {
        GuiPackage gui = GuiPackage.getInstance();
        if (gui == null || gui.getTreeModel() == null) {
            return List.of();
        }
        AtomicReference<HashTree> treeRef = new AtomicReference<>();
        EdtExecutor.swing().run(() -> {
            gui.updateCurrentNode();
            treeRef.set(gui.getTreeModel().getTestPlan());
        });
        HashTree tree = treeRef.get();
        if (tree == null) {
            return List.of();
        }
        List<String> added = new ArrayList<>();
        for (Candidate candidate : candidates(tree, observations)) {
            JMeterTreeNode parent = findNode(tree, candidate.sampler);
            if (parent == null) {
                continue;
            }
            JMeterTreeNode assertionNode = adder.add(parent, ASSERTION_ALIAS, ASSERTION_NAME);
            if (assertionNode == null) {
                continue;
            }
            boolean configured = mutator.updateProperty(gui.getTreeModel(), assertionNode, TEST_FIELD,
                    "Assertion.response_data")
                    && mutator.updateProperty(gui.getTreeModel(), assertionNode, TEST_TYPE, "16")
                    && mutator.replacePropertyList(gui.getTreeModel(), assertionNode, TEST_STRINGS,
                            List.of(candidate.title));
            if (configured) {
                added.add(candidate.sampler.getName());
            } else {
                mutator.deleteElement(gui.getTreeModel(), assertionNode, true);
            }
        }
        return List.copyOf(added);
    }

    @Override
    public void removeAdded(Collection<String> samplerLabels) {
        if (samplerLabels == null || samplerLabels.isEmpty()) {
            return;
        }
        GuiPackage gui = GuiPackage.getInstance();
        if (gui == null || gui.getTreeModel() == null) {
            return;
        }
        EdtExecutor.swing().run(() -> {
            gui.updateCurrentNode();
            List<JMeterTreeNode> nodes = addedAssertionNodes(gui.getTreeModel().getTestPlan(), samplerLabels);
            nodes.forEach(node -> mutator.deleteElement(gui.getTreeModel(), node, true));
        });
    }

    static List<Candidate> candidates(HashTree tree, List<Observation> observations) {
        Map<String, List<Observation>> byLabel = new HashMap<>();
        if (observations != null) {
            for (Observation observation : observations) {
                byLabel.computeIfAbsent(observation.getLabel(), ignored -> new ArrayList<>()).add(observation);
            }
        }
        Map<String, Integer> samplerCounts = new HashMap<>();
        countSamplers(tree, samplerCounts);
        List<Candidate> candidates = new ArrayList<>();
        collect(tree, byLabel, samplerCounts, candidates);
        return List.copyOf(candidates);
    }

    private static void countSamplers(HashTree tree, Map<String, Integer> counts) {
        if (tree == null) {
            return;
        }
        for (Object key : tree.list()) {
            TestElement element = element(key);
            if (element instanceof Sampler) {
                counts.merge(element.getName(), 1, Integer::sum);
            }
            countSamplers(tree.getTree(key), counts);
        }
    }

    private static void collect(HashTree tree, Map<String, List<Observation>> byLabel,
                                Map<String, Integer> samplerCounts, List<Candidate> candidates) {
        if (tree == null) {
            return;
        }
        for (Object key : tree.list()) {
            TestElement element = element(key);
            HashTree children = tree.getTree(key);
            String label = element == null ? "" : element.getName();
            List<Observation> matching = byLabel.get(label);
            if (element instanceof Sampler && samplerCounts.getOrDefault(label, 0) == 1
                    && matching != null && !matching.isEmpty() && !hasAssertion(children)) {
                Observation observation = matching.get(matching.size() - 1);
                String title = observation.getTitle().trim();
                boolean stableTitle = matching.stream()
                        .map(item -> item.getTitle().trim()).allMatch(title::equals);
                if (stableTitle && isEligible(observation, title)) {
                    candidates.add(new Candidate(element, title));
                }
            }
            collect(children, byLabel, samplerCounts, candidates);
        }
    }

    private static boolean isEligible(Observation observation, String title) {
        if (observation == null || observation.getSoftFailureReason() != null || title.isEmpty()) {
            return false;
        }
        try {
            int code = Integer.parseInt(observation.getResponseCode());
            if (code < 200 || code >= 400) {
                return false;
            }
        } catch (NumberFormatException e) {
            return false;
        }
        String contentType = observation.getContentType().toLowerCase(Locale.ROOT);
        if (!contentType.contains("html")) {
            return false;
        }
        return !title.contains("${") && !title.contains("__")
                && !GENERIC_TITLES.contains(title.toLowerCase(Locale.ROOT));
    }

    private static boolean hasAssertion(HashTree children) {
        if (children == null) {
            return false;
        }
        for (Object key : children.list()) {
            if (element(key) instanceof Assertion) {
                return true;
            }
        }
        return false;
    }

    private static JMeterTreeNode findNode(HashTree tree, TestElement target) {
        if (tree == null) {
            return null;
        }
        for (Object key : tree.list()) {
            if (key instanceof JMeterTreeNode && ((JMeterTreeNode) key).getTestElement() == target) {
                return (JMeterTreeNode) key;
            }
            JMeterTreeNode found = findNode(tree.getTree(key), target);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static List<JMeterTreeNode> addedAssertionNodes(HashTree tree, Collection<String> samplerLabels) {
        List<JMeterTreeNode> nodes = new ArrayList<>();
        collectAddedAssertionNodes(tree, new HashSet<>(samplerLabels), nodes);
        return List.copyOf(nodes);
    }

    private static void collectAddedAssertionNodes(HashTree tree, Set<String> labels,
                                                   List<JMeterTreeNode> nodes) {
        if (tree == null) {
            return;
        }
        for (Object key : tree.list()) {
            TestElement element = element(key);
            HashTree children = tree.getTree(key);
            if (element instanceof Sampler && labels.contains(element.getName()) && children != null) {
                for (Object child : children.list()) {
                    TestElement assertion = element(child);
                    if (shouldRemoveAddedAssertion(element.getName(), labels, assertion)
                            && child instanceof JMeterTreeNode) {
                        nodes.add((JMeterTreeNode) child);
                    }
                }
            }
            collectAddedAssertionNodes(children, labels, nodes);
        }
    }

    static boolean isPageCheck(TestElement element) {
        return element instanceof Assertion && ASSERTION_NAME.equals(element.getName());
    }

    static boolean shouldRemoveAddedAssertion(String samplerLabel, Collection<String> samplerLabels,
                                              TestElement assertion) {
        return samplerLabels.contains(samplerLabel) && isPageCheck(assertion);
    }

    private static TestElement element(Object key) {
        return key instanceof JMeterTreeNode ? ((JMeterTreeNode) key).getTestElement()
                : key instanceof TestElement ? (TestElement) key : null;
    }

    static final class Candidate {
        final TestElement sampler;
        final String title;

        Candidate(TestElement sampler, String title) {
            this.sampler = sampler;
            this.title = title;
        }
    }
}
