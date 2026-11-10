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
