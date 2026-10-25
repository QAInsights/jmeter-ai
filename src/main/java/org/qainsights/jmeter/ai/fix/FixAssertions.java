package org.qainsights.jmeter.ai.fix;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.jmeter.assertions.Assertion;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jorphan.collections.HashTree;
import org.qainsights.jmeter.ai.agent.jmeter.EdtExecutor;
import org.qainsights.jmeter.ai.agent.jmeter.GuiElementAdder;
import org.qainsights.jmeter.ai.agent.jmeter.JMeterTreeMutator;
import org.qainsights.jmeter.ai.agent.jmeter.TestRunSummary.Observation;

/** Adds deterministic page-title assertions to successful HTML samplers. */
public final class FixAssertions {
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

    public int addMissing(List<Observation> observations) {
        GuiPackage gui = GuiPackage.getInstance();
        if (gui == null || gui.getTreeModel() == null) {
            return 0;
        }
        AtomicReference<HashTree> treeRef = new AtomicReference<>();
        EdtExecutor.swing().run(() -> {
            gui.updateCurrentNode();
            treeRef.set(gui.getTreeModel().getTestPlan());
        });
        HashTree tree = treeRef.get();
        if (tree == null) {
            return 0;
        }
        int added = 0;
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
                added++;
            }
        }
        return added;
    }

    static List<Candidate> candidates(HashTree tree, List<Observation> observations) {
        Map<String, Observation> latest = new HashMap<>();
        if (observations != null) {
            for (Observation observation : observations) {
                latest.put(observation.getLabel(), observation);
            }
        }
        List<Candidate> candidates = new ArrayList<>();
        collect(tree, latest, candidates);
        return List.copyOf(candidates);
    }

    private static void collect(HashTree tree, Map<String, Observation> latest, List<Candidate> candidates) {
        if (tree == null) {
            return;
        }
        for (Object key : tree.list()) {
            TestElement element = element(key);
            HashTree children = tree.getTree(key);
            if (element instanceof Sampler && !hasAssertion(children)) {
                Observation observation = latest.get(element.getName());
                String title = observation == null ? "" : observation.getTitle().trim();
                if (isEligible(observation, title)) {
                    candidates.add(new Candidate(element, title));
                }
            }
            collect(children, latest, candidates);
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
        return !GENERIC_TITLES.contains(title.toLowerCase(Locale.ROOT));
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
