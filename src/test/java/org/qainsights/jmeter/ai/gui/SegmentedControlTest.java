package org.qainsights.jmeter.ai.gui;

import java.awt.Dimension;
import java.awt.FlowLayout;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JButton;
import javax.swing.JTextField;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.gui.theme.UiTokens;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SegmentedControlTest {

    @Test
    void wrapLayoutReportsTwoRowsAtTheAvailableWidth() {
        JPanel panel = new JPanel(new WrapLayout());
        JLabel first = new JLabel("first");
        JLabel second = new JLabel("second");
        first.setPreferredSize(new Dimension(60, 20));
        second.setPreferredSize(new Dimension(60, 20));
        panel.add(first);
        panel.add(second);

        Dimension preferred = ((WrapLayout) panel.getLayout()).preferredLayoutSizeForWidth(panel, 100);

        assertEquals(44, preferred.height);
    }

    @Test
    void compactTrackCentersItsSegmentsVertically() {
        JComboBox<String> combo = new JComboBox<>(new String[]{"s", "min", "h"});
        int trackHeight = UiTokens.CONTROL_HEIGHT - UiTokens.SPACE_2;
        SegmentedControl<String> control = new SegmentedControl<>(combo, value -> value,
                FlowLayout.RIGHT, UiTokens.SPACE_2, 2, trackHeight);

        int preferredWidth = control.getPreferredSize().width;
        control.setSize(Math.max(1, preferredWidth / 2), trackHeight);
        assertEquals(preferredWidth, control.getPreferredSize().width);

        control.setSize(200, trackHeight);
        control.doLayout();

        JButton firstPill = (JButton) control.getComponent(0);
        assertEquals(trackHeight, control.getPreferredSize().height);
        assertEquals((trackHeight - firstPill.getPreferredSize().height) / 2, firstPill.getY());
    }

    @Test
    void fieldShellKeepsCompactSegmentsOnOneRow() {
        JComboBox<String> combo = new JComboBox<>(new String[]{"s", "min", "h"});
        SegmentedControl<String> control = new SegmentedControl<>(combo, value -> value,
                FlowLayout.RIGHT, UiTokens.SPACE_2, 2, UiTokens.CONTROL_HEIGHT - UiTokens.SPACE_2);
        int preferredWidth = control.getPreferredSize().width;
        FieldShell shell = new FieldShell(new JTextField(), control);
        shell.setSize(240, UiTokens.CONTROL_HEIGHT);
        shell.doLayout();
        JPanel trailingSlot = (JPanel) shell.getComponent(1);
        trailingSlot.doLayout();
        control.doLayout();

        assertTrue(control.getWidth() >= preferredWidth);
        assertEquals(UiTokens.SPACE_2,
                trailingSlot.getWidth() - control.getX() - control.getWidth());
        assertEquals((UiTokens.CONTROL_HEIGHT - control.getPreferredSize().height) / 2,
                control.getY());
        assertEquals(((JButton) control.getComponent(0)).getY(),
                ((JButton) control.getComponent(2)).getY());
    }

    @Test
    void comboAndSegmentedControlStayBoundInBothDirections() {
        JComboBox<String> combo = new JComboBox<>(new String[]{"one", "two", "three"});
        SegmentedControl<String> control = new SegmentedControl<>(combo, value -> value);

        combo.setSelectedItem("three");
        assertEquals(2, control.selectedIndex());

        ((JButton) control.getComponent(0)).doClick();
        assertEquals("one", combo.getSelectedItem());
        assertEquals(0, control.selectedIndex());
    }
}
