package org.qainsights.jmeter.ai.claudecode;

import com.jediterm.pty.PtyProcessTtyConnector;
import com.jediterm.terminal.TtyConnector;
import com.jediterm.terminal.ui.JediTermWidget;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.save.SaveService;
import org.apache.jorphan.collections.HashTree;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A JPanel that embeds a full terminal emulator (JediTerm) running Claude Code.
 * <p>
 * Uses JediTerm + pty4j for a rich terminal experience with:
 * <ul>
 * <li>Full ANSI/xterm color support</li>
 * <li>Interactive permission prompts</li>
 * <li>Cursor control, scrollback, and copy/paste</li>
 * <li>Native PTY on Windows, macOS, and Linux</li>
 * </ul>
 */
public class ClaudeCodePanel extends JPanel {
    private static final Logger log = LoggerFactory.getLogger(ClaudeCodePanel.class);

    private static final Color HEADER_BG_COLOR = new Color(40, 40, 40);
    private static final Color HEADER_FG_COLOR = new Color(220, 220, 220);
    private static final Color BUTTON_BG = new Color(55, 55, 55);
    private static final Color BUTTON_FG = new Color(200, 200, 200);
    private static final Color STATUS_RUNNING = new Color(152, 195, 121);
    private static final Color STATUS_STOPPED = new Color(224, 108, 117);
    private static final Font HEADER_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 13);

    private JediTermWidget terminalWidget;
    private JLabel statusLabel;
    private PtyProcess ptyProcess;
    private volatile String testPlanFilePath;
    private Timer fileWatchTimer;
    private long lastKnownModified;
    private JMeterActionBridge actionBridge;
    private File claudeMdFile;

    public ClaudeCodePanel() {
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(600, 600));
        setMinimumSize(new Dimension(400, 300));

        // Header panel
        add(createHeaderPanel(), BorderLayout.NORTH);

        // Terminal widget
        terminalWidget = createTerminalWidget();
        add(terminalWidget.getComponent(), BorderLayout.CENTER);

        // Start Claude Code in the terminal
        startClaudeCode();
    }

    /**
     * Creates the JediTerm terminal widget with a dark-themed settings provider.
     */
    private JediTermWidget createTerminalWidget() {
        DarkTerminalSettingsProvider settings = new DarkTerminalSettingsProvider();
        JediTermWidget widget = new JediTermWidget(settings);
        return widget;
    }

    /**
     * Creates the header panel with title and control buttons.
     */
    private JPanel createHeaderPanel() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(HEADER_BG_COLOR);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(60, 60, 60)),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));

        // Left: title + status
        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        leftPanel.setOpaque(false);

        JLabel titleLabel = new JLabel("Claude Code");
        titleLabel.setFont(HEADER_FONT);
        titleLabel.setForeground(HEADER_FG_COLOR);
        leftPanel.add(titleLabel);

        statusLabel = new JLabel("\u25CF Starting...");
        statusLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        statusLabel.setForeground(new Color(200, 180, 50));
        leftPanel.add(statusLabel);

        header.add(leftPanel, BorderLayout.WEST);

        // Right: control buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttonPanel.setOpaque(false);

        JButton reloadBtn = createHeaderButton("\u21BB Reload", "Reload test plan from disk");
        reloadBtn.addActionListener(e -> reloadTestPlan());
        buttonPanel.add(reloadBtn);

        JButton refreshBtn = createHeaderButton("\u21BB Ctx", "Send test plan context to Claude Code");
        refreshBtn.addActionListener(e -> sendTestPlanContext());
        buttonPanel.add(refreshBtn);

        JButton restartBtn = createHeaderButton("\u25B6 Restart", "Restart Claude Code");
        restartBtn.addActionListener(e -> restartClaudeCode());
        buttonPanel.add(restartBtn);

        JButton stopBtn = createHeaderButton("\u25A0 Stop", "Stop Claude Code");
        stopBtn.addActionListener(e -> stopClaudeCode());
        buttonPanel.add(stopBtn);

        header.add(buttonPanel, BorderLayout.EAST);

        return header;
    }

    /**
     * Creates a styled header button.
     */
    private JButton createHeaderButton(String text, String tooltip) {
        JButton button = new JButton(text);
        button.setToolTipText(tooltip);
        button.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        button.setBackground(BUTTON_BG);
        button.setForeground(BUTTON_FG);
        button.setFocusPainted(false);
        button.setContentAreaFilled(false);
        button.setOpaque(true);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(70, 70, 70), 1, true),
                BorderFactory.createEmptyBorder(3, 8, 3, 8)));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    /**
     * Starts Claude Code in the JediTerm terminal via a PTY process.
     */
    private void startClaudeCode() {
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                try {
                    String claudeBinary = ClaudeCodeLocator.findClaudeCodeBinary();
                    if(claudeBinary != null && !claudeBinary.isEmpty()) {

                        log.info("Starting Claude Code via PTY from: {}", claudeBinary);

                        // Get test plan file path from JMeter
                        testPlanFilePath = null;
                        String testPlanDir = null;
                        try {
                            GuiPackage guiPackage = GuiPackage.getInstance();
                            if (guiPackage != null) {
                                String filePath = guiPackage.getTestPlanFile();
                                testPlanFilePath = filePath;
                                if (testPlanFilePath != null && !testPlanFilePath.isEmpty()) {
                                    File testPlanFile = new File(testPlanFilePath);
                                    if (testPlanFile.exists()) {
                                        testPlanDir = testPlanFile.getParent();
                                        log.info("Test plan file: {}", testPlanFilePath);
                                        log.info("Working directory: {}", testPlanDir);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            log.warn("Could not get test plan file path from JMeter", e);
                        }

                        // Build the system prompt with test plan context
                        String testPlanContext = TestPlanSerializer.serializeTestPlan();
                        String systemPrompt = buildSystemPrompt(testPlanContext, testPlanFilePath);

                        // Write system prompt as CLAUDE.md to avoid command-line argument
                        // parsing issues on Windows (long args with special chars get mangled
                        // by PtyProcessBuilder, causing stray text to be sent as user input)
                        if (testPlanDir != null) {
                            try {
                                claudeMdFile = new File(testPlanDir, "CLAUDE.md");
                                Files.write(claudeMdFile.toPath(),
                                        systemPrompt.getBytes(StandardCharsets.UTF_8));
                                log.info("Wrote CLAUDE.md to: {}", claudeMdFile.getAbsolutePath());
                            } catch (Exception e) {
                                log.warn("Could not write CLAUDE.md", e);
                                claudeMdFile = null;
                            }
                        }

                        // Build command with arguments
                        List<String> command = new ArrayList<>();
                        command.add(claudeBinary);

                        // Add the test plan directory so Claude can access the .jmx file
                        if (testPlanDir != null) {
                            command.add("--add-dir");
                            command.add(testPlanDir);
                        }

                        // Set up environment
                        Map<String, String> env = new HashMap<>(System.getenv());
                        env.put("TERM", "xterm-256color");
                        env.put("LANG", "en_US.UTF-8");
                        env.put("LC_ALL", "en_US.UTF-8");
                        env.put("PYTHONIOENCODING", "utf-8"); // Just in case

                        // Pass JMETER_HOME so Claude Code can run JMeter CLI for non-GUI mode
                        String jmeterHome = org.apache.jmeter.util.JMeterUtils.getJMeterHome();
                        if (jmeterHome != null && !jmeterHome.isEmpty()) {
                            env.put("JMETER_HOME", jmeterHome);
                        }

                        // Create PTY process
                        PtyProcessBuilder processBuilder = new PtyProcessBuilder(command.toArray(new String[0]))
                                .setEnvironment(env)
                                .setConsole(false)
                                .setInitialColumns(120)
                                .setInitialRows(40);

                        // Set working directory to the test plan's directory
                        if (testPlanDir != null) {
                            processBuilder.setDirectory(testPlanDir);
                        }

                        ptyProcess = processBuilder.start();

                        // Create TTY connector
                        TtyConnector connector = new PtyProcessTtyConnector(
                                ptyProcess, StandardCharsets.UTF_8);

                        // Connect to the terminal widget
                        SwingUtilities.invokeLater(() -> {
                            terminalWidget.setTtyConnector(connector);
                            terminalWidget.start();

                            statusLabel.setText("\u25CF Running");
                            statusLabel.setForeground(STATUS_RUNNING);
                        });

                        // Start file watcher to detect .jmx modifications
                        startFileWatcher();

                        // Start action bridge so Claude Code can trigger JMeter actions
                        if (testPlanDir != null) {
                            actionBridge = new JMeterActionBridge(new File(testPlanDir));
                            actionBridge.setReloadCallback(() -> reloadTestPlan());
                            actionBridge.startWatching();
                        }

                        // Monitor process exit
                        int exitCode = ptyProcess.waitFor();
                        log.info("Claude Code exited with code: {}", exitCode);

                        SwingUtilities.invokeLater(() -> {
                            statusLabel.setText("\u25CF Exited (" + exitCode + ")");
                            statusLabel.setForeground(STATUS_STOPPED);
                        });
                    }
                    else {
                        log.error("Please enable this feature in properties file.");
                    }

                }
                catch (Exception e) {
                    log.error("Failed to start Claude Code via PTY", e);
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("\u25CF Error");
                        statusLabel.setForeground(STATUS_STOPPED);
                    });
                }
                return null;
            }
        }.execute();
    }

    /**
     * Sends the current test plan context to Claude Code by typing it into the
     * terminal.
     */
    private void sendTestPlanContext() {
        if (ptyProcess == null || !ptyProcess.isAlive()) {
            return;
        }
        try {
            String context = TestPlanSerializer.serializeTestPlan();
            String message = "Here is the updated JMeter test plan context:\n" + context + "\n";
            ptyProcess.getOutputStream().write(message.getBytes(StandardCharsets.UTF_8));
            ptyProcess.getOutputStream().flush();
        } catch (Exception e) {
            log.error("Failed to send context to Claude Code", e);
        }
    }

    /**
     * Restarts Claude Code.
     */
    private void restartClaudeCode() {
        stopClaudeCode();
        // Small delay to let the old process die
        Timer timer = new Timer(500, e -> {
            // Recreate the terminal widget
            remove(terminalWidget.getComponent());
            terminalWidget = createTerminalWidget();
            add(terminalWidget.getComponent(), BorderLayout.CENTER);
            revalidate();
            repaint();
            startClaudeCode();
        });
        timer.setRepeats(false);
        timer.start();
    }

    /**
     * Builds the system prompt with test plan context.
     */
    private String buildSystemPrompt(String testPlanContext, String testPlanFilePath) {
        StringBuilder sb = new StringBuilder();

        sb.append(getDefaultPromptFromProperties()).append("\n");

        if (testPlanFilePath != null && !testPlanFilePath.isEmpty()) {
            sb.append("IMPORTANT: The JMeter test plan file is located at: ").append(testPlanFilePath).append("\n");
        }

        // Running JMeter tests instructions
        String testPlanDir = "";
        if (testPlanFilePath != null && !testPlanFilePath.isEmpty()) {
            File f = new File(testPlanFilePath);
            testPlanDir = f.getParent();
        }

        sb.append("## Running JMeter Tests\n\n");
        sb.append("### GUI Mode (default - use when user says \"run\", \"start\", \"execute the test\"):\n");
        sb.append("To start the test in GUI mode, create a file with the content \"start\":\n");
        sb.append("  echo \"start\" > ").append(testPlanDir).append("/.jmeter-claude-action\n");
        sb.append("To stop a running test:\n");
        sb.append("  echo \"stop\" > ").append(testPlanDir).append("/.jmeter-claude-action\n");
        sb.append("To gracefully shut down a running test:\n");
        sb.append("  echo \"shutdown\" > ").append(testPlanDir).append("/.jmeter-claude-action\n\n");

        sb.append("### Refreshing the JMeter GUI after editing the .jmx file:\n");
        sb.append("IMPORTANT: After ANY modification to the .jmx file (adding, editing, or deleting elements), ");
        sb.append("you MUST trigger a reload so changes appear in the JMeter GUI:\n");
        sb.append("  echo \"reload\" > ").append(testPlanDir).append("/.jmeter-claude-action\n\n");

        String jmeterHome = null;
        try {
            jmeterHome = org.apache.jmeter.util.JMeterUtils.getJMeterHome();
        } catch (Exception ignored) {
        }

        sb.append("### Non-GUI / CLI Mode (use when user explicitly says \"non-gui\", \"CLI mode\", \"headless\"):\n");
        sb.append("Run JMeter from the command line:\n");
        if (jmeterHome != null && !jmeterHome.isEmpty()) {
            sb.append("  ").append(jmeterHome).append("/bin/jmeter -n -t ")
                    .append(testPlanFilePath != null ? testPlanFilePath : "<testplan.jmx>")
                    .append(" -l results.jtl -e -o report/\n");
        } else {
            sb.append("  $JMETER_HOME/bin/jmeter -n -t ")
                    .append(testPlanFilePath != null ? testPlanFilePath : "<testplan.jmx>")
                    .append(" -l results.jtl -e -o report/\n");
        }
        sb.append("\n");

        sb.append("Current Test Plan Structure:\n\n");
        sb.append(testPlanContext);
        return sb.toString();
    }

    private String getDefaultPromptFromProperties() {
        String getPromptFromProperty = AiConfig.getProperty("jmeter.ai.terminal.claudecode.prompt", "");
        if(getPromptFromProperty != null && !getPromptFromProperty.isEmpty()) {
            log.info("The (stripped for brevity) prompt is {}", getPromptFromProperty.substring(0, Math.min(25, getPromptFromProperty.length())));
            return getPromptFromProperty;
        }

        return """
                You are an AI assistant integrated into Apache JMeter via the FeatherWand plugin.
                You have access to the current JMeter test plan structure.
                Help the user with performance testing tasks including:
                Analyzing and optimizing JMeter test plans
                - Creating new test elements (Thread Groups, Samplers, Assertions, etc.)
                - Debugging test plan issues
                - Performance testing best practices
                - JMeter scripting and configuration
                - Write, debug, and test Java code
                - Write, debug, and test Groovy script
                """;
    }

    /**
     * Stops the Claude Code process.
     */
    public void stopClaudeCode() {
        stopFileWatcher();
        cleanupClaudeMd();
        if (actionBridge != null) {
            actionBridge.stopWatching();
            actionBridge = null;
        }
        if (ptyProcess != null && ptyProcess.isAlive()) {
            ptyProcess.destroyForcibly();
            SwingUtilities.invokeLater(() -> {
                statusLabel.setText("\u25CF Stopped");
                statusLabel.setForeground(STATUS_STOPPED);
            });
        }
    }

    /**
     * Deletes the CLAUDE.md file created for this session.
     */
    private void cleanupClaudeMd() {
        if (claudeMdFile != null) {
            if (claudeMdFile.exists()) {
                claudeMdFile.delete();
                log.info("Cleaned up CLAUDE.md: {}", claudeMdFile.getAbsolutePath());
            }
            claudeMdFile = null;
        }
    }

    /**
     * Reloads the current test plan from disk silently without any dialogs.
     * Preserves the expanded/collapsed state of the tree view.
     */
    private void reloadTestPlan() {
        try {
            GuiPackage guiPackage = GuiPackage.getInstance();
            if (guiPackage == null) {
                return;
            }
            String filePath = guiPackage.getTestPlanFile();
            if (filePath == null || filePath.isEmpty()) {
                return;
            }
            File file = new File(filePath);
            if (!file.exists()) {
                return;
            }

            log.info("Silently reloading test plan from: {}", filePath);

            // Pre-flight check: if a test is currently running, FileServer will have open
            // files.
            // clearTestPlan() will crash if files are open, wiping the UI without
            // reloading.
            // We safely test this by attempting to set the base dir to its current value.
            try {
                String currentBase = org.apache.jmeter.services.FileServer.getFileServer().getBaseDir();
                org.apache.jmeter.services.FileServer.getFileServer().setBasedir(currentBase);
            } catch (IllegalStateException ex) {
                log.info("Skipping auto-reload because a test is actively running (FileServer has open files).");
                return;
            }

            // Load the tree from the file
            HashTree tree = SaveService.loadTree(file);

            // Replace the tree in JMeter's GUI
            SwingUtilities.invokeLater(() -> {
                try {

                    javax.swing.JTree jTree = guiPackage.getTreeListener().getJTree();

                    // Save expanded paths before reload
                    List<javax.swing.tree.TreePath> expandedPaths = new ArrayList<>();
                    int rowCount = jTree.getRowCount();
                    for (int i = 0; i < rowCount; i++) {
                        javax.swing.tree.TreePath path = jTree.getPathForRow(i);
                        if (jTree.isExpanded(path)) {
                            expandedPaths.add(path);
                        }
                    }

                    // Save the selected path
                    javax.swing.tree.TreePath selectedPath = jTree.getSelectionPath();
                    String selectedNodeName = null;
                    if (selectedPath != null) {
                        Object lastComponent = selectedPath.getLastPathComponent();
                        if (lastComponent instanceof JMeterTreeNode) {
                            selectedNodeName = ((JMeterTreeNode) lastComponent).getName();
                        }
                    }

                    // Save expanded node names (path of names from root to node)
                    List<List<String>> expandedNodePaths = new ArrayList<>();
                    for (javax.swing.tree.TreePath path : expandedPaths) {
                        List<String> nodeNames = new ArrayList<>();
                        for (Object component : path.getPath()) {
                            if (component instanceof JMeterTreeNode) {
                                nodeNames.add(((JMeterTreeNode) component).getName());
                            }
                        }
                        expandedNodePaths.add(nodeNames);
                    }

                    // Clear the existing test plan from the model to prevent appending duplicates
                    guiPackage.clearTestPlan();

                    // Add the reloaded tree to the cleared model
                    guiPackage.addSubTree(tree);

                    // Restore the file path, as clearTestPlan() sets it to null
                    guiPackage.setTestPlanFile(filePath);

                    JMeterTreeModel newTreeModel = guiPackage.getTreeModel();
                    JMeterTreeNode root = (JMeterTreeNode) newTreeModel.getRoot();
                    newTreeModel.nodeStructureChanged(root);

                    // Restore expanded paths by matching node names
                    for (List<String> nodeNames : expandedNodePaths) {
                        restoreExpandedPath(jTree, newTreeModel, nodeNames);
                    }

                    // Restore selection
                    if (selectedNodeName != null) {
                        restoreSelection(jTree, newTreeModel, selectedNodeName);
                    }

                    guiPackage.getMainFrame().repaint();
                    log.info("Test plan silently reloaded with tree state preserved");
                } catch (Exception e) {
                    log.error("Failed to update tree after reload", e);
                }
            });

        } catch (Exception e) {
            log.error("Failed to reload test plan", e);
        }
    }

    /**
     * Restores an expanded path by matching node names from root to leaf.
     */
    private void restoreExpandedPath(javax.swing.JTree jTree,
            JMeterTreeModel treeModel,
            List<String> nodeNames) {
        JMeterTreeNode current = (JMeterTreeNode) treeModel.getRoot();
        List<Object> pathComponents = new ArrayList<>();
        pathComponents.add(current);

        for (int i = 1; i < nodeNames.size(); i++) {
            String targetName = nodeNames.get(i);
            boolean found = false;
            for (int j = 0; j < current.getChildCount(); j++) {
                JMeterTreeNode child = (JMeterTreeNode) current.getChildAt(j);
                if (child.getName().equals(targetName)) {
                    pathComponents.add(child);
                    current = child;
                    found = true;
                    break;
                }
            }
            if (!found) {
                break;
            }
        }

        if (pathComponents.size() > 1) {
            javax.swing.tree.TreePath treePath = new javax.swing.tree.TreePath(pathComponents.toArray());
            jTree.expandPath(treePath);
        }
    }

    /**
     * Restores the selected node by name.
     */
    private void restoreSelection(javax.swing.JTree jTree,
            JMeterTreeModel treeModel,
            String nodeName) {
        JMeterTreeNode root = (JMeterTreeNode) treeModel.getRoot();
        JMeterTreeNode target = findNodeByName(root, nodeName);
        if (target != null) {
            javax.swing.tree.TreePath path = new javax.swing.tree.TreePath(target.getPath());
            jTree.setSelectionPath(path);
            jTree.scrollPathToVisible(path);
        }
    }

    /**
     * Finds a node by name in the tree (depth-first).
     */
    private JMeterTreeNode findNodeByName(JMeterTreeNode parent, String name) {
        if (parent.getName().equals(name)) {
            return parent;
        }
        for (int i = 0; i < parent.getChildCount(); i++) {
            JMeterTreeNode child = (JMeterTreeNode) parent.getChildAt(i);
            JMeterTreeNode result = findNodeByName(child, name);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    /**
     * Starts a timer that watches the .jmx file for modifications.
     * When Claude Code edits the file, it automatically reloads it in JMeter.
     */
    private void startFileWatcher() {
        if (testPlanFilePath == null || testPlanFilePath.isEmpty()) {
            return;
        }
        File file = new File(testPlanFilePath);
        if (!file.exists()) {
            return;
        }
        lastKnownModified = file.lastModified();

        // Poll every 2 seconds
        fileWatchTimer = new Timer(2000, e -> {
            File f = new File(testPlanFilePath);
            if (f.exists()) {
                long currentModified = f.lastModified();
                if (currentModified > lastKnownModified) {
                    lastKnownModified = currentModified;

                    // Suppress auto-reload when a test was recently started via the
                    // action bridge. JMeter may save the .jmx on test start, and
                    // reloading after the test ends would clear View Results Tree.
                    if (actionBridge != null && actionBridge.isRecentlyStarted()) {
                        log.info("Auto-reload suppressed: test recently triggered via action bridge ({})",
                                testPlanFilePath);
                        return;
                    }

                    log.info("Test plan file modified, auto-reloading: {}", testPlanFilePath);
                    reloadTestPlan();
                }
            }
        });
        fileWatchTimer.start();
        log.info("File watcher started for: {}", testPlanFilePath);
    }

    /**
     * Stops the file watcher timer.
     */
    private void stopFileWatcher() {
        if (fileWatchTimer != null) {
            fileWatchTimer.stop();
            fileWatchTimer = null;
        }
    }

    /**
     * Cleans up resources.
     */
    public void dispose() {
        stopClaudeCode();
        if (terminalWidget != null) {
            terminalWidget.close();
        }
    }
}
