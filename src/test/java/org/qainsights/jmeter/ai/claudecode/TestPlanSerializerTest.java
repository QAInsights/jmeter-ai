package org.qainsights.jmeter.ai.claudecode;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TestPlanSerializer}.
 * <p>
 * {@link GuiPackage#getInstance()} is mocked statically to avoid needing a
 * running JMeter instance.  The private utility methods {@code getIndent} and
 * {@code truncateValue} are exercised via reflection.
 */
class TestPlanSerializerTest {

    // ── serializeTestPlan – null-guard branches ────────────────────────────────

    @Test
    void serializeTestPlan_whenGuiPackageIsNull_returnsNoTestPlanMessage() {
        try (MockedStatic<GuiPackage> guiMock = mockStatic(GuiPackage.class)) {
            guiMock.when(GuiPackage::getInstance).thenReturn(null);

            String result = TestPlanSerializer.serializeTestPlan();

            assertEquals("No test plan is currently open in JMeter.", result);
        }
    }

    @Test
    void serializeTestPlan_whenTreeModelIsNull_returnsTreeModelMessage() {
        GuiPackage mockGui = mock(GuiPackage.class);
        when(mockGui.getTreeModel()).thenReturn(null);

        try (MockedStatic<GuiPackage> guiMock = mockStatic(GuiPackage.class)) {
            guiMock.when(GuiPackage::getInstance).thenReturn(mockGui);

            String result = TestPlanSerializer.serializeTestPlan();

            assertEquals("No test plan tree model available.", result);
        }
    }

    @Test
    void serializeTestPlan_whenRootNodeIsNull_returnsEmptyTreeMessage() {
        GuiPackage mockGui = mock(GuiPackage.class);
        JMeterTreeModel mockModel = mock(JMeterTreeModel.class);
        when(mockGui.getTreeModel()).thenReturn(mockModel);
        when(mockModel.getRoot()).thenReturn(null);

        try (MockedStatic<GuiPackage> guiMock = mockStatic(GuiPackage.class)) {
            guiMock.when(GuiPackage::getInstance).thenReturn(mockGui);

            String result = TestPlanSerializer.serializeTestPlan();

            assertEquals("Test plan tree is empty.", result);
        }
    }

    @Test
    void serializeTestPlan_withRootNodeHavingNullElement_returnsHeaderOnly() {
        GuiPackage mockGui = mock(GuiPackage.class);
        JMeterTreeModel mockModel = mock(JMeterTreeModel.class);
        JMeterTreeNode mockRoot = mock(JMeterTreeNode.class);

        when(mockGui.getTreeModel()).thenReturn(mockModel);
        when(mockModel.getRoot()).thenReturn(mockRoot);
        // getTestElement() returns null by default (Mockito) → serializeNode exits immediately
        when(mockRoot.getTestElement()).thenReturn(null);

        try (MockedStatic<GuiPackage> guiMock = mockStatic(GuiPackage.class)) {
            guiMock.when(GuiPackage::getInstance).thenReturn(mockGui);

            String result = TestPlanSerializer.serializeTestPlan();

            assertTrue(result.startsWith("=== JMeter Test Plan Structure ==="),
                    "Output must start with the standard header");
        }
    }

    // ── getIndent (private static) ─────────────────────────────────────────────

    @Test
    void getIndent_depthZero_returnsEmptyString() throws Exception {
        Method m = getIndentMethod();
        assertEquals("", m.invoke(null, 0));
    }

    @Test
    void getIndent_depthOne_returnsTwoSpaces() throws Exception {
        Method m = getIndentMethod();
        assertEquals("  ", m.invoke(null, 1));
    }

    @Test
    void getIndent_depthThree_returnsSixSpaces() throws Exception {
        Method m = getIndentMethod();
        assertEquals("      ", m.invoke(null, 3));
    }

    @Test
    void getIndent_depthFive_returnsTenSpaces() throws Exception {
        Method m = getIndentMethod();
        String result = (String) m.invoke(null, 5);
        assertEquals(10, result.length());
        assertTrue(result.isBlank(), "Indent must contain only spaces");
    }

    // ── truncateValue (private static) ────────────────────────────────────────

    @Test
    void truncateValue_shortValue_returnsUnchanged() throws Exception {
        Method m = getTruncateValueMethod();
        assertEquals("hello", m.invoke(null, "hello"));
    }

    @Test
    void truncateValue_valueWith500Chars_returnsUnchanged() throws Exception {
        Method m = getTruncateValueMethod();
        String value = "x".repeat(500);
        assertEquals(value, m.invoke(null, value));
    }

    @Test
    void truncateValue_valueWith501Chars_returnsTruncatedWithSuffix() throws Exception {
        Method m = getTruncateValueMethod();
        String value = "x".repeat(501);
        String result = (String) m.invoke(null, value);
        assertTrue(result.endsWith("... [truncated]"),
                "Values longer than 500 chars must end with '... [truncated]'");
        assertTrue(result.startsWith("x".repeat(500)),
                "First 500 characters must be preserved");
    }

    @Test
    void truncateValue_withNewlineCharacter_replacedWithLiteralBackslashN() throws Exception {
        Method m = getTruncateValueMethod();
        String result = (String) m.invoke(null, "line1\nline2");
        // \n (actual newline) becomes the two-char sequence \n (backslash + n)
        assertEquals("line1\\nline2", result);
    }

    @Test
    void truncateValue_withCarriageReturn_removedFromOutput() throws Exception {
        Method m = getTruncateValueMethod();
        String result = (String) m.invoke(null, "line1\rline2");
        assertEquals("line1line2", result);
    }

    @Test
    void truncateValue_withCrLf_crRemovedAndLfEscaped() throws Exception {
        Method m = getTruncateValueMethod();
        // Input: "line1" CR LF "line2"
        String result = (String) m.invoke(null, "line1\r\nline2");
        assertEquals("line1\\nline2", result);
    }

    @Test
    void truncateValue_emptyString_returnsEmptyString() throws Exception {
        Method m = getTruncateValueMethod();
        assertEquals("", m.invoke(null, ""));
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static Method getIndentMethod() throws Exception {
        Method m = TestPlanSerializer.class.getDeclaredMethod("getIndent", int.class);
        m.setAccessible(true);
        return m;
    }

    private static Method getTruncateValueMethod() throws Exception {
        Method m = TestPlanSerializer.class.getDeclaredMethod("truncateValue", String.class);
        m.setAccessible(true);
        return m;
    }
}
