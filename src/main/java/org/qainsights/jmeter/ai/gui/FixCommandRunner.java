package org.qainsights.jmeter.ai.gui;

import java.awt.event.ActionEvent;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import javax.swing.SwingWorker;

import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.action.ActionRouter;
import org.qainsights.jmeter.ai.agent.AgentRunListeners;
import org.qainsights.jmeter.ai.agent.JMeterAgent;
import org.qainsights.jmeter.ai.agent.loop.AgentLoop;
import org.qainsights.jmeter.ai.agent.jmeter.TestRunController;
import org.qainsights.jmeter.ai.agent.tool.AgentToolRegistry;
import org.qainsights.jmeter.ai.agent.tool.ToolRegistry;
import org.qainsights.jmeter.ai.agent.jmeter.TestPlanRunner;
import org.qainsights.jmeter.ai.agent.jmeter.TestResultsRunner;
import org.qainsights.jmeter.ai.fix.FixLoop;
import org.qainsights.jmeter.ai.fix.FixRequest;
import org.qainsights.jmeter.ai.fix.FixResult;
import org.qainsights.jmeter.ai.fix.FixTools;
import org.qainsights.jmeter.ai.fix.HostConfirmation;
import org.qainsights.jmeter.ai.fix.PlanBackup;
import org.qainsights.jmeter.ai.fix.PlanCensus;
import org.qainsights.jmeter.ai.fix.PlanHosts;
import org.qainsights.jmeter.ai.service.AiService;
import org.qainsights.jmeter.ai.telemetry.Telemetry;
import org.qainsights.jmeter.ai.telemetry.TelemetryFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** SwingWorker-backed command runner for the guarded Make it pass workflow. */
final class FixCommandRunner {
    private static final Logger log = LoggerFactory.getLogger(FixCommandRunner.class);
    private final CommandCallback cb;
    private final Function<AiService, JMeterAgent> agentFactory;
    private final TestRunController testRunController;

    FixCommandRunner(CommandCallback cb, Function<AiService, JMeterAgent> agentFactory) {
        this(cb, agentFactory, TestRunController.live());
    }

    FixCommandRunner(CommandCallback cb, Function<AiService, JMeterAgent> agentFactory,
                     TestRunController testRunController) {
        this.cb = cb;
        this.agentFactory = agentFactory;
        this.testRunController = testRunController;
    }

