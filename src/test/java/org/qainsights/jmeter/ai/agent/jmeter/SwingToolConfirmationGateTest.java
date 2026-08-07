package org.qainsights.jmeter.ai.agent.jmeter;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SwingToolConfirmationGate} using a direct EDT, a fake
 * dialog, and an injected tree supplier.
 */
class SwingToolConfirmationGateTest {

    private static JMeterTreeNode sampleTree() {
        JMeterTreeNode wrapperRoot = new JMeterTreeNode();
        ConfigTestElement plan = new ConfigTestElement();
        plan.setName("Test Plan");
        JMeterTreeNode testPlan = new JMeterTreeNode(plan, null);
        ConfigTestElement group = new ConfigTestElement();
        group.setName("Thread Group");
        JMeterTreeNode threadGroup = new JMeterTreeNode(group, null);
        ConfigTestElement child = new ConfigTestElement();
        child.setName("HTTP Request");
        threadGroup.add(new JMeterTreeNode(child, null));
        wrapperRoot.add(testPlan);
        testPlan.add(threadGroup);
        return wrapperRoot;
    }

    @Test
    void confirm_approved_returnsTrue() {
        SwingToolConfirmationGate gate = new SwingToolConfirmationGate(EdtExecutor.direct(), p -> true);
        assertTrue(gate.confirm("delete_element", new LinkedHashMap<>()));
    }

    @Test
    void confirm_declined_returnsFalse() {
        SwingToolConfirmationGate gate = new SwingToolConfirmationGate(EdtExecutor.direct(), p -> false);
        assertFalse(gate.confirm("delete_element", new LinkedHashMap<>()));
    }

    @Test
    void confirm_dialogReceivesStructuredPreviewFromLiveTree() {
        ConfirmationPreview.Preview[] captured = {null};
        SwingToolConfirmationGate gate = new SwingToolConfirmationGate(EdtExecutor.direct(), p -> {
            captured[0] = p;
            return true;
        }, SwingToolConfirmationGateTest::sampleTree, new ConfirmationPreview());

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("element_id", "Test Plan/Thread Group");
        args.put("force", "true");
        gate.confirm("delete_element", args);

        assertNotNull(captured[0]);
        assertEquals("Delete 'Thread Group' (ConfigTestElement)?", captured[0].summary());
        assertEquals(ConfirmationPreview.Level.DANGER, captured[0].level());
    }

    @Test
    void confirm_genericToolKeepsWorking() {
        ConfirmationPreview.Preview[] captured = {null};
        SwingToolConfirmationGate gate = new SwingToolConfirmationGate(EdtExecutor.direct(), p -> {
            captured[0] = p;
            return true;
        });
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("element_id", "Test Plan/Thread Group/HTTP Request");

        gate.confirm("some_tool", args);

        assertTrue(captured[0].summary().contains("some_tool"));
        assertEquals("Test Plan/Thread Group/HTTP Request", captured[0].rows().get(0).value());
    }

    @Test
    void confirm_readsTreeInsideTheEdtBlock() {
        boolean[] inEdtBlock = {false};
        EdtExecutor tracking = task -> {
            inEdtBlock[0] = true;
            task.run();
            inEdtBlock[0] = false;
        };
        boolean[] readOnEdt = {false};
        SwingToolConfirmationGate gate = new SwingToolConfirmationGate(tracking, p -> true,
                () -> {
                    readOnEdt[0] = inEdtBlock[0];
                    return null;
                }, new ConfirmationPreview());

        gate.confirm("delete_element", new LinkedHashMap<>());

        assertTrue(readOnEdt[0], "tree reads must happen inside the EDT block");
    }

    @Test
    void buildConfirmPanel_boundsWidthForLongValues() {
        ConfirmationPreview.Preview preview = new ConfirmationPreview.Preview(
                "Open 'very-long-plan-name.jmx', replacing the current plan?",
                java.util.List.of(new ConfirmationPreview.Row("Location", "C:\\" + "deep\\".repeat(40))),
                "Note text that could also be fairly long when it needs to be.",
                ConfirmationPreview.Level.WARN);

        javax.swing.JPanel panel = SwingToolConfirmationGate.buildConfirmPanel(preview);

        assertTrue(panel.getPreferredSize().width < 520,
                "panel should wrap instead of growing wide: " + panel.getPreferredSize().width);
    }

    @Test
    void confirm_delegatesThroughTheEdtExecutor() {
        boolean[] usedExecutor = {false};
        EdtExecutor tracking = task -> {
            usedExecutor[0] = true;
            task.run();
        };
        SwingToolConfirmationGate gate = new SwingToolConfirmationGate(tracking, p -> true);

        gate.confirm("delete_element", new LinkedHashMap<>());

        assertTrue(usedExecutor[0]);
    }

    @Test
    void buildConfirmPanel_rendersTitleSummaryRowsAndTintedNote() {
        ConfirmationPreview.Preview preview = new ConfirmationPreview.Preview(
                "Delete 'Thread Group' (ConfigTestElement)?",
                java.util.List.of(new ConfirmationPreview.Row("Children", "2 direct · 3 total")),
                "This cannot be undone by the agent. Children go with it.",
                ConfirmationPreview.Level.DANGER);

        javax.swing.JPanel panel = SwingToolConfirmationGate.buildConfirmPanel(preview);

        java.util.List<javax.swing.JComponent> texts = new java.util.ArrayList<>();
        collectTextComponents(panel, texts);
        assertTrue(texts.stream().anyMatch(l -> "The AI agent requests your approval".equals(textOf(l))
                && l.getFont().isBold()));
        assertTrue(texts.stream().anyMatch(l -> textOf(l).contains("Delete 'Thread Group'")));
        assertTrue(texts.stream().anyMatch(l -> textOf(l).contains("2 direct · 3 total")));
        javax.swing.JComponent note = texts.stream()
                .filter(l -> textOf(l).contains("cannot be undone"))
                .findFirst()
                .orElseThrow();
        assertEquals(new java.awt.Color(0xB0, 0x48, 0x3E), note.getForeground());
    }

    private static String textOf(javax.swing.JComponent c) {
        return c instanceof javax.swing.JLabel
                ? ((javax.swing.JLabel) c).getText()
                : ((javax.swing.JTextArea) c).getText();
    }

    private static void collectTextComponents(java.awt.Container container, java.util.List<javax.swing.JComponent> into) {
        for (java.awt.Component c : container.getComponents()) {
            if (c instanceof javax.swing.JLabel || c instanceof javax.swing.JTextArea) {
                into.add((javax.swing.JComponent) c);
            }
            if (c instanceof java.awt.Container) {
                collectTextComponents((java.awt.Container) c, into);
            }
        }
    }
}
