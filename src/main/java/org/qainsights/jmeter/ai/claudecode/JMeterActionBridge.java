package org.qainsights.jmeter.ai.claudecode;

import org.apache.jmeter.gui.action.ActionNames;
import org.apache.jmeter.gui.action.ActionRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * File-based action bridge between Claude Code and JMeter.
 * <p>
 * Claude Code runs as an external PTY process and cannot directly invoke JMeter's Java APIs.
 * This bridge watches a control file ({@code .jmeter-claude-action}) in the test plan directory.
 * When Claude Code writes a command to this file, the bridge reads it, triggers the corresponding
 * JMeter action via {@link ActionRouter}, and deletes the file.
 * <p>
 * Supported commands:
 * <ul>
 *   <li>{@code start} - Start the test (ActionNames.ACTION_START)</li>
 *   <li>{@code stop} - Stop the test (ActionNames.ACTION_STOP)</li>
 *   <li>{@code shutdown} - Graceful shutdown (ActionNames.ACTION_SHUTDOWN)</li>
 * </ul>
 */
public class JMeterActionBridge {
    private static final Logger log = LoggerFactory.getLogger(JMeterActionBridge.class);

    static final String ACTION_FILE_NAME = ".jmeter-claude-action";
    private static final String ALT_ACTION_FILE_NAME = ".jmeter_cmd";
    private static final int POLL_INTERVAL_MS = 2000;
    private static final long RELOAD_SUPPRESS_MS = 60_000; // 60 seconds

    private final File watchDir;
    private Timer pollTimer;
    private volatile long lastStartTriggeredAt;

    public JMeterActionBridge(File watchDir) {
        this.watchDir = watchDir;
    }

    /**
     * Starts polling for the action control file.
     */
    public void startWatching() {
        if (watchDir == null || !watchDir.isDirectory()) {
            log.warn("Cannot start action bridge: invalid watch directory {}", watchDir);
            return;
        }

        pollTimer = new Timer(POLL_INTERVAL_MS, e -> checkForActionFile());
        pollTimer.start();
        log.info("JMeterActionBridge started watching: {}", watchDir);
    }

    /**
     * Stops polling.
     */
    public void stopWatching() {
        if (pollTimer != null) {
            pollTimer.stop();
            pollTimer = null;
            log.info("JMeterActionBridge stopped");
        }
    }

    private void checkForActionFile() {
        // Check both file names for robustness
        File actionFile = new File(watchDir, ACTION_FILE_NAME);
        File altFile = new File(watchDir, ALT_ACTION_FILE_NAME);

        if (actionFile.exists()) {
            processFile(actionFile);
        }
        if (altFile.exists()) {
            processFile(altFile);
        }
    }

    private void processFile(File actionFile) {
        try {
            String content = new String(Files.readAllBytes(actionFile.toPath()), StandardCharsets.UTF_8).trim();
            if (!actionFile.delete()) {
                log.warn("Failed to delete action file: {}", actionFile);
            }

            if (content.isEmpty()) {
                return;
            }

            log.info("Action bridge received command: '{}' from {}", content, actionFile.getName());
            executeAction(content);
        } catch (Exception ex) {
            log.error("Error processing action file", ex);
            actionFile.delete();
        }
    }

    /**
     * Returns true if a test start was recently triggered via the action bridge.
     * Used to suppress auto-reload of the test plan file so that View Results Tree
     * results are not cleared after a test run.
     */
    boolean isRecentlyStarted() {
        return lastStartTriggeredAt > 0
                && (System.currentTimeMillis() - lastStartTriggeredAt) < RELOAD_SUPPRESS_MS;
    }

    private void executeAction(String command) {
        String actionName;
        switch (command.toLowerCase()) {
            case "start":
            case "run":
                actionName = ActionNames.ACTION_START;
                lastStartTriggeredAt = System.currentTimeMillis();
                break;
            case "stop":
                actionName = ActionNames.ACTION_STOP;
                break;
            case "shutdown":
                actionName = ActionNames.ACTION_SHUTDOWN;
                break;
            default:
                log.warn("Unknown action command: {}", command);
                return;
        }

        SwingUtilities.invokeLater(() -> {
            try {
                ActionRouter.getInstance().actionPerformed(
                        new ActionEvent(this, ActionEvent.ACTION_PERFORMED, actionName));
                log.info("JMeter action triggered: {}", actionName);
            } catch (Exception ex) {
                log.error("Failed to trigger JMeter action: {}", actionName, ex);
            }
        });
    }
}