    void run() {
        cb.setLastCommandType("NONE");
        cb.setInputEnabled(false);
        cb.removeLoadingIndicator();
        if (testRunController.isRunning()) {
            finishWithMessage("A test is already running. Stop it before using @fix.");
            return;
        }
        if (!hasPlan()) {
            finishWithMessage("Open a JMeter test plan before using @fix.");
            return;
        }
        if (!JMeterAgent.isEnabled()) {
            finishWithMessage("Make it pass requires Agent Mode. Enable it in JMeter settings, then retry.");
            return;
        }
        AiService service = cb.resolveAiService(cb.getSelectedModel());
        JMeterAgent agent = agentFactory.apply(service);
        if (agent == null) {
            finishWithMessage("Make it pass requires an Agent Mode provider. Select a supported provider such as Claude, "
                    + "OpenAI, Gemini, DeepSeek, Grok, OpenRouter, Meta Muse, Codex, Claude Code, or OpenCode.");
            return;
        }
        org.apache.jmeter.gui.GuiPackage gui = org.apache.jmeter.gui.GuiPackage.getInstance();
        java.awt.Component parent = gui == null ? null : gui.getMainFrame();
        if (!HostConfirmation.confirm(parent, PlanHosts.live())) {
            Telemetry.record(TelemetryFeature.FIX_DECLINED);
            finishWithMessage("Make it pass was cancelled. No requests were sent.");
            return;
        }

        PlanBackup backups = new PlanBackup();
        ToolRegistry registry = AgentToolRegistry.createDefault().subset(FixTools.NAMES);
        FixRequest request = FixRequest.fromConfig();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<Thread> workerThread = new AtomicReference<>();
        SwingWorker<FixResult, String> worker = new SwingWorker<>() {
            @Override
            protected FixResult doInBackground() {
                workerThread.set(Thread.currentThread());
                FixLoop loop = new FixLoop(TestResultsRunner.live()::runSingleIteration, backups,
                        PlanCensus::live,
                        message -> agent.runRestricted(message, registry, "",
                                new AgentRunListeners(this::publish, null, null, null, null)),
                        cancelled::get);
                try {
                    return loop.run(request, this::publish);
                } finally {
                    workerThread.set(null);
                }
            }

            @Override
            protected void process(java.util.List<String> lines) {
                lines.forEach(cb::appendToolActivity);
            }

            @Override
            protected void done() {
                cb.hideStopButton();
                cb.setCancelHandle(null);
                cb.setInputEnabled(true);
                try {
                    FixResult result = get();
                    recordResult(result);
                    cb.appendFixResult(result,
                            () -> runCardAction("Could not undo Make it pass", () -> {
                                java.io.File backup = result.backupFile();
                                if (backup == null || !backup.isFile()) {
                                    cb.appendMessageToChat(
                                            "The Make it pass backup is missing. Undo all is unavailable.");
                                    backups.unpin(backup);
                                    return;
                                }
                                backups.restore(backup);
                                Telemetry.record(TelemetryFeature.FIX_UNDO);
                                backups.unpin(backup);
                            }),
                            () -> runCardAction("Could not save the test plan", () ->
                                    ActionRouter.getInstance().doActionNow(
                                            new ActionEvent(this, ActionEvent.ACTION_PERFORMED, ActionNames.SAVE_AS))),
                            () -> runCardAction("Could not release Make it pass backup",
                                    () -> backups.unpin(result.backupFile())));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Make it pass was interrupted", e);
                } catch (ExecutionException e) {
                    cb.appendErrorMessageToChat("Make it pass failed", asException(e.getCause()));
                } catch (CancellationException e) {
                    cb.appendErrorMessageToChat("Make it pass was cancelled", e);
                }
            }
        };
        cb.setCancelHandle(() -> {
            cancelled.set(true);
            TestPlanRunner.stopActive(true);
            Thread thread = workerThread.get();
            if (thread != null) {
                thread.interrupt();
            }
        });
        cb.showStopButton();
        worker.execute();
    }

    private boolean hasPlan() {
        org.apache.jmeter.gui.GuiPackage gui = org.apache.jmeter.gui.GuiPackage.getInstance();
        return gui != null && gui.getTreeModel() != null && gui.getTreeModel().getTestPlan() != null
                && gui.getTreeModel().getTestPlan().size() > 0;
    }

    private void finishWithMessage(String message) {
        cb.appendRedMessage(message);
        cb.setInputEnabled(true);
    }

    private static void recordResult(FixResult result) {
        Telemetry.record(switch (result.status()) {
            case ALREADY_PASSING -> TelemetryFeature.FIX_ALREADY_PASSING;
            case FIXED -> TelemetryFeature.FIX_FIXED;
            case PARTLY_FIXED -> TelemetryFeature.FIX_PARTLY_FIXED;
            case NOT_FIXED -> TelemetryFeature.FIX_NOT_FIXED;
            case CANCELLED -> TelemetryFeature.FIX_CANCELLED;
            case NOTHING_RAN -> TelemetryFeature.FIX_NOTHING_RAN;
        });
        if (result.roundsUsed() == 1) {
            Telemetry.record(TelemetryFeature.FIX_ROUNDS_1);
        } else if (result.roundsUsed() <= 3 && result.roundsUsed() > 1) {
            Telemetry.record(TelemetryFeature.FIX_ROUNDS_2_3);
        } else if (result.roundsUsed() >= 4) {
            Telemetry.record(TelemetryFeature.FIX_ROUNDS_4P);
        }
    }

    private void runCardAction(String failureMessage, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            cb.appendErrorMessageToChat(failureMessage, e);
        }
    }

    private static Exception asException(Throwable error) {
        return error instanceof Exception ? (Exception) error : new RuntimeException(error);
    }
}
