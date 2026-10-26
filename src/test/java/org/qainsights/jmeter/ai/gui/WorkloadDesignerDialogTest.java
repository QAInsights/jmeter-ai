package org.qainsights.jmeter.ai.gui;

import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Rectangle;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentListener;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WorkloadDesignerDialogTest {

    @Test
    void hidesRemoteHostsCaptionByDefault() {
        JLabel label = WorkloadDesignerDialog.createHostsSourceLabel();

        assertFalse(label.isVisible());
    }

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

    @Test
    void sizesDialogWithinNinetyPercentOfUsableScreenBounds() {
        assertEquals(new Dimension(720, 486),
                WorkloadDesignerDialog.sizeWithinScreen(new Dimension(1140, 840),
                        new Rectangle(0, 0, 800, 600), new Insets(40, 0, 20, 0)));
        assertEquals(new Dimension(1140, 840),
                WorkloadDesignerDialog.sizeWithinScreen(new Dimension(1140, 840),
                        new Rectangle(0, 0, 1920, 1080), new Insets(40, 0, 40, 0)));
    }

    @Test
    void typingGeneratorHostsDefersAndCoalescesPreviewChanges() throws Exception {
        JTextField hosts = new JTextField();
        JTextField agents = new JTextField();
        JTextField prefix = new JTextField();
        AtomicBoolean updating = new AtomicBoolean();
        AtomicInteger updates = new AtomicInteger();
        DocumentListener listener = new WorkloadDesignerDialog.DeferredPreviewListener(() -> {
            updating.set(true);
            try {
                updates.incrementAndGet();
                agents.setText(Integer.toString(hosts.getText().split(",", -1).length));
            } finally {
                updating.set(false);
            }
        }, updating::get);
        hosts.getDocument().addDocumentListener(listener);
        agents.getDocument().addDocumentListener(listener);
        prefix.getDocument().addDocumentListener(listener);

        SwingUtilities.invokeAndWait(() -> {
            try {
                for (char character : "gen1,gen2".toCharArray()) {
                    hosts.getDocument().insertString(hosts.getDocument().getLength(),
                            Character.toString(character), null);
                }
                prefix.setText("checkout");
            } catch (javax.swing.text.BadLocationException e) {
                throw new IllegalStateException(e);
            }
        });
        SwingUtilities.invokeAndWait(() -> { });

        assertEquals("gen1,gen2", hosts.getText());
        assertEquals("checkout", prefix.getText());
        assertEquals("2", agents.getText());
        assertEquals(1, updates.get());
    }
}
