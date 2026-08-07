package org.qainsights.jmeter.ai.agent.jmeter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qainsights.jmeter.ai.agent.jmeter.ConfirmationPreview.Preview;
import org.qainsights.jmeter.ai.agent.jmeter.ConfirmationPreview.Row;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ConfirmationPreview}: per-tool previews over a
 * constructed tree, stale-id degradation, and the generic fallback.
 */
class ConfirmationPreviewTest {

    @TempDir
    Path tempDir;

    private JMeterTreeNode wrapperRoot;

    private static JMeterTreeNode node(String name) {
        ConfigTestElement element = new ConfigTestElement();
        element.setName(name);
        return new JMeterTreeNode(element, null);
    }

    @BeforeEach
    void setUp() {
        wrapperRoot = new JMeterTreeNode();
        JMeterTreeNode testPlan = node("Test Plan");
        JMeterTreeNode threadGroup = node("Thread Group");
        JMeterTreeNode sampler = node("Login Sampler");
        JMeterTreeNode checkout = node("Checkout Controller");
        wrapperRoot.add(testPlan);
        testPlan.add(threadGroup);
        threadGroup.add(sampler);
        threadGroup.add(checkout);
        checkout.add(node("Checkout Sampler"));
        sampler.add(node("HTTP Header Manager"));
        sampler.add(node("CSV Data Set Config"));
        sampler.add(node("Response Assertion"));
        sampler.add(node("Debug PostProcessor"));
        sampler.add(node("Extra Timer"));
    }

