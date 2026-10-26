package org.qainsights.jmeter.ai.gui;

import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Rectangle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentListener;

import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.load.CommandShell;
import org.qainsights.jmeter.ai.load.WorkloadApplier;
import org.qainsights.jmeter.ai.load.WorkloadCalculator;
import org.qainsights.jmeter.ai.load.WorkloadInputs;
import org.qainsights.jmeter.ai.load.WorkloadPlan;
import org.qainsights.jmeter.ai.load.WorkloadShape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void switchesShapeCardsAndUsesLoadFieldsForEndurance() {
        JPanel cards = new JPanel(new java.awt.CardLayout());
        JPanel load = new JPanel();
        JPanel step = new JPanel();
        cards.add(load, WorkloadDesignerDialog.shapeCardFor(WorkloadShape.LOAD));
        cards.add(step, WorkloadDesignerDialog.shapeCardFor(WorkloadShape.STEP));

        WorkloadDesignerDialog.showShapeCard(cards, WorkloadShape.STEP);
        assertTrue(step.isVisible());
        assertFalse(load.isVisible());
        assertEquals("LOAD", WorkloadDesignerDialog.shapeCardFor(WorkloadShape.ENDURANCE));

        WorkloadDesignerDialog.showShapeCard(cards, WorkloadShape.LOAD);
        assertTrue(load.isVisible());
        assertFalse(step.isVisible());
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
