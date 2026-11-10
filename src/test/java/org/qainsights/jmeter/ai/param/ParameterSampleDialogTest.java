package org.qainsights.jmeter.ai.param;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.function.Function;
import javax.swing.JCheckBox;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ParameterSampleDialogTest {
    @Test
    void csvPreflightUsesAnIsolatedSnapshotAndRejectsTheEdt(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        java.nio.file.Path file = directory.resolve("users.csv");
        java.nio.file.Files.writeString(file, "existing\n1234\n");
        org.apache.jmeter.testelement.TestPlan plan = new org.apache.jmeter.testelement.TestPlan();
        JMeterTreeNode root = new JMeterTreeNode(plan, null);
        JMeterTreeNode group = new JMeterTreeNode(new ThreadGroup(), null);
        root.add(group);
        org.apache.jmeter.config.CSVDataSet csv = new org.apache.jmeter.config.CSVDataSet();
        csv.setProperty("filename", file.toString());
        csv.setProperty("quotedData", true);
        group.add(new JMeterTreeNode(csv, null));
        JMeterTreeNode snapshot = ParameterizeThisDialog.snapshotScope(group);
        csv.setProperty("variableNames", "later");
        var result = ParameterizeThisDialog.readPreflight(file, null, new org.apache.jmeter.config.CSVDataSet(), snapshot, ',');
        assertEquals(java.util.List.of("existing"), result.oldNames());
        assertTrue(result.used().contains("existing"));
        assertFalse(result.used().contains("later"));
        assertTrue(result.exists());
        var empty = ParameterizeThisDialog.readPreflight(directory.resolve("new.csv"), null, null, snapshot, ',');
        assertFalse(empty.exists());
        assertTrue(empty.oldNames().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> ParameterizeThisDialog.readPreflight(file, file.toString(), null, snapshot, ','));
        SwingUtilities.invokeAndWait(() -> assertThrows(IllegalStateException.class,
                () -> ParameterizeThisDialog.readPreflight(file, null, null, snapshot, ',')));
    }

    @Test
    void providerAvailabilityAndBoundedRowCountControlTheOptionalFeature() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(java.awt.GraphicsEnvironment.isHeadless());
        if (JMeterUtils.getJMeterProperties() == null) {
            JMeterUtils.loadJMeterProperties("nonexistent.properties");
        }
        @SuppressWarnings("unchecked") Function<String, String> provider = mock(Function.class);
        SwingUtilities.invokeAndWait(() -> {
            for (boolean configured : new boolean[] {false, true}) {
                GuiPackage gui = mock(GuiPackage.class);
                JMeterTreeNode group = new JMeterTreeNode(new ThreadGroup(), null);
                HTTPSamplerProxy http = new HTTPSamplerProxy();
                http.addArgument("user", "alice");
                JMeterTreeNode scope = new JMeterTreeNode(http, null);
                group.add(scope);
                ParameterizeThisDialog dialog = new ParameterizeThisDialog(gui, scope,
                        configured ? provider : null);
                try {
                    JCheckBox ask = field(dialog, "askAi", JCheckBox.class);
                    JSpinner rows = field(dialog, "sampleCount", JSpinner.class);
                    assertEquals(configured, ask.isEnabled());
                    assertFalse(ask.isSelected());
                    assertEquals(ParameterizeThisDialog.sampleTooltip(configured), ask.getToolTipText());
                    assertFalse(rows.isEnabled());
                    SpinnerNumberModel model = (SpinnerNumberModel) rows.getModel();
                    assertEquals(10, model.getNumber());
                    assertEquals(1, model.getMinimum());
                    assertEquals(500, model.getMaximum());
                    if (configured) {
                        ask.doClick();
                        assertTrue(rows.isEnabled());
                    }
                } finally {
                    dialog.dispose();
                }
            }
        });
        verifyNoInteractions(provider);
    }

    private static <T> T field(Object target, String name, Class<T> type) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(target));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