    private final ConfirmationPreview preview = new ConfirmationPreview();

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> args = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            args.put((String) kv[i], kv[i + 1]);
        }
        return args;
    }

    private static String rowValue(Preview p, String label) {
        for (Row row : p.rows()) {
            if (row.label().equals(label)) {
                return row.value();
            }
        }
        return null;
    }

    // --- delete_element ------------------------------------------------------

    @Test
    void deleteWithChildrenAndForceShowsSubtreeSummary() {
        Preview p = preview.describe("delete_element",
                args("element_id", "Test Plan/Thread Group/Login Sampler", "force", "true"), wrapperRoot);

        assertEquals("Delete 'Login Sampler' (ConfigTestElement)?", p.summary());
        assertEquals("5 direct · 5 total", rowValue(p, "Children"));
        assertTrue(rowValue(p, "Child names").contains("HTTP Header Manager"));
        assertTrue(rowValue(p, "Child names").contains("+1 more"));
        assertEquals(ConfirmationPreview.Level.DANGER, p.level());
        assertTrue(p.note().contains("Children"));
    }

    @Test
    void deleteWithChildrenWithoutForceWarnsAboutRejection() {
        Preview p = preview.describe("delete_element",
                args("element_id", "Test Plan/Thread Group/Login Sampler"), wrapperRoot);
        assertEquals(ConfirmationPreview.Level.WARN, p.level());
        assertTrue(p.note().contains("force=true"));
    }

    @Test
    void deleteLeafElementIsPlainInfo() {
        Preview p = preview.describe("delete_element",
                args("element_id", "Test Plan/Thread Group/Checkout Controller/Checkout Sampler"),
                wrapperRoot);
        assertEquals(ConfirmationPreview.Level.INFO, p.level());
        assertNull(rowValue(p, "Children"));
    }

    @Test
    void deleteLeafWithForceIsNotDanger() {
        Preview p = preview.describe("delete_element",
                args("element_id", "Test Plan/Thread Group/Checkout Controller/Checkout Sampler",
                        "force", "true"),
                wrapperRoot);
        // force is meaningless on a leaf: no DANGER note about children
        assertEquals(ConfirmationPreview.Level.INFO, p.level());
        assertFalse(p.note().contains("Children go with it"));
        assertNull(rowValue(p, "Force flag"));
    }

    @Test
    void nullArgsAreTolerated() {
        assertDoesNotThrow(() -> preview.describe("delete_element", null, wrapperRoot));
        assertDoesNotThrow(() -> preview.describe("open_plan", null, wrapperRoot));
        assertDoesNotThrow(() -> preview.describe("apply_correlation", null, wrapperRoot));
        assertDoesNotThrow(() -> preview.describe("move_element", null, wrapperRoot));
    }

    @Test
    void deleteUnknownIdDegradesHonestly() {
        Preview p = preview.describe("delete_element",
                args("element_id", "Test Plan/Gone"), wrapperRoot);
        assertEquals(ConfirmationPreview.Level.WARN, p.level());
        assertTrue(p.summary().contains("Test Plan/Gone"));
        assertTrue(p.note().contains("may fail"));
        assertTrue(p.rows().isEmpty());
    }

    // --- move_element --------------------------------------------------------

    @Test
    void moveShowsFromAndTo() {
        Preview p = preview.describe("move_element",
                args("element_id", "Test Plan/Thread Group/Checkout Controller/Checkout Sampler",
                        "new_parent_id", "Test Plan/Thread Group"),
                wrapperRoot);

        assertEquals("Move 'Checkout Sampler' to 'Thread Group'?", p.summary());
        assertTrue(rowValue(p, "From").contains("Checkout Controller"));
        assertTrue(rowValue(p, "To").contains("Thread Group"));
        assertEquals("none", rowValue(p, "Children"));
        assertEquals(ConfirmationPreview.Level.INFO, p.level());
    }

    @Test
    void moveWithUnknownParentDegrades() {
        Preview p = preview.describe("move_element",
                args("element_id", "Test Plan/Thread Group/Checkout Controller",
                        "new_parent_id", "Test Plan/Nowhere"),
                wrapperRoot);
        assertEquals(ConfirmationPreview.Level.WARN, p.level());
        assertTrue(p.note().contains("Test Plan/Nowhere"));
    }

    // --- open_plan -----------------------------------------------------------

    @Test
    void openPlanShowsFileDetails() throws Exception {
        Path jmx = Files.writeString(tempDir.resolve("checkout-load.jmx"), "<jmeterTestPlan/>");
        Preview p = preview.describe("open_plan",
                args("file_path", jmx.toString(), "force", "false"), wrapperRoot);

        assertEquals("Open 'checkout-load.jmx', replacing the current plan?", p.summary());
        assertEquals("checkout-load.jmx", rowValue(p, "File"));
        assertTrue(rowValue(p, "Size").endsWith(" B"));
        assertTrue(rowValue(p, "Force flag").startsWith("no"));
        assertEquals(ConfirmationPreview.Level.WARN, p.level());
    }

    @Test
    void openPlanMissingFileSaysUnknown() {
        Preview p = preview.describe("open_plan",
                args("file_path", tempDir.resolve("nope.jmx").toString()), wrapperRoot);
        assertEquals("unknown", rowValue(p, "Size"));
    }

    // --- apply_correlation ---------------------------------------------------

    @Test
    void correlationCountsSelectedCandidates() {
        Preview p = preview.describe("apply_correlation",
                args("candidate_ids", List.of("c1", "c2", "c3")), wrapperRoot);
        assertEquals("Apply correlation to 3 candidates?", p.summary());
        assertEquals("3 selected", rowValue(p, "Candidates"));
        assertEquals(ConfirmationPreview.Level.INFO, p.level());
    }

    @Test
    void correlationApplyAll() {
        Preview p = preview.describe("apply_correlation", args("apply_all", true), wrapperRoot);
        assertEquals("Apply correlation to all pending candidates?", p.summary());
        assertEquals("all pending", rowValue(p, "Candidates"));
    }

    // --- fallback / edge cases -------------------------------------------------

    @Test
    void unknownToolGetsGenericArgPreview() {
        Preview p = preview.describe("some_future_tool",
                args("element_id", "Test Plan/Thread Group", "force", "true"), wrapperRoot);
        assertTrue(p.summary().contains("some_future_tool"));
        assertEquals("Test Plan/Thread Group", rowValue(p, "Element"));
        assertEquals(ConfirmationPreview.Level.WARN, p.level());
    }

    @Test
    void nullRootStillProducesPreview() {
        Preview p = preview.describe("delete_element",
                args("element_id", "Test Plan/Thread Group"), null);
        assertEquals(ConfirmationPreview.Level.WARN, p.level());
        assertTrue(p.summary().contains("Test Plan/Thread Group"));
    }

    @Test
    void nullArgsAndToolNameDoNotThrow() {
        assertDoesNotThrow(() -> preview.describe(null, new LinkedHashMap<>(), wrapperRoot));
        Preview p = preview.describe(null, new LinkedHashMap<>(), wrapperRoot);
        assertEquals(ConfirmationPreview.Level.INFO, p.level());
    }
}
