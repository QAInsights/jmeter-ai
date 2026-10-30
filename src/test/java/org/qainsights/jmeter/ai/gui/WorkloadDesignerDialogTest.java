package org.qainsights.jmeter.ai.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

import javax.swing.BoxLayout;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.DefaultCaret;

import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.load.ArrivalMode;
import org.qainsights.jmeter.ai.load.CommandShell;
import org.qainsights.jmeter.ai.load.LoadCommandHandler;
import org.qainsights.jmeter.ai.load.LoadShapeChart;
import org.qainsights.jmeter.ai.load.WorkloadApplier;
import org.qainsights.jmeter.ai.load.WorkloadCalculator;
import org.qainsights.jmeter.ai.load.WorkloadFormState;
import org.qainsights.jmeter.ai.load.WorkloadField;
import org.qainsights.jmeter.ai.load.WorkloadInputs;
import org.qainsights.jmeter.ai.load.WorkloadPlan;
import org.qainsights.jmeter.ai.load.WorkloadParser;
import org.qainsights.jmeter.ai.load.WorkloadShape;
import org.qainsights.jmeter.ai.load.WorkloadShapeConfig;
import org.qainsights.jmeter.ai.load.WorkloadSpec;
import org.qainsights.jmeter.ai.gui.theme.UiTokens;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class WorkloadDesignerDialogTest {

    @Test
    void hidesRemoteHostsCaptionByDefault() {
        JLabel label = WorkloadDesignerDialog.createHostsSourceLabel();

        assertFalse(label.isVisible());
    }

    @Test
    void wrapsKpiTitlesWhenTheyExceedTheAvailableWidth() {
        FontMetrics metrics = new JLabel().getFontMetrics(new Font(Font.SANS_SERIF, Font.PLAIN, 12));

        assertEquals("<html>Generators<br>needed</html>",
                WorkloadDesignerDialog.wrapKpiTitle("Generators needed", metrics,
                        metrics.stringWidth("Generators")));
        assertEquals("Threads", WorkloadDesignerDialog.wrapKpiTitle("Threads", metrics,
                metrics.stringWidth("Generators")));
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
    void derivesBlankShapeUpperPlaceholdersFromTheCurrentInputs() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        Preferences preferences = Preferences.userRoot()
                .node("org/qainsights/jmeter/ai/gui/shapes-" + UUID.randomUUID());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                WorkloadDesignerDialog dialog = null;
                try {
                    JMeterTreeNode group =
                            new JMeterTreeNode(new org.apache.jmeter.threads.ThreadGroup(), null);
                    group.add(new JMeterTreeNode(
                            new org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy(), null));
                    LoadCommandHandler.Preparation preparation =
                            new LoadCommandHandler.Preparation(null, WorkloadParser.parse("@load"),
                                    List.of(group), group, 1, null, "plan.jmx");
                    dialog = new WorkloadDesignerDialog(preparation, ignored -> { }, ignored -> "", preferences);
                    dialog.setSize(1140, 840);
                    dialog.addNotify();

                    JComboBox<WorkloadShape> shape = dialogField(dialog, "shapeCombo", JComboBox.class);
                    shape.setSelectedItem(WorkloadShape.STEP);
                    dialogField(dialog, "stepStart", JTextField.class).setText("1000");
                    dialogField(dialog, "stepSize", JTextField.class).setText("");
                    dialogField(dialog, "stepEnd", JTextField.class).setText("");
                    invokePrivate(dialog, "updatePreview");
                    assertEquals("1400", dialogField(dialog, "stepEnd", PlaceholderTextField.class).placeholder());
                    assertTrue(dialogField(dialog, "evaluation", WorkloadFormState.Evaluation.class).valid());

                    shape.setSelectedItem(WorkloadShape.STRESS);
                    dialogField(dialog, "stressStart", JTextField.class).setText("2000");
                    dialogField(dialog, "stressStep", JTextField.class).setText("");
                    dialogField(dialog, "stressCeiling", JTextField.class).setText("");
                    invokePrivate(dialog, "updatePreview");
                    assertEquals("2900",
                            dialogField(dialog, "stressCeiling", PlaceholderTextField.class).placeholder());

                    shape.setSelectedItem(WorkloadShape.SPIKE);
                    dialogField(dialog, "spikeBaseline", JTextField.class).setText("300");
                    dialogField(dialog, "spikePeak", JTextField.class).setText("");
                    invokePrivate(dialog, "updatePreview");
                    assertEquals("1500",
                            dialogField(dialog, "spikePeak", PlaceholderTextField.class).placeholder());

                    shape.setSelectedItem(WorkloadShape.STEP);
                    dialogField(dialog, "stepStart", JTextField.class).setText("");
                    dialogField(dialog, "stepSize", JTextField.class).setText("");
                    invokePrivate(dialog, "updatePreview");
                    assertEquals("500", dialogField(dialog, "stepEnd", PlaceholderTextField.class).placeholder());
                } catch (Throwable exception) {
                    failure.set(exception);
                } finally {
                    if (dialog != null) {
                        dialog.dispose();
                    }
                }
            });
            if (failure.get() != null) {
                throw new AssertionError("Shape placeholder defaults failed", failure.get());
            }
        } finally {
            preferences.removeNode();
        }
    }

    @Test
    void formFieldAndKpiColumnsRespondToTheirWidthThresholds() {
        assertEquals(1, WorkloadDesignerDialog.fieldsPerRow(419));
        assertEquals(2, WorkloadDesignerDialog.fieldsPerRow(420));
        assertEquals(1, WorkloadDesignerDialog.targetFieldsPerRow(500, 300, 220, false));
        assertEquals(2, WorkloadDesignerDialog.targetFieldsPerRow(600, 300, 220, false));
        assertEquals(1, WorkloadDesignerDialog.targetFieldsPerRow(600, 300, 220, true));
        assertEquals(2, WorkloadDesignerDialog.kpiColumns(599));
        assertEquals(4, WorkloadDesignerDialog.kpiColumns(600));
    }

    @Test
    void wideColumnConstraintsKeepColumnsTopAlignedWithoutStretchingTheirContents() {
        javax.swing.JPanel columns = new javax.swing.JPanel(new java.awt.GridBagLayout());
        javax.swing.JPanel form = new javax.swing.JPanel();
        javax.swing.JPanel preview = new javax.swing.JPanel();
        form.setPreferredSize(new Dimension(100, 80));
        preview.setPreferredSize(new Dimension(100, 160));
        columns.add(form, WorkloadDesignerDialog.wideColumnConstraints(0, 12));
        columns.add(preview, WorkloadDesignerDialog.wideColumnConstraints(1, 0));
        columns.add(new javax.swing.JPanel(), WorkloadDesignerDialog.wideColumnFillerConstraints());

        columns.setSize(300, 400);
        columns.doLayout();

        assertEquals(0, form.getY());
        assertEquals(80, form.getHeight());
        assertEquals(0, preview.getY());
        assertEquals(160, preview.getHeight());
    }

    @Test
    void commandBlockOverlaysCopyAtTopRightAndReservesTextSpace() {
        JTextArea text = new JTextArea(3, 20);
        JButton copy = new JButton("Copy");
        WorkloadDesignerDialog.CodeBlockPanel block =
                new WorkloadDesignerDialog.CodeBlockPanel(text, copy);
        block.setSize(420, 100);

        block.doLayout();

        Insets blockInsets = block.getInsets();
        assertEquals(blockInsets.top, copy.getY());
        assertEquals(block.getWidth() - blockInsets.right, copy.getX() + copy.getWidth());
        assertTrue(text.getX() + text.getWidth() - text.getInsets().right + UiTokens.SPACE_2
                <= copy.getX());
    }

    @Test
    void dialogControlsKeepTheirPreferredHeightsAcrossResizeAndRefresh() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        Preferences preferences = Preferences.userRoot()
                .node("org/qainsights/jmeter/ai/gui/layout-" + UUID.randomUUID());
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try {
            SwingUtilities.invokeAndWait(() -> {
                WorkloadDesignerDialog dialog = null;
                try {
                    JMeterTreeNode group =
                            new JMeterTreeNode(new org.apache.jmeter.threads.ThreadGroup(), null);
                    group.add(new JMeterTreeNode(
                            new org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy(), null));
                    LoadCommandHandler.Preparation preparation =
                            new LoadCommandHandler.Preparation(null, WorkloadParser.parse("@load"),
                                    List.of(group), group, 1, null, "plan.jmx");
                    dialog = new WorkloadDesignerDialog(preparation, ignored -> { }, ignored -> "", preferences);
                    dialog.setSize(1140, 840);
                    dialog.addNotify();
                    layoutDialog(dialog);

                    dialogField(dialog, "shapeCombo", JComboBox.class)
                            .setSelectedItem(WorkloadShape.LOAD);
                    JTextField target = dialogField(dialog, "target", JTextField.class);
                    target.setText("");
                    invokePrivate(dialog, "updatePreview");
                    FieldShell targetShell =
                            dialogField(dialog, "targetValueShell", FieldShell.class);
                    Field invalidField = FieldShell.class.getDeclaredField("invalid");
                    invalidField.setAccessible(true);
                    assertFalse(invalidField.getBoolean(targetShell));
                    LoadShapeChart chart = dialogField(dialog, "chart", LoadShapeChart.class);
                    Field emptyMessage = LoadShapeChart.class.getDeclaredField("emptyMessage");
                    emptyMessage.setAccessible(true);
                    Field emptyMessageIsError =
                            LoadShapeChart.class.getDeclaredField("emptyMessageIsError");
                    emptyMessageIsError.setAccessible(true);
                    assertEquals("Enter a target to preview the load shape", emptyMessage.get(chart));
                    assertFalse(emptyMessageIsError.getBoolean(chart));

                    dialogField(dialog, "askAiButton", JButton.class).doClick(0);
                    dialogField(dialog, "shapeCombo", JComboBox.class).setSelectedItem(WorkloadShape.CUSTOM);
                    JButton addStage = findButton(dialog, "Add stage");
                    assertTrue(addStage != null, "Custom stage controls should be visible");
                    addStage.doClick(0);
                    addStage.doClick(0);
                    addStage.doClick(0);

                    JTextArea command = dialogField(dialog, "cliArea", JTextArea.class);
                    command.setText(String.join("\n", List.of(
                            "stage 1 --target 100 --ramp 10 --hold 60",
                            "stage 2 --target 200 --ramp 10 --hold 60",
                            "stage 3 --target 300 --ramp 10 --hold 60",
                            "stage 4 --target 400 --ramp 10 --hold 60",
                            "stage 5 --target 500 --ramp 10 --hold 60",
                            "stage 6 --target 600 --ramp 10 --hold 60",
                            "stage 7 --target 700 --ramp 10 --hold 60",
                            "stage 8 --target 800 --ramp 10 --hold 60",
                            "stage 9 --target 900 --ramp 10 --hold 60",
                            "stage 10 --target 1000 --ramp 10 --hold 60")));
                    WorkloadDesignerDialog.updateCommandRows(command);
                    layoutDialog(dialog);
                    assertDialogControlHeights(dialog, "1140x840");

                    dialog.setSize(640, 840);
                    invokePrivate(dialog, "updateColumnsLayout");
                    layoutDialog(dialog);
                    assertDialogControlHeights(dialog, "640x840");

                    dialog.setSize(1140, 840);
                    invokePrivate(dialog, "updateColumnsLayout");
                    layoutDialog(dialog);
                    assertDialogControlHeights(dialog, "1140x840 after resize");

                    invokePrivate(dialog, "refreshLookAndFeel");
                    layoutDialog(dialog);
                    assertDialogControlHeights(dialog, "1140x840 after refresh");

                    JTextArea goal = dialogField(dialog, "aiGoal", JTextArea.class);
                    goal.setText("g".repeat(1_000));
                    goal.getDocument().insertString(goal.getDocument().getLength(), "x", null);
                    assertEquals(1_000, goal.getText().length());
                    Object textUi = goal.getUI();
                    Method zoomRefresh = WorkloadDesignerDialog.class.getDeclaredMethod(
                            "refreshLookAndFeel", boolean.class);
                    zoomRefresh.setAccessible(true);
                    zoomRefresh.invoke(dialog, false);
                    assertTrue(textUi == goal.getUI(),
                            "Zoom styling refresh should not reinstall the text UI");

                    Map<String, Font> originalFonts = new LinkedHashMap<>();
                    for (String key : List.of("Label.font", "TextField.font", "TextArea.font",
                            "Button.font", "ComboBox.font", "Table.font")) {
                        Font font = UIManager.getFont(key);
                        if (font != null) {
                            originalFonts.put(key, font);
                            UIManager.put(key, font.deriveFont(font.getSize2D() * 1.1f));
                        }
                    }
                    try {
                        dialog.setSize(1700, 840);
                        invokePrivate(dialog, "updateColumnsLayout");
                        invokePrivate(dialog, "refreshLookAndFeel");
                        layoutDialog(dialog);
                        assertDialogControlHeights(dialog, "1700x840 after zoom");

                        dialog.setSize(1140, 840);
                        invokePrivate(dialog, "updateColumnsLayout");
                        layoutDialog(dialog);
                        assertDialogControlHeights(dialog, "1140x840 after zoom and resize");
                        assertWideColumnSizing(dialog, "1140x840 after zoom and resize");

                        dialog.setSize(900, 840);
                        invokePrivate(dialog, "updateColumnsLayout");
                        layoutDialog(dialog);
                        WorkloadDesignerDialog.updateCommandRows(
                                dialogField(dialog, "cliArea", JTextArea.class));
                        layoutDialog(dialog);
                        assertTrue(dialogField(dialog, "columns", JPanel.class)
                                .getLayout() instanceof GridBagLayout,
                                "900 px should retain the two-column layout");
                        assertDialogControlHeights(dialog, "900x840");

                        dialog.setSize(899, 840);
                        invokePrivate(dialog, "updateColumnsLayout");
                        layoutDialog(dialog);
                        assertTrue(dialogField(dialog, "columns", JPanel.class)
                                .getLayout() instanceof BoxLayout,
                                "899 px should switch to the stacked layout");
                        assertDialogControlHeights(dialog, "899x840");

                        dialogField(dialog, "shapeCombo", JComboBox.class).setSelectedItem(WorkloadShape.LOAD);
                        invokePrivate(dialog, "updatePreview");
                        target.setText("9999999999");
                        invokePrivate(dialog, "updatePreview");
                        assertTrue(invalidField.getBoolean(targetShell));
                        assertFalse(dialogField(dialog, "applyButton", JButton.class).isEnabled());
                        Field plan = LoadShapeChart.class.getDeclaredField("plan");
                        plan.setAccessible(true);
                        String error = dialogField(dialog, "evaluation", WorkloadFormState.Evaluation.class).error();
                        assertEquals(error, emptyMessage.get(chart));
                        assertTrue(emptyMessageIsError.getBoolean(chart));
                        assertNull(plan.get(chart));

                        target.setText("100");
                        invokePrivate(dialog, "updatePreview");
                        assertFalse(invalidField.getBoolean(targetShell));
                        assertFalse(emptyMessageIsError.getBoolean(chart));
                        assertTrue(dialogField(dialog, "applyButton", JButton.class).isEnabled());
                        assertTrue(plan.get(chart) != null);

                        dialog.showAiSuggestionForRender(new org.qainsights.jmeter.ai.load.WorkloadAiAssistant.Suggestion(
                                WorkloadInputs.TargetType.REQUEST_RATE, 1.0E7, null, 500.0, 0.0,
                                0.0, 60.0, null, "Large target", List.of(), List.of()));
                        assertEquals("10000000", target.getText());
                        target.setText("100");

                        JComboBox shapeCombo = dialogField(dialog, "shapeCombo", JComboBox.class);
                        shapeCombo.setSelectedItem(WorkloadShape.STEP);
                        JTextField start = dialogField(dialog, "stepStart", JTextField.class);
                        JTextField end = dialogField(dialog, "stepEnd", JTextField.class);
                        start.setText("1000");
                        end.setText("500");
                        invokePrivate(dialog, "updatePreview");
                        WorkloadFormState.Evaluation endEvaluation =
                                dialogField(dialog, "evaluation", WorkloadFormState.Evaluation.class);
                        FieldShell endShell = (FieldShell) ((Map<?, ?>) dialogField(
                                dialog, "fieldShells", Map.class)).get(end);
                        assertEquals(WorkloadField.SHAPE_END, endEvaluation.field());
                        assertTrue(invalidField.getBoolean(endShell));
                        assertEquals(endEvaluation.error(), emptyMessage.get(chart));
                        assertTrue(emptyMessageIsError.getBoolean(chart));

                        dialogField(dialog, "shapeCombo", JComboBox.class)
                                .setSelectedItem(WorkloadShape.CUSTOM);
                        invokePrivate(dialog, "updatePreview");
                        JTable stages = dialogField(dialog, "customStagesTable", JTable.class);
                        stages.setValueAt("999999999999999999999", 0, 0);
                        invokePrivate(dialog, "updatePreview");
                        WorkloadFormState.Evaluation customEvaluation =
                                dialogField(dialog, "evaluation", WorkloadFormState.Evaluation.class);
                        assertEquals(WorkloadField.CUSTOM_STAGES, customEvaluation.field());
                        assertTrue(customEvaluation.error().contains("Custom stage 1 target"));
                        assertTrue(customEvaluation.error().contains("1,000,000 samples/s"));
                        assertTrue(invalidField.getBoolean(
                                dialogField(dialog, "customStagesShell", FieldShell.class)));
                    } finally {
                        originalFonts.forEach(UIManager::put);
                        invokePrivate(dialog, "refreshLookAndFeel");
                    }
                } catch (Throwable exception) {
                    failure.set(exception);
                } finally {
                    if (dialog != null) {
                        dialog.dispose();
                    }
                }
            });
            if (failure.get() != null) {
                throw new AssertionError("Dialog layout reproduction failed", failure.get());
            }
        } finally {
            preferences.removeNode();
        }
    }

    private static <T> T dialogField(WorkloadDesignerDialog dialog, String name, Class<T> type)
            throws ReflectiveOperationException {
        Field field = WorkloadDesignerDialog.class.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(dialog));
    }

    private static void invokePrivate(WorkloadDesignerDialog dialog, String name)
            throws ReflectiveOperationException {
        Method method = WorkloadDesignerDialog.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(dialog);
    }

    private static JButton findButton(Container container, String text) {
        for (Component component : container.getComponents()) {
            if (component instanceof JButton button && text.equals(button.getText())) {
                return button;
            }
            if (component instanceof Container child) {
                JButton match = findButton(child, text);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private static void assertWideColumnSizing(WorkloadDesignerDialog dialog, String stage)
            throws ReflectiveOperationException {
        JPanel columns = dialogField(dialog, "columns", JPanel.class);
        JPanel form = dialogField(dialog, "form", JPanel.class);
        JScrollPane bodyScroll = dialogField(dialog, "bodyScroll", JScrollPane.class);
        JComponent chart = dialogField(dialog, "chart", JComponent.class);
        int contentWidth = bodyScroll.getViewport().getExtentSize().width;
        JLabel threadGroupLabel = findLabel(form, "Thread Group");

        assertTrue(columns.getPreferredSize().width <= contentWidth,
                stage + " preferred body width exceeds viewport: " + columns.getPreferredSize()
                        + " vs " + contentWidth);
        assertTrue(chart.getPreferredSize().width <= contentWidth,
                stage + " chart preferred width exceeds viewport: " + chart.getPreferredSize()
                        + " vs " + contentWidth);
        assertTrue(form.getWidth() >= Math.ceil(contentWidth * 0.4),
                stage + " form width is below 40%: " + form.getWidth() + " / " + contentWidth);
        assertTrue(threadGroupLabel != null
                        && threadGroupLabel.getWidth() >= threadGroupLabel.getPreferredSize().width,
                stage + " Thread Group label is clipped: " + threadGroupLabel);
    }

    private static JLabel findLabel(Container container, String text) {
        for (Component component : container.getComponents()) {
            if (component instanceof JLabel label && text.equals(label.getText())) {
                return label;
            }
            if (component instanceof Container child) {
                JLabel match = findLabel(child, text);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private static void layoutDialog(WorkloadDesignerDialog dialog) {
        for (int i = 0; i < 3; i++) {
            dialog.validate();
            layoutRecursively(dialog);
        }
    }

    private static void layoutRecursively(Container container) {
        container.doLayout();
        for (Component component : container.getComponents()) {
            if (component instanceof Container child) {
                layoutRecursively(child);
            }
        }
    }

    private static void assertDialogControlHeights(WorkloadDesignerDialog dialog, String stage)
            throws ReflectiveOperationException {
        JTextArea goal = dialogField(dialog, "aiGoal", JTextArea.class);
        JTextArea command = dialogField(dialog, "cliArea", JTextArea.class);
        JTable stages = dialogField(dialog, "customStagesTable", JTable.class);
        FieldShell goalShell = (FieldShell) SwingUtilities.getAncestorOfClass(FieldShell.class, goal);
        JScrollPane goalScroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, goal);
        WorkloadDesignerDialog.CodeBlockPanel codeBlock =
                (WorkloadDesignerDialog.CodeBlockPanel) SwingUtilities.getAncestorOfClass(
                        WorkloadDesignerDialog.CodeBlockPanel.class, command);
        JScrollPane stagesScroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, stages);
        String metrics = "goal shell " + componentSizes(goalShell)
                + "; command block " + componentSizes(codeBlock)
                + "; command area " + componentSizes(command)
                + "; custom table scroll " + componentSizes(stagesScroll);

        assertTrue(goalShell.getHeight() >= goalShell.getPreferredSize().height,
                stage + " goal shell is compressed: " + metrics);
        assertEquals(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER, goalScroll.getHorizontalScrollBarPolicy(),
                stage + " goal scroll policy: " + metrics);
        assertTrue(command.getRows() >= 10, stage + " command rows: " + metrics);
        assertTrue(codeBlock.getHeight() >= codeBlock.getPreferredSize().height,
                stage + " command block is compressed: " + metrics);
        assertTrue(command.getHeight() >= command.getPreferredSize().height,
                stage + " command area is compressed: " + metrics);
        assertTrue(stagesScroll.getHeight() >= stagesScroll.getPreferredSize().height,
                stage + " custom table is compressed: " + metrics);
        assertTrue(stagesScroll.getPreferredSize().width <= stagesScroll.getWidth(),
                stage + " custom table preferred width exceeds its available width: " + metrics);
        Container stagesBody = stagesScroll.getParent();
        assertTrue(stagesBody.getPreferredSize().width <= stagesBody.getWidth(),
                stage + " custom table body preferred width exceeds its available width: " + metrics);
    }

    private static String componentSizes(Component component) {
        StringBuilder sizes = new StringBuilder();
        for (Component current = component; current != null; current = current.getParent()) {
            if (!sizes.isEmpty()) {
                sizes.append(" <- ");
            }
            sizes.append(current.getClass().getSimpleName())
                    .append("{bounds=").append(current.getBounds())
                    .append(", pref=").append(current.getPreferredSize())
                    .append(", min=").append(current.getMinimumSize());
            if (current instanceof Container container && container.getLayout() != null) {
                sizes.append(", layout=").append(container.getLayout().getClass().getSimpleName());
            }
            sizes.append('}');
        }
        return sizes.toString();
    }

    @Test
    void makesTextAreasTransparentForTheirParentSurface() {
        JTextArea area = new JTextArea();

        area.updateUI();
        WorkloadDesignerDialog.styleReadOnlyArea(area);

        assertFalse(area.isOpaque());
        assertEquals(0, area.getBackground().getAlpha());
        assertEquals(DefaultCaret.NEVER_UPDATE,
                ((DefaultCaret) area.getCaret()).getUpdatePolicy());
    }

    @Test
    void expandedAdvancedCardContainsBothRows() {
        SectionCard advancedCard = new SectionCard("Advanced");
        JLabel samplesLabel = new JLabel("Samples/s per vCPU");
        JPanel samplesField = new JPanel();
        JLabel prefixLabel = new JLabel("Property prefix");
        JPanel prefixField = new JPanel();
        List<WorkloadDesignerDialog.FormRow> rows = List.of(
                new WorkloadDesignerDialog.FormRow(samplesLabel, samplesField, advancedCard, false),
                new WorkloadDesignerDialog.FormRow(prefixLabel, prefixField, advancedCard, false));

        WorkloadDesignerDialog.populateFormCardBody(
                advancedCard, rows, 1, advancedCard, true, null, false, null, false);

        assertEquals(2, advancedCard.body().getComponentCount());
        assertTrue(samplesLabel.getParent() != null);
        assertTrue(prefixLabel.getParent() != null);
        GridBagLayout layout = (GridBagLayout) advancedCard.body().getLayout();
        assertEquals(0, layout.getConstraints(advancedCard.body().getComponent(0)).insets.right);
    }

    @Test
    void onlyAdvancedFieldErrorsExpandTheAdvancedCard() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        Preferences preferences = Preferences.userRoot()
                .node("org/qainsights/jmeter/ai/gui/advanced-" + UUID.randomUUID());
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try {
            SwingUtilities.invokeAndWait(() -> {
                WorkloadDesignerDialog dialog = null;
                try {
                    JMeterTreeNode group =
                            new JMeterTreeNode(new org.apache.jmeter.threads.ThreadGroup(), null);
                    group.add(new JMeterTreeNode(
                            new org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy(), null));
                    LoadCommandHandler.Preparation preparation =
                            new LoadCommandHandler.Preparation(null, WorkloadParser.parse("@load"),
                                    List.of(group), group, 1, null, "plan.jmx");
                    dialog = new WorkloadDesignerDialog(preparation, ignored -> { }, ignored -> "", preferences);
                    dialog.setSize(1140, 840);
                    dialog.addNotify();

                    SectionCard advancedCard = dialogField(dialog, "advancedCard", SectionCard.class);
                    JTextField target = dialogField(dialog, "target", JTextField.class);
                    JTextField ram = dialogField(dialog, "generatorRamGb", JTextField.class);
                    JTextField samples = dialogField(dialog, "samplesPerVcpu", JTextField.class);
                    Map<?, ?> fieldShells = dialogField(dialog, "fieldShells", Map.class);
                    FieldShell ramShell = (FieldShell) fieldShells.get(ram);
                    FieldShell samplesShell = (FieldShell) fieldShells.get(samples);
                    Field invalidField = FieldShell.class.getDeclaredField("invalid");
                    invalidField.setAccessible(true);

                    dialogField(dialog, "shapeCombo", JComboBox.class).setSelectedItem(WorkloadShape.LOAD);
                    target.setText("100");
                    invokePrivate(dialog, "updatePreview");
                    assertFalse(advancedCard.body().isVisible());

                    ram.setText("4097");
                    invokePrivate(dialog, "updatePreview");
                    assertEquals(WorkloadField.RAM,
                            dialogField(dialog, "evaluation", WorkloadFormState.Evaluation.class).field());
                    assertFalse(advancedCard.body().isVisible());
                    assertTrue(invalidField.getBoolean(ramShell));

                    ram.setText("4");
                    samples.setText("0");
                    invokePrivate(dialog, "updatePreview");
                    assertEquals(WorkloadField.SAMPLES_PER_VCPU,
                            dialogField(dialog, "evaluation", WorkloadFormState.Evaluation.class).field());
                    assertTrue(advancedCard.body().isVisible());
                    assertTrue(invalidField.getBoolean(samplesShell));
                } catch (Throwable exception) {
                    failure.set(exception);
                } finally {
                    if (dialog != null) {
                        dialog.dispose();
                    }
                }
            });
            if (failure.get() != null) {
                throw new AssertionError("Advanced field expansion failed", failure.get());
            }
        } finally {
            preferences.removeNode();
        }
    }

    @Test
    void customRowsAndAiSuggestionsUpdateValidationAndVisibility() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        Preferences preferences = Preferences.userRoot()
                .node("org/qainsights/jmeter/ai/gui/review-" + UUID.randomUUID());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                WorkloadDesignerDialog dialog = null;
                try {
                    JMeterTreeNode group = new JMeterTreeNode(new org.apache.jmeter.threads.ThreadGroup(), null);
                    group.add(new JMeterTreeNode(new org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy(), null));
                    LoadCommandHandler.Preparation preparation = new LoadCommandHandler.Preparation(
                            null, WorkloadParser.parse("@load"), List.of(group), group, 1, null, "plan.jmx");
                    dialog = new WorkloadDesignerDialog(preparation, ignored -> { }, ignored -> "", preferences);
                    java.beans.PropertyChangeListener listener = dialogField(
                            dialog, "lookAndFeelListener", java.beans.PropertyChangeListener.class);
                    assertFalse(List.of(UIManager.getPropertyChangeListeners()).contains(listener));
                    dialog.addNotify();
                    dialog.addNotify();
                    assertEquals(1, List.of(UIManager.getPropertyChangeListeners()).stream()
                            .filter(item -> item == listener).count());
                    dialog.removeNotify();
                    assertFalse(List.of(UIManager.getPropertyChangeListeners()).contains(listener));
                    dialog.addNotify();

                    dialogField(dialog, "shapeCombo", JComboBox.class).setSelectedItem(WorkloadShape.CUSTOM);
                    JTable table = dialogField(dialog, "customStagesTable", JTable.class);
                    DefaultTableModel model = (DefaultTableModel) table.getModel();
                    model.addRow(new Object[]{"200", "10", "60"});
                    Field invalid = FieldShell.class.getDeclaredField("invalid");
                    invalid.setAccessible(true);
                    for (int column = 0; column < 3; column++) {
                        Object original = model.getValueAt(1, column);
                        model.setValueAt("", 1, column);
                        invokePrivate(dialog, "updatePreview");
                        WorkloadFormState.Evaluation evaluation = dialogField(
                                dialog, "evaluation", WorkloadFormState.Evaluation.class);
                        assertEquals(WorkloadField.CUSTOM_STAGES, evaluation.field());
                        assertTrue(evaluation.error().contains("Custom stage 2 "
                                + List.of("target", "ramp", "hold").get(column)));
                        assertTrue(invalid.getBoolean(dialogField(dialog, "customStagesShell", FieldShell.class)));
                        model.setValueAt(original, 1, column);
                    }
                    model.setValueAt("0", 1, 2);
                    invokePrivate(dialog, "updatePreview");
                    assertEquals("Custom stage 2 hold must be greater than 0.",
                            dialogField(dialog, "evaluation", WorkloadFormState.Evaluation.class).error());
                    model.removeRow(1);
                    model.setValueAt("0", 0, 0);
                    invokePrivate(dialog, "updatePreview");
                    assertEquals(WorkloadField.CUSTOM_STAGES,
                            dialogField(dialog, "evaluation", WorkloadFormState.Evaluation.class).field());
                    assertTrue(invalid.getBoolean(dialogField(dialog, "customStagesShell", FieldShell.class)));

                    dialogField(dialog, "shapeCombo", JComboBox.class).setSelectedItem(WorkloadShape.LOAD);
                    for (String name : List.of("cap", "response", "think", "ramp", "hold", "samplesPerVcpu")) {
                        dialogField(dialog, name, JTextField.class).setText("123");
                    }
                    dialog.showAiSuggestionForRender(new org.qainsights.jmeter.ai.load.WorkloadAiAssistant.Suggestion(
                            WorkloadInputs.TargetType.USERS, 100, null, null, null, null, null, null,
                            "Suggestion", List.of(), List.of()));
                    for (String name : List.of("cap", "response", "think", "ramp", "hold", "samplesPerVcpu")) {
                        assertEquals("", dialogField(dialog, name, JTextField.class).getText(), name);
                    }
                    SectionCard advanced = dialogField(dialog, "advancedCard", SectionCard.class);
                    assertFalse(advanced.body().isVisible());
                    dialog.showAiSuggestionForRender(new org.qainsights.jmeter.ai.load.WorkloadAiAssistant.Suggestion(
                            WorkloadInputs.TargetType.USERS, 100, 50.0, 600.0, 100.0, 120.0, 600.0, 300.0,
                            "Suggestion", List.of(), List.of()));
                    assertTrue(advanced.body().isVisible());
                    assertEquals("300", dialogField(dialog, "samplesPerVcpu", JTextField.class).getText());
                    assertEquals("50", dialogField(dialog, "cap", JTextField.class).getText());
                } catch (Throwable exception) {
                    failure.set(exception);
                } finally {
                    if (dialog != null) {
                        dialog.dispose();
                    }
                }
            });
            if (failure.get() != null) {
                throw new AssertionError("Dialog review regression failed", failure.get());
            }
        } finally {
            preferences.removeNode();
        }
    }

    @Test
    void keepsAiPanelAttachedWhenRebuildingFormCardBodies() {
        SectionCard aiCard = new SectionCard("Ask AI");
        JPanel aiPanel = new JPanel();
        aiCard.body().add(aiPanel, BorderLayout.CENTER);
        SectionCard targetCard = new SectionCard("Target");
        targetCard.body().add(new JPanel(), BorderLayout.CENTER);

        WorkloadDesignerDialog.clearFormCardBody(targetCard, aiCard);
        WorkloadDesignerDialog.clearFormCardBody(aiCard, aiCard);

        assertEquals(0, targetCard.body().getComponentCount());
        assertSame(aiPanel, aiCard.body().getComponent(0));
    }

    @Test
    void stackedCardGroupsUseSpaceThreeGaps() {
        JPanel column = new JPanel();
        List<SectionCard> cards = List.of(new SectionCard("First"), new SectionCard("Second"));
        List<Component> gaps = new ArrayList<>();

        WorkloadDesignerDialog.rebuildCardStack(column, cards, UiTokens.SPACE_3, gaps);

        assertEquals(3, column.getComponentCount());
        Component gap = column.getComponent(1);
        assertTrue(gap.isVisible());
        assertEquals(UiTokens.SPACE_3, gap.getPreferredSize().height);
    }

    @Test
    void stackedFormAndPreviewColumnsKeepTheirGap() {
        JPanel columns = new JPanel();
        columns.setLayout(new BoxLayout(columns, BoxLayout.Y_AXIS));
        JPanel form = new JPanel();
        JPanel preview = new JPanel();

        WorkloadDesignerDialog.addStackedColumns(columns, form, preview);

        assertEquals(3, columns.getComponentCount());
        assertSame(form, columns.getComponent(0));
        assertEquals(UiTokens.SPACE_4, columns.getComponent(1).getPreferredSize().height);
        assertSame(preview, columns.getComponent(2));
    }

    @Test
    void commandPreviewCanGrowBeyondEightRows() {
        JTextArea cliArea = new JTextArea();
        cliArea.setText(String.join("\n", List.of(
                "stage 1", "stage 2", "stage 3", "stage 4", "stage 5", "stage 6",
                "stage 7", "stage 8", "stage 9", "stage 10", "stage 11", "stage 12")));
        cliArea.setSize(500, 300);

        WorkloadDesignerDialog.updateCommandRows(cliArea);

        assertTrue(cliArea.getRows() > 8);
    }

    @Test
    void commandPreviewUpdatesMaximumHeightWhenRowsStayTheSame() {
        JTextArea cliArea = new JTextArea("command");
        WorkloadDesignerDialog.updateCommandRows(cliArea);
        int rows = cliArea.getRows();
        cliArea.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));

        WorkloadDesignerDialog.updateCommandRows(cliArea);

        assertEquals(rows, cliArea.getRows());
        assertEquals(cliArea.getPreferredSize().height, cliArea.getMaximumSize().height);
    }

    @Test
    void selectsCoreOpenModelForShapedWorkloadsAndStandardGroupsForLoadAndEndurance() {
        assertFalse(WorkloadShape.LOAD.usesOpenModelThreadGroup());
        assertFalse(WorkloadShape.ENDURANCE.usesOpenModelThreadGroup());
        assertTrue(WorkloadShape.STEP.usesOpenModelThreadGroup());
        assertTrue(WorkloadShape.SPIKE.usesOpenModelThreadGroup());
        assertTrue(WorkloadShape.STRESS.usesOpenModelThreadGroup());
        assertTrue(WorkloadShape.CUSTOM.usesOpenModelThreadGroup());
        assertTrue(WorkloadDesignerDialog.isShapeRowVisible(WorkloadShape.STEP, WorkloadShape.STEP));
        assertFalse(WorkloadDesignerDialog.isShapeRowVisible(WorkloadShape.STEP, WorkloadShape.SPIKE));
    }

    @Test
    void askAiIsLimitedToLoadAndEnduranceShapes() {
        assertTrue(WorkloadDesignerDialog.askAiEnabledForShape(WorkloadShape.LOAD));
        assertTrue(WorkloadDesignerDialog.askAiEnabledForShape(WorkloadShape.ENDURANCE));
        assertFalse(WorkloadDesignerDialog.askAiEnabledForShape(WorkloadShape.STEP));
        assertFalse(WorkloadDesignerDialog.askAiButtonEnabled(WorkloadShape.LOAD, true));
        assertTrue(WorkloadDesignerDialog.askAiButtonEnabled(WorkloadShape.LOAD, false));
        assertFalse(WorkloadDesignerDialog.askAiButtonEnabled(WorkloadShape.STEP, false));
        assertEquals("Ask AI fills Load and Endurance workloads.",
                WorkloadDesignerDialog.askAiTooltipForShape(WorkloadShape.CUSTOM));
    }

    @Test
    void zoomShortcutsMapToJMeterZoomActions() {
        Map<KeyStroke, String> shortcuts =
                WorkloadDesignerDialog.zoomKeyStrokes(InputEvent.CTRL_DOWN_MASK);

        assertEquals(6, shortcuts.size());
        assertEquals(ActionNames.ZOOM_IN,
                shortcuts.get(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, InputEvent.CTRL_DOWN_MASK)));
        assertEquals(ActionNames.ZOOM_IN, shortcuts.get(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS,
                InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK)));
        assertEquals(ActionNames.ZOOM_IN,
                shortcuts.get(KeyStroke.getKeyStroke(KeyEvent.VK_PLUS, InputEvent.CTRL_DOWN_MASK)));
        assertEquals(ActionNames.ZOOM_IN, shortcuts.get(KeyStroke.getKeyStroke(KeyEvent.VK_ADD,
                InputEvent.CTRL_DOWN_MASK)));
        assertEquals(ActionNames.ZOOM_OUT,
                shortcuts.get(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, InputEvent.CTRL_DOWN_MASK)));
        assertEquals(ActionNames.ZOOM_OUT, shortcuts.get(KeyStroke.getKeyStroke(KeyEvent.VK_SUBTRACT,
                InputEvent.CTRL_DOWN_MASK)));
    }

    @Test
    void customShapeRowLimitIsCheckedBeforeStageExpansion() {
        assertTrue(WorkloadDesignerDialog.customRowCountWithinLimit(10));
        assertFalse(WorkloadDesignerDialog.customRowCountWithinLimit(11));
    }

    @Test
    void invalidShapeFieldTextNamesTheFieldAndDisablesApply() {
        String rateError = WorkloadDesignerDialog.shapeFieldError("oops", "Step start target", false);
        assertEquals("Step start target must be a number.", rateError);
        assertEquals("Spike duration must be a whole number.",
                WorkloadDesignerDialog.shapeFieldError("2.5", "Spike duration", true));
        assertEquals("Ramp down must be a whole number.",
                WorkloadDesignerDialog.shapeFieldError("bad", "Ramp down", true));
        assertEquals("Custom stage 2 target must be a number.",
                WorkloadDesignerDialog.shapeFieldError("oops", "Custom stage 2 target", false));
        assertEquals(null, WorkloadDesignerDialog.shapeFieldError("", "Step size", false));
        assertFalse(WorkloadDesignerDialog.canApply(1, new WorkloadFormState.Evaluation(null, rateError)));
    }

    @Test
    void customMessageRateRejectsZeroMessagesPerSample() {
        String error = WorkloadDesignerDialog.customConversionInputError(
                WorkloadInputs.TargetType.MESSAGE_RATE, "0", "500", "0");

        assertEquals("Messages per sample must be at least 1.", error);
        assertFalse(WorkloadDesignerDialog.canApply(1, new WorkloadFormState.Evaluation(null, error)));
    }

    @Test
    void customUsersRejectZeroResponseAndThinkTime() {
        String error = WorkloadDesignerDialog.customConversionInputError(
                WorkloadInputs.TargetType.USERS, "1", "0", "0");

        assertEquals("Response time and think time must total more than zero.", error);
        assertFalse(WorkloadDesignerDialog.canApply(1, new WorkloadFormState.Evaluation(null, error)));
    }

    @Test
    void disabledRampDownDoesNotValidateItsText() {
        assertEquals(null, WorkloadDesignerDialog.rampDownFieldError(false, "not a duration"));
        assertEquals("Ramp down must be a whole number.",
                WorkloadDesignerDialog.rampDownFieldError(true, "not a duration"));
    }

    @Test
    void applyCommitsAnActiveCustomCellBeforeRecomputing() {
        DefaultTableModel model = new DefaultTableModel(new Object[][]{{"100"}}, new Object[]{"Target"});
        JTable table = new JTable(model);
        table.setDefaultEditor(Object.class, new DefaultCellEditor(new JTextField()));
        assertTrue(table.editCellAt(0, 0));
        ((JTextField) table.getEditorComponent()).setText("300");
        AtomicInteger recomputes = new AtomicInteger();

        assertTrue(WorkloadDesignerDialog.stopEditingAndRecompute(table, recomputes::incrementAndGet));

        assertEquals("300", model.getValueAt(0, 0));
        assertEquals(1, recomputes.get());
    }

    @Test
    void applyDoesNotRecomputeWhenCustomCellCommitFails() {
        DefaultTableModel model = new DefaultTableModel(new Object[][]{{"100"}}, new Object[]{"Target"});
        JTable table = new JTable(model);
        table.setDefaultEditor(Object.class, new DefaultCellEditor(new JTextField()) {
            @Override
            public boolean stopCellEditing() {
                return false;
            }
        });
        assertTrue(table.editCellAt(0, 0));
        AtomicInteger recomputes = new AtomicInteger();

        assertFalse(WorkloadDesignerDialog.stopEditingAndRecompute(table, recomputes::incrementAndGet));

        assertEquals(0, recomputes.get());
        assertTrue(table.isEditing());
    }

    @Test
    void initialSizeUsesDefaultWithinNinetyPercentOfUsableScreenBounds() {
        Rectangle bounds = new Rectangle(0, 0, 800, 600);
        Insets insets = new Insets(40, 0, 20, 0);
        Dimension minimum = WorkloadDesignerDialog.minimumSizeWithinScreen(bounds, insets);

        assertEquals(new Dimension(720, 486),
                WorkloadDesignerDialog.initialSize(null, new Dimension(1140, 840), minimum, bounds, insets));
    }

    @Test
    void initialSizeRestoresSavedDimensions() {
        Rectangle bounds = new Rectangle(0, 0, 1920, 1080);
        Insets insets = new Insets(40, 0, 40, 0);
        Dimension minimum = WorkloadDesignerDialog.minimumSizeWithinScreen(bounds, insets);

        assertEquals(new Dimension(900, 700),
                WorkloadDesignerDialog.initialSize(new Dimension(900, 700), new Dimension(1140, 840),
                        minimum, bounds, insets));
    }

    @Test
    void initialSizeClampsSavedDimensionsToNinetyPercentOfTheScreen() {
        Rectangle bounds = new Rectangle(0, 0, 1920, 1080);
        Insets insets = new Insets(40, 0, 40, 0);
        Dimension minimum = WorkloadDesignerDialog.minimumSizeWithinScreen(bounds, insets);

        assertEquals(new Dimension(1728, 900),
                WorkloadDesignerDialog.initialSize(new Dimension(3000, 2000), new Dimension(1140, 840),
                        minimum, bounds, insets));
    }

    @Test
    void initialSizeRaisesSavedDimensionsToTheMinimum() {
        Rectangle bounds = new Rectangle(0, 0, 1920, 1080);
        Insets insets = new Insets(40, 0, 40, 0);
        Dimension minimum = WorkloadDesignerDialog.minimumSizeWithinScreen(bounds, insets);

        assertEquals(new Dimension(640, 480),
                WorkloadDesignerDialog.initialSize(new Dimension(400, 300), new Dimension(1140, 840),
                        minimum, bounds, insets));
    }

    @Test
    void minimumSizeIsCappedByTheUsableScreenDimensions() {
        Rectangle bounds = new Rectangle(0, 0, 500, 400);
        Insets insets = new Insets(10, 20, 30, 40);
        Dimension minimum = WorkloadDesignerDialog.minimumSizeWithinScreen(bounds, insets);

        assertEquals(new Dimension(440, 360), minimum);
        assertEquals(minimum, WorkloadDesignerDialog.initialSize(new Dimension(200, 100),
                new Dimension(1140, 840), minimum, bounds, insets));
    }

    @Test
    void rememberedSizeIsRestoredForTheNextOpen() throws Exception {
        Preferences preferences = Preferences.userRoot().node("featherwand-test-" + UUID.randomUUID());
        Preferences parent = preferences.parent();
        try {
            Dimension closedSize = new Dimension(1000, 700);
            WorkloadDesignerDialog.saveWindowSize(preferences, closedSize);
            Rectangle bounds = new Rectangle(0, 0, 1920, 1080);
            Insets insets = new Insets(0, 0, 0, 0);
            Dimension minimum = WorkloadDesignerDialog.minimumSizeWithinScreen(bounds, insets);

            Dimension saved = WorkloadDesignerDialog.readSavedSize(preferences);

            assertEquals(closedSize, saved);
            assertEquals(closedSize,
                    WorkloadDesignerDialog.initialSize(saved, new Dimension(1140, 840),
                            minimum, bounds, insets));
        } finally {
            preferences.removeNode();
            parent.flush();
        }
    }

    @Test
    void invalidAndNonPositiveSavedDimensionsAreIgnored() throws Exception {
        Preferences preferences = Preferences.userRoot().node("featherwand-test-" + UUID.randomUUID());
        Preferences parent = preferences.parent();
        try {
            preferences.put("workloadDesigner.width", "invalid");
            preferences.putInt("workloadDesigner.height", 700);
            preferences.flush();
            assertNull(WorkloadDesignerDialog.readSavedSize(preferences));

            preferences.putInt("workloadDesigner.width", 0);
            preferences.flush();
            assertNull(WorkloadDesignerDialog.readSavedSize(preferences));
        } finally {
            preferences.removeNode();
            parent.flush();
        }
    }

    @Test
    void loadReplyUsesSelectedShellCommandAndFenceLanguage() {
        WorkloadPlan plan = WorkloadCalculator.calculate(new WorkloadInputs(
                WorkloadInputs.TargetType.REQUEST_RATE, 10, null, 500L, null,
                60L, 600L, 1, 1, "load", "plan.jmx"));
        WorkloadApplier.ApplyResult result =
                new WorkloadApplier.ApplyResult("Thread Group", java.util.List.of(), java.util.List.of());

        for (CommandShell shell : CommandShell.values()) {
            String reply = CommandDispatcher.loadReply(plan, result, shell);
            assertTrue(reply.contains("```" + shell.languageTag() + "\n"
                    + plan.command(shell) + "\n```"));
        }
        assertTrue(CommandDispatcher.loadReply(plan, result, CommandShell.CMD)
                .contains("set HEAP=-Xms1g -Xmx1g\njmeter.bat"));
    }

    @Test
    void showsOpenModelArrivalsInsteadOfTimerThroughput() {
        WorkloadPlan plan = WorkloadCalculator.calculate(new WorkloadInputs(
                WorkloadInputs.TargetType.ITERATION_RATE, 1, null, 500L, 0L,
                10L, 20L, 1, 1, "load", "plan.jmx", 1.0, null,
                java.util.List.of(), java.util.List.of(), java.util.List.of(), null, null, null, false,
                new WorkloadShapeConfig(WorkloadShape.STEP, 1.0, 2.0, 1.0,
                        10L, 5L, null, null, null, null, null, java.util.List.of(), null, ArrivalMode.EVEN)));

        assertEquals("Not used (Open Model arrivals)",
                WorkloadDesignerDialog.throughputSamplesPerMinute(plan));
    }

    @Test
    void displaysConvertedOriginalRedirectAlongsidePlanNotes() {
        String redirectNote = "\"Checkout\" was converted to \"Checkout (Step)\". "
                + "Editing \"Checkout (Step)\".";

        assertEquals(redirectNote + "\nSoak guidance.",
                WorkloadDesignerDialog.infoNotes(java.util.List.of("Soak guidance."), redirectNote));
        assertEquals(redirectNote, WorkloadDesignerDialog.infoNotes(java.util.List.of(), redirectNote));
    }

    @Test
    void customStageHeaderUsesTheCurrentTargetUnit() {
        assertEquals("Target (samples/s)",
                WorkloadDesignerDialog.customTargetHeader(WorkloadInputs.TargetType.REQUEST_RATE));
        assertEquals("Target (messages/s)",
                WorkloadDesignerDialog.customTargetHeader(WorkloadInputs.TargetType.MESSAGE_RATE));
        assertEquals("Target (iterations/s)",
                WorkloadDesignerDialog.customTargetHeader(WorkloadInputs.TargetType.ITERATION_RATE));
        assertEquals("Target (users)",
                WorkloadDesignerDialog.customTargetHeader(WorkloadInputs.TargetType.USERS));
    }

    @Test
    void customStageNumericEditorsSurviveTargetTypeHeaderRebuilds() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        Preferences preferences = Preferences.userRoot()
                .node("org/qainsights/jmeter/ai/gui/custom-editors-" + UUID.randomUUID());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                WorkloadDesignerDialog dialog = null;
                try {
                    JMeterTreeNode group =
                            new JMeterTreeNode(new org.apache.jmeter.threads.ThreadGroup(), null);
                    group.add(new JMeterTreeNode(
                            new org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy(), null));
                    LoadCommandHandler.Preparation preparation =
                            new LoadCommandHandler.Preparation(null, WorkloadParser.parse("@load"),
                                    List.of(group), group, 1, null, "plan.jmx");
                    dialog = new WorkloadDesignerDialog(preparation, ignored -> { }, ignored -> "", preferences);
                    dialog.addNotify();

                    JTable table = dialogField(dialog, "customStagesTable", JTable.class);
                    JComboBox<?> targetType = dialogField(dialog, "targetCombo", JComboBox.class);
                    assertEquals("Target (samples/s)", table.getColumnModel().getColumn(0).getHeaderValue());
                    assertCustomStageEditorFilters(table);

                    assertTrue(table.editCellAt(0, 0));
                    ((JTextField) table.getEditorComponent()).setText("321");
                    targetType.setSelectedItem(WorkloadInputs.TargetType.USERS);
                    assertFalse(table.isEditing(), "Target type changes should finish active cell editing");
                    assertEquals("321", table.getValueAt(0, 0));
                    assertEquals("Target (users)", table.getColumnModel().getColumn(0).getHeaderValue());
                    assertCustomStageEditorFilters(table);

                    targetType.setSelectedItem(WorkloadInputs.TargetType.REQUEST_RATE);
                    assertEquals("Target (samples/s)", table.getColumnModel().getColumn(0).getHeaderValue());
                    assertCustomStageEditorFilters(table);
                } catch (Throwable exception) {
                    failure.set(exception);
                } finally {
                    if (dialog != null) {
                        dialog.dispose();
                    }
                }
            });
            if (failure.get() != null) {
                throw new AssertionError("Custom stage editor rebuild failed", failure.get());
            }
        } finally {
            preferences.removeNode();
        }
    }

    @Test
    void oversizedPrefilledAndSuggestedTargetsRemainVisibleForLimitErrors() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        Preferences preferences = Preferences.userRoot()
                .node("org/qainsights/jmeter/ai/gui/oversized-target-" + UUID.randomUUID());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                WorkloadDesignerDialog dialog = null;
                try {
                    JMeterTreeNode group =
                            new JMeterTreeNode(new org.apache.jmeter.threads.ThreadGroup(), null);
                    group.add(new JMeterTreeNode(
                            new org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy(), null));
                    WorkloadSpec spec = WorkloadParser.parse("@load 10000000000000 rps");
                    LoadCommandHandler.Preparation preparation =
                            new LoadCommandHandler.Preparation(null, spec, List.of(group), group, 1,
                                    null, "plan.jmx");
                    dialog = new WorkloadDesignerDialog(preparation, ignored -> { }, ignored -> "", preferences);
                    dialog.addNotify();

                    assertOversizedTargetError(dialog);
                    dialog.showAiSuggestionForRender(
                            new org.qainsights.jmeter.ai.load.WorkloadAiAssistant.Suggestion(
                                    WorkloadInputs.TargetType.REQUEST_RATE, 10_000_000_000_000d,
                                    null, 500.0, 0.0, 0.0, 60.0, null,
                                    "Oversized target", List.of(), List.of()));
                    assertOversizedTargetError(dialog);
                } catch (Throwable exception) {
                    failure.set(exception);
                } finally {
                    if (dialog != null) {
                        dialog.dispose();
                    }
                }
            });
            if (failure.get() != null) {
                throw new AssertionError("Oversized target prefill was not preserved", failure.get());
            }
        } finally {
            preferences.removeNode();
        }
    }

    private static void assertOversizedTargetError(WorkloadDesignerDialog dialog) throws Exception {
        JTextField target = dialogField(dialog, "target", JTextField.class);
        assertEquals("10000000000000", target.getText());

        FieldShell targetShell = dialogField(dialog, "targetValueShell", FieldShell.class);
        Field invalid = FieldShell.class.getDeclaredField("invalid");
        invalid.setAccessible(true);
        assertTrue(invalid.getBoolean(targetShell));

        String error = dialogField(dialog, "evaluation", WorkloadFormState.Evaluation.class).error();
        assertTrue(error.contains("at most 1,000,000"), error);
        assertTrue(dialogField(dialog, "errorLabel", JLabel.class).getText().contains("at most 1,000,000"));

        LoadShapeChart chart = dialogField(dialog, "chart", LoadShapeChart.class);
        Field emptyMessage = LoadShapeChart.class.getDeclaredField("emptyMessage");
        emptyMessage.setAccessible(true);
        assertEquals(error, emptyMessage.get(chart));
    }

    private static void assertCustomStageEditorFilters(JTable table) throws Exception {
        JTextField target = customStageEditorField(table, 0);
        target.setText("5");
        target.getDocument().insertString(target.getDocument().getLength(), "a", null);
        assertEquals("5", target.getText());

        for (int column = 1; column < 3; column++) {
            JTextField duration = customStageEditorField(table, column);
            duration.setText("");
            duration.getDocument().insertString(0, ".", null);
            assertEquals("", duration.getText());
        }
    }

    private static JTextField customStageEditorField(JTable table, int column) {
        Object editor = table.getColumnModel().getColumn(column).getCellEditor();
        assertTrue(editor instanceof DefaultCellEditor,
                "Custom stage column " + column + " should use a numeric text editor");
        return (JTextField) ((DefaultCellEditor) editor).getComponent();
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

    @Test
    void closingPendingAiRequestDoesNotInterruptOrApplyLateResult() throws Exception {
        long requestId = 1;
        AtomicLong currentRequestId = new AtomicLong(requestId);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch requestFinished = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicBoolean resultProduced = new AtomicBoolean();
        AtomicBoolean resultApplied = new AtomicBoolean();
        AtomicBoolean failureRecorded = new AtomicBoolean();
        SwingWorker<String, Void> worker = new SwingWorker<>() {
            @Override
            protected String doInBackground() throws Exception {
                started.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Fake AI request was not released.");
                    }
                    resultProduced.set(true);
                    return "late suggestion";
                } catch (InterruptedException e) {
                    interrupted.set(true);
                    throw e;
                } finally {
                    requestFinished.countDown();
                }
            }

            @Override
            protected void done() {
                if (!WorkloadDesignerDialog.shouldHandleAiResult(
                        requestId, currentRequestId.get(), false, isCancelled())) {
                    done.countDown();
                    return;
                }
                try {
                    resultApplied.set("late suggestion".equals(get()));
                } catch (Exception e) {
                    failureRecorded.set(true);
                } finally {
                    done.countDown();
                }
            }
        };
        worker.execute();
        assertTrue(started.await(5, TimeUnit.SECONDS));

        SwingUtilities.invokeAndWait(() -> currentRequestId.set(
                WorkloadDesignerDialog.invalidateAiRequest(requestId, worker)));
        assertTrue(worker.isCancelled());
        assertFalse(interrupted.get());

        release.countDown();
        assertTrue(requestFinished.await(5, TimeUnit.SECONDS));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(resultProduced.get());
        assertFalse(interrupted.get());
        assertFalse(resultApplied.get());
        assertFalse(failureRecorded.get());
    }
}
