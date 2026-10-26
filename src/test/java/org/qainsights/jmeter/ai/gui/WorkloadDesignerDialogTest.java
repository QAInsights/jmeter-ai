package org.qainsights.jmeter.ai.gui;

import javax.swing.JComboBox;
import javax.swing.JTextField;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkloadDesignerDialogTest {

    @Test
    void prefillsAnHourLongRampAsOneHour() {
        JTextField value = new JTextField();
        JComboBox<String> unit = new JComboBox<>(new String[]{"s", "min", "h"});

        WorkloadDesignerDialog.setDuration(value, unit, 3600L);

        assertEquals("1", value.getText());
        assertEquals("h", unit.getSelectedItem());
    }

    @Test
    void selectsOnlyUnitsThatTheComboContains() {
        JTextField value = new JTextField();
        JComboBox<String> unit = new JComboBox<>(new String[]{"s", "min"});

        WorkloadDesignerDialog.setDuration(value, unit, 3600L);

        assertEquals("60", value.getText());
        assertEquals("min", unit.getSelectedItem());
    }
}
