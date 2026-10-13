package org.qainsights.jmeter.ai.sla;

import java.util.ArrayList;
import java.util.List;

import org.apache.jmeter.assertions.DurationAssertion;
import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.exceptions.IllegalUserActionException;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.control.Controller;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles {@code @sla}: turns a plain-English SLA into a Duration Assertion and/or a
 * Response Assertion on the response code, added under the selected tree element.
 * No AI call is made; the text is parsed by {@link SlaParser}.
 */
public final class SlaCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(SlaCommandHandler.class);

    static final String DURATION_GUI = "org.apache.jmeter.assertions.gui.DurationAssertionGui";
    static final String RESPONSE_GUI = "org.apache.jmeter.assertions.gui.AssertionGui";

    static final String NOTHING_TO_UNDO = "Nothing to undo: the last `@sla` added no assertions that are still in the plan.";

    private static List<JMeterTreeNode> lastAdded = List.of();

    static final String HELP = "Tell me the SLA after `@sla`, for example:\n"
            + "- `@sla under 800 ms and no 5xx`\n"
            + "- `@sla p95 below 1.5 s, no 4xx or 5xx`\n"
            + "- `@sla status 200`\n\n"
            + "Select a sampler, controller, Thread Group or the Test Plan first. "
            + "The assertions apply to every sampler in that element's scope.";

    /** Parses {@code message}, adds the assertions under the selected node and returns a markdown reply. */
    public String process(String message) {
        return process(message, selectedNode());
    }

    /** The tree node selected right now, or null without a GUI; capture it when the command is sent. */
    public static JMeterTreeNode selectedNode() {
        GuiPackage guiPackage = GuiPackage.getInstance();
        return guiPackage == null || guiPackage.getTreeListener() == null
                ? null
                : guiPackage.getTreeListener().getCurrentNode();
    }

    /** Like {@link #process(String)} but adds under {@code node}, the selection when the command was sent. */
    public String process(String message, JMeterTreeNode node) {
        GuiPackage guiPackage = GuiPackage.getInstance();
        return process(message, node, guiPackage == null ? null : guiPackage.getTreeModel());
    }

    String process(String message, JMeterTreeNode node, JMeterTreeModel model) {
        SlaParser.SlaSpec spec = SlaParser.parse(message);
        if (spec.isEmpty()) {
            return notes(spec, HELP);
        }
        if (model == null) {
            return "The JMeter GUI is not available, so I can't add assertions.";
        }
        if (node == null || node.getRoot() != model.getRoot() || !canHoldAssertions(node.getTestElement())) {
            return "Select a sampler, controller, Thread Group or the Test Plan first, then run `@sla` again. "
                    + "The assertions are added under the selected element.";
        }
        List<TestElement> elements = buildAssertions(spec, message);
        try {
            addAll(model, node, elements);
        } catch (Exception e) {
            log.warn("Could not add @sla assertions under {}", node.getName(), e);
            return "I couldn't add the assertions under **" + node.getName() + "**: " + e.getMessage();
        }
        return reply(spec, node.getName(), elements);
    }

    static synchronized void addAll(JMeterTreeModel model, JMeterTreeNode parent, List<TestElement> elements)
            throws IllegalUserActionException {
        List<JMeterTreeNode> added = new ArrayList<>();
        for (TestElement element : elements) {
            added.add(model.addComponent(element, parent));
        }
        model.nodeStructureChanged(parent);
        lastAdded = added;
    }

    /** Removes the assertions added by the last {@code @sla}; Ctrl+Z in the chat calls this. */
    public static String undoLast() {
        GuiPackage guiPackage = GuiPackage.getInstance();
        return guiPackage == null ? NOTHING_TO_UNDO : undoLast(guiPackage.getTreeModel());
    }

    static synchronized String undoLast(JMeterTreeModel model) {
        int removed = 0;
        for (JMeterTreeNode node : lastAdded) {
            if (node.getParent() != null) {
                model.removeNodeFromParent(node);
                removed++;
            }
        }
        lastAdded = List.of();
        if (removed == 0) {
            return NOTHING_TO_UNDO;
        }
        return "Removed " + removed + (removed == 1 ? " SLA assertion" : " SLA assertions")
                + " added by the last `@sla`.";
    }

    static boolean canHoldAssertions(TestElement element) {
        return element instanceof Sampler || element instanceof Controller || element instanceof TestPlan;
    }

    /** The assertion elements for {@code spec}, fully configured but not yet in the tree. */
    static List<TestElement> buildAssertions(SlaParser.SlaSpec spec, String source) {
        List<TestElement> elements = new ArrayList<>();
        String comment = "Added by Feather Wand from: " + source.trim();
        if (spec.maxMillis() > 0) {
            DurationAssertion duration = new DurationAssertion();
            duration.setProperty(TestElement.TEST_CLASS, DurationAssertion.class.getName());
            duration.setProperty(TestElement.GUI_CLASS, DURATION_GUI);
            duration.setName("SLA: each sample under " + formatMillis(spec.maxMillis()));
            duration.setComment(comment);
            duration.setAllowedDuration(spec.maxMillis());
            elements.add(duration);
        }
        if (spec.codeRule() != null || !spec.exactCode().isEmpty()) {
            ResponseAssertion assertion = new ResponseAssertion();
            assertion.setProperty(TestElement.TEST_CLASS, ResponseAssertion.class.getName());
            assertion.setProperty(TestElement.GUI_CLASS, RESPONSE_GUI);
            assertion.setComment(comment);
            assertion.setTestFieldResponseCode();
            if (spec.codeRule() != null) {
                assertion.setToMatchType();
                if (spec.codeRule().negated()) {
                    assertion.setToNotType();
                }
                assertion.addTestString(spec.codeRule().pattern());
                assertion.setName("SLA: " + spec.codeRule().description());
                assertion.setCustomFailureMessage("SLA breached: expected " + spec.codeRule().description());
            } else {
                assertion.setToEqualsType();
                assertion.addTestString(spec.exactCode());
                if (spec.exactCode().charAt(0) >= '4') {
                    // JMeter fails 4xx/5xx samples before assertions run; let this assertion decide.
                    assertion.setAssumeSuccess(true);
                }
                assertion.setName("SLA: response code " + spec.exactCode());
                assertion.setCustomFailureMessage("SLA breached: expected response code " + spec.exactCode());
            }
            elements.add(assertion);
        }
        return elements;
    }

    static String reply(SlaParser.SlaSpec spec, String nodeName, List<TestElement> elements) {
        StringBuilder text = new StringBuilder("Added ").append(elements.size())
                .append(elements.size() == 1 ? " assertion" : " assertions")
                .append(" under **").append(nodeName).append("**:\n");
        for (TestElement element : elements) {
            text.append("- ").append(element.getName()).append("\n");
        }
        if (!spec.statistic().isEmpty() && spec.maxMillis() > 0) {
            text.append("\nNote: JMeter assertions check each sample, not the ").append(spec.statistic())
                    .append(". Any single sample slower than ").append(formatMillis(spec.maxMillis()))
                    .append(" is marked failed, which is stricter than a ").append(spec.statistic())
                    .append(" target. Check the ").append(spec.statistic())
                    .append(" itself in the HTML dashboard or Aggregate Report after the run.\n");
        }
        return notes(spec, text.toString().trim());
    }

    private static String notes(SlaParser.SlaSpec spec, String body) {
        StringBuilder text = new StringBuilder(body);
        if (!spec.unsupported().isEmpty()) {
            text.append("\n\nNot added: ").append(String.join(", ", quoted(spec.unsupported())))
                    .append(". These are run-level targets (error rate, throughput, a percentage of samples, "
                            + "Apdex), so a per-sample assertion can't check them. Read them from the HTML "
                            + "dashboard or Aggregate Report after the run.");
        }
        if (!spec.superseded().isEmpty()) {
            text.append("\n\nAlso asked for: ").append(String.join(", ", quoted(spec.superseded())))
                    .append(". Only the strictest limit is added, because a sample that meets it also meets "
                            + "the looser ones.");
        }
        if (!spec.conflicts().isEmpty()) {
            text.append("\n\nNo response-code assertion added, because these rules contradict each other: ")
                    .append(String.join(", ", quoted(spec.conflicts())))
                    .append(". Run `@sla` again with the one you want.");
        }
        if (!spec.unrecognized().isEmpty()) {
            text.append("\n\nI didn't understand: ").append(String.join(", ", quoted(spec.unrecognized())))
                    .append(".");
        }
        return text.toString();
    }

    private static List<String> quoted(List<String> values) {
        List<String> quoted = new ArrayList<>();
        values.forEach(value -> quoted.add("\"" + value + "\""));
        return quoted;
    }

    static String formatMillis(long millis) {
        if (millis >= 1000 && millis % 100 == 0) {
            double seconds = millis / 1000.0;
            return (seconds == Math.floor(seconds) ? String.valueOf((long) seconds) : String.valueOf(seconds)) + " s";
        }
        return millis + " ms";
    }
}
