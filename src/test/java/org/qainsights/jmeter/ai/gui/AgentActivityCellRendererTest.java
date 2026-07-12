package org.qainsights.jmeter.ai.gui;

import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import javax.swing.JLabel;
import javax.swing.JTree;
import javax.swing.border.Border;
import javax.swing.tree.TreeCellRenderer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link AgentActivityCellRenderer}. */
class AgentActivityCellRendererTest {

    /** Always returns a fresh {@link JLabel}, mirroring a real cell renderer's contract. */
    private static final class FakeDelegate implements TreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded,
                                                        boolean leaf, int row, boolean hasFocus) {
            return new JLabel(String.valueOf(value));
        }
    }

    @Test
    void activeRow_getsAPaintingBorder() {
        AgentActivityCellRenderer renderer = new AgentActivityCellRenderer(new FakeDelegate(), () -> "node-a",
                () -> 0, () -> 1f);

        Component rendered = renderer.getTreeCellRendererComponent(null, "node-a", false, false, true, 0, false);

        Border border = ((JLabel) rendered).getBorder();
        assertTrue(border instanceof AgentGlowBorder);
    }

    @Test
    void inactiveRow_getsANonPaintingBorderWithTheSameInsets() {
        AgentActivityCellRenderer renderer = new AgentActivityCellRenderer(new FakeDelegate(), () -> "node-a",
                () -> 0, () -> 1f);

        Component activeRendered = renderer.getTreeCellRendererComponent(null, "node-a", false, false, true, 0, false);
        Component inactiveRendered = renderer.getTreeCellRendererComponent(null, "node-b", false, false, true, 1, false);

        Border activeBorder = ((JLabel) activeRendered).getBorder();
        Border inactiveBorder = ((JLabel) inactiveRendered).getBorder();
        assertNotSame(activeBorder, inactiveBorder);
        assertEquals(activeBorder.getBorderInsets(activeRendered), inactiveBorder.getBorderInsets(inactiveRendered));
    }

    @Test
    void nullActiveTarget_leavesEveryRowWithTheNonPaintingBorder() {
        AgentActivityCellRenderer renderer = new AgentActivityCellRenderer(new FakeDelegate(), () -> null,
                () -> 0, () -> 1f);

        Component rendered = renderer.getTreeCellRendererComponent(null, "node-a", false, false, true, 0, false);
        Border border = ((JLabel) rendered).getBorder();

        BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        assertDoesNotThrow(() -> border.paintBorder(rendered, g, 0, 0, 10, 10));
        g.dispose();
    }

    @Test
    void nullValue_doesNotMatchAnyActiveTarget() {
        AgentActivityCellRenderer renderer = new AgentActivityCellRenderer(new FakeDelegate(), () -> "node-a",
                () -> 0, () -> 1f);

        Component rendered = renderer.getTreeCellRendererComponent(null, null, false, false, true, 0, false);

        assertNotNull(((JLabel) rendered).getBorder());
    }

    @Test
    void delegateReturningNonJComponent_isPassedThroughUnmodified() {
        TreeCellRenderer delegate = (tree, value, selected, expanded, leaf, row, hasFocus) -> null;
        AgentActivityCellRenderer renderer = new AgentActivityCellRenderer(delegate, () -> "node-a",
                () -> 0, () -> 1f);

        assertNull(renderer.getTreeCellRendererComponent(null, "node-a", false, false, true, 0, false));
    }
}
