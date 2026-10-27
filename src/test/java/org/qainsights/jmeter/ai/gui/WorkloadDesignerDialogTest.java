package org.qainsights.jmeter.ai.gui;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.prefs.Preferences;

import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;

import org.apache.jmeter.gui.action.ActionNames;
import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.load.ArrivalMode;
import org.qainsights.jmeter.ai.load.CommandShell;
import org.qainsights.jmeter.ai.load.WorkloadApplier;
import org.qainsights.jmeter.ai.load.WorkloadCalculator;
import org.qainsights.jmeter.ai.load.WorkloadFormState;
import org.qainsights.jmeter.ai.load.WorkloadInputs;
import org.qainsights.jmeter.ai.load.WorkloadPlan;
import org.qainsights.jmeter.ai.load.WorkloadShape;
import org.qainsights.jmeter.ai.load.WorkloadShapeConfig;
import org.qainsights.jmeter.ai.gui.theme.UiTokens;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        assertEquals("Messages per sample must be greater than zero.", error);
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
