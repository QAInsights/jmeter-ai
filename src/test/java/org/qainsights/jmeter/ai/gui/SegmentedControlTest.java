package org.qainsights.jmeter.ai.gui;

import java.awt.Dimension;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JButton;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
