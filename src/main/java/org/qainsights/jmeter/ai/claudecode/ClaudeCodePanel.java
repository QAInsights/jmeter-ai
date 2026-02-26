package org.qainsights.jmeter.ai.claudecode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A JPanel that provides an embedded terminal-like interface for Claude Code.
 * <p>
 * Uses Claude Code's {@code -p} (print) mode for each user message, with
 * {@code --resume} to maintain conversation continuity across invocations.
 * <p>
 * Features:
 * <ul>
 * <li>Dark-themed terminal output area</li>
 * <li>Input field for sending messages</li>
 * <li>Conversation persistence via session IDs</li>
 * <li>Test plan context passed as system prompt</li>
 * <li>Streaming output display</li>
 * </ul>
 */
public class ClaudeCodePanel extends JPanel {
    private static final Logger log = LoggerFactory.getLogger(ClaudeCodePanel.class);

    // Terminal colors
    private static final Color BACKGROUND_COLOR = new Color(30, 30, 30);
    private static final Color FOREGROUND_COLOR = new Color(204, 204, 204);
    private static final Color INPUT_BG_COLOR = new Color(45, 45, 45);
    private static final Color PROMPT_COLOR = new Color(86, 182, 194);
    private static final Color ERROR_COLOR = new Color(224, 108, 117);
    private static final Color HEADER_BG_COLOR = new Color(40, 40, 40);
    private static final Color HEADER_FG_COLOR = new Color(220, 220, 220);
    private static final Color BUTTON_BG = new Color(55, 55, 55);
    private static final Color BUTTON_FG = new Color(200, 200, 200);
    private static final Color STATUS_RUNNING = new Color(152, 195, 121);
    private static final Color STATUS_STOPPED = ERROR_COLOR;
    private static final Color DIM_COLOR = new Color(128, 128, 128);

    // Font
    private static final Font TERMINAL_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 13);
    private static final Font HEADER_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 13);

    // Components
    private JTextPane outputArea;
    private JTextField inputField;
    private JLabel statusLabel;
    private JButton stopButton;
    private JButton newSessionButton;
    private JButton refreshContextButton;
    private JButton sendButton;

    // Process management
    private volatile Process currentProcess;
    private volatile boolean isProcessing = false;

    // Session management
    private String sessionId;
    private boolean isFirstMessage;
    private String claudeBinaryPath;
    private String systemPrompt;

    public ClaudeCodePanel() {
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(550, 600));
        setMinimumSize(new Dimension(400, 300));
        setBackground(BACKGROUND_COLOR);

        // Initialize session
        startNewSession();

        // Locate Claude Code binary
        claudeBinaryPath = ClaudeCodeLocator.findClaudeCodeBinary();

        // Create header
        add(createHeaderPanel(), BorderLayout.NORTH);

        // Create terminal output area
        outputArea = createOutputArea();
        JScrollPane scrollPane = new JScrollPane(outputArea);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setBackground(BACKGROUND_COLOR);
        scrollPane.getViewport().setBackground(BACKGROUND_COLOR);
        add(scrollPane, BorderLayout.CENTER);

        // Create input panel
        add(createInputPanel(), BorderLayout.SOUTH);

        // Show welcome message
        showWelcomeMessage();
    }

    /**
     * Starts a new session with a fresh session ID.
     */
    private void startNewSession() {
        sessionId = UUID.randomUUID().toString();
        isFirstMessage = true;
        systemPrompt = buildSystemPrompt(TestPlanSerializer.serializeTestPlan());
        log.info("New Claude Code session: {}", sessionId);
    }

    /**
     * Shows a welcome message in the terminal.
     */
    private void showWelcomeMessage() {
        appendText("Claude Code Terminal\n", PROMPT_COLOR);
        appendText("Binary: " + claudeBinaryPath + "\n", DIM_COLOR);
        appendText("Session: " + sessionId.substring(0, 8) + "...\n\n", DIM_COLOR);
        appendText("Type a message and press Enter to interact with Claude Code.\n", FOREGROUND_COLOR);
        appendText("Claude has access to your current JMeter test plan context.\n\n", DIM_COLOR);
    }

    /**
     * Creates the header panel with title, status, and control buttons.
     */
    private JPanel createHeaderPanel() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(HEADER_BG_COLOR);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(60, 60, 60)),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));

        // Left side: title + status
        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        leftPanel.setOpaque(false);

        JLabel titleLabel = new JLabel("Claude Code");
        titleLabel.setFont(HEADER_FONT);
        titleLabel.setForeground(HEADER_FG_COLOR);
        leftPanel.add(titleLabel);

        statusLabel = new JLabel("● Ready");
        statusLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        statusLabel.setForeground(STATUS_RUNNING);
        leftPanel.add(statusLabel);

        header.add(leftPanel, BorderLayout.WEST);

        // Right side: control buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttonPanel.setOpaque(false);

        refreshContextButton = createHeaderButton("\u21BB Ctx", "Refresh test plan context");
        refreshContextButton.addActionListener(e -> refreshContext());
        buttonPanel.add(refreshContextButton);

        newSessionButton = createHeaderButton("+ New", "Start a new conversation");
        newSessionButton.addActionListener(e -> resetSession());
        buttonPanel.add(newSessionButton);

        stopButton = createHeaderButton("\u25A0 Stop", "Stop current request");
        stopButton.addActionListener(e -> stopCurrentProcess());
        stopButton.setEnabled(false);
        buttonPanel.add(stopButton);

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
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(70, 70, 70), 1, true),
                BorderFactory.createEmptyBorder(3, 8, 3, 8)));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    /**
     * Creates the terminal output area.
     */
    private JTextPane createOutputArea() {
        JTextPane pane = new JTextPane();
        pane.setEditable(false);
        pane.setBackground(BACKGROUND_COLOR);
        pane.setForeground(FOREGROUND_COLOR);
        pane.setFont(TERMINAL_FONT);
        pane.setCaretColor(FOREGROUND_COLOR);
        pane.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));

        StyledDocument doc = pane.getStyledDocument();
        SimpleAttributeSet leftAlign = new SimpleAttributeSet();
        StyleConstants.setAlignment(leftAlign, StyleConstants.ALIGN_LEFT);
        doc.setParagraphAttributes(0, doc.getLength(), leftAlign, false);

        return pane;
    }

    /**
     * Creates the input panel with prompt and text field.
     */
    private JPanel createInputPanel() {
        JPanel inputPanel = new JPanel(new BorderLayout(4, 0));
        inputPanel.setBackground(INPUT_BG_COLOR);
        inputPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(60, 60, 60)),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));

        // Prompt label
        JLabel promptLabel = new JLabel("> ");
        promptLabel.setFont(TERMINAL_FONT);
        promptLabel.setForeground(PROMPT_COLOR);
        inputPanel.add(promptLabel, BorderLayout.WEST);

        // Input field
        inputField = new JTextField();
        inputField.setFont(TERMINAL_FONT);
        inputField.setBackground(INPUT_BG_COLOR);
        inputField.setForeground(FOREGROUND_COLOR);
        inputField.setCaretColor(FOREGROUND_COLOR);
        inputField.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        inputField.addActionListener(e -> sendMessage());
        inputPanel.add(inputField, BorderLayout.CENTER);

        // Send button
        sendButton = new JButton("\u23CE");
        sendButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        sendButton.setBackground(BUTTON_BG);
        sendButton.setForeground(PROMPT_COLOR);
        sendButton.setFocusPainted(false);
        sendButton.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(70, 70, 70), 1, true),
                BorderFactory.createEmptyBorder(2, 8, 2, 8)));
        sendButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        sendButton.addActionListener(e -> sendMessage());
        inputPanel.add(sendButton, BorderLayout.EAST);

        return inputPanel;
    }

    /**
     * Sends a message to Claude Code using -p mode.
     * Uses --resume for subsequent messages to maintain conversation context.
     */
    private void sendMessage() {
        String message = inputField.getText().trim();
        if (message.isEmpty() || isProcessing) {
            return;
        }

        inputField.setText("");

        // Echo user message
        appendText("> " + message + "\n", PROMPT_COLOR);

        // Set processing state
        setProcessingState(true);

        // Run Claude Code in background
        new SwingWorker<Void, String>() {
            @Override
            protected Void doInBackground() {
                try {
                    ProcessBuilder pb = new ProcessBuilder();

                    if (isFirstMessage) {
                        // First message: use --session-id and --system-prompt
                        pb.command(
                                claudeBinaryPath,
                                "-p",
                                "--output-format", "text",
                                "--session-id", sessionId,
                                "--system-prompt", systemPrompt,
                                "--verbose",
                                message);
                        isFirstMessage = false;
                    } else {
                        // Subsequent messages: use --resume to continue the session
                        pb.command(
                                claudeBinaryPath,
                                "-p",
                                "--output-format", "text",
                                "--resume", sessionId,
                                "--verbose",
                                message);
                    }

                    pb.redirectErrorStream(false);
                    pb.environment().put("NO_COLOR", "1");
                    pb.environment().put("TERM", "dumb");

                    currentProcess = pb.start();

                    // Close stdin immediately since we pass the prompt as argument
                    currentProcess.getOutputStream().close();

                    // Read stdout in real-time
                    Thread stderrThread = new Thread(() -> {
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(currentProcess.getErrorStream(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                final String errLine = line;
                                log.debug("Claude stderr: {}", errLine);
                            }
                        } catch (IOException e) {
                            log.debug("Stderr read ended: {}", e.getMessage());
                        }
                    }, "claude-stderr");
                    stderrThread.setDaemon(true);
                    stderrThread.start();

                    // Read stdout character by character for streaming display
                    try (InputStream stdout = currentProcess.getInputStream()) {
                        byte[] buffer = new byte[1024];
                        int bytesRead;
                        while ((bytesRead = stdout.read(buffer)) != -1) {
                            String text = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                            appendText(text, FOREGROUND_COLOR);
                        }
                    }

                    int exitCode = currentProcess.waitFor();
                    if (exitCode != 0) {
                        appendText("\n[Process exited with code " + exitCode + "]\n", ERROR_COLOR);
                    }

                    // Ensure newline after response
                    appendText("\n", FOREGROUND_COLOR);

                } catch (Exception e) {
                    log.error("Error running Claude Code", e);
                    appendText("\n\u274C Error: " + e.getMessage() + "\n", ERROR_COLOR);

                    if (e.getMessage() != null && e.getMessage().contains("Cannot run program")) {
                        appendText("\nPlease ensure Claude Code is installed:\n", FOREGROUND_COLOR);
                        appendText("  npm install -g @anthropic-ai/claude-code\n\n", PROMPT_COLOR);
                    }
                } finally {
                    currentProcess = null;
                }
                return null;
            }

            @Override
            protected void done() {
                setProcessingState(false);
            }
        }.execute();
    }

    /**
     * Sets the UI to processing or idle state.
     */
    private void setProcessingState(boolean processing) {
        isProcessing = processing;
        SwingUtilities.invokeLater(() -> {
            inputField.setEnabled(!processing);
            sendButton.setEnabled(!processing);
            stopButton.setEnabled(processing);
            statusLabel.setText(processing ? "\u25CF Processing..." : "\u25CF Ready");
            statusLabel.setForeground(processing ? new Color(200, 180, 50) : STATUS_RUNNING);
            if (!processing) {
                inputField.requestFocusInWindow();
            }
        });
    }

    /**
     * Refreshes the test plan context for the next message.
     */
    private void refreshContext() {
        systemPrompt = buildSystemPrompt(TestPlanSerializer.serializeTestPlan());
        appendText("[Test plan context refreshed]\n", DIM_COLOR);
        // Reset session so the next message uses the new system prompt
        sessionId = UUID.randomUUID().toString();
        isFirstMessage = true;
        appendText("[New session started with updated context: " + sessionId.substring(0, 8) + "...]\n\n", DIM_COLOR);
    }

    /**
     * Resets the session, clearing output and starting fresh.
     */
    private void resetSession() {
        stopCurrentProcess();
        startNewSession();
        SwingUtilities.invokeLater(() -> {
            outputArea.setText("");
            showWelcomeMessage();
        });
    }

    /**
     * Stops the currently running Claude Code process.
     */
    public void stopCurrentProcess() {
        Process proc = currentProcess;
        if (proc != null && proc.isAlive()) {
            proc.destroyForcibly();
            appendText("\n[Stopped]\n", new Color(200, 180, 50));
        }
    }

    /**
     * Builds the system prompt with test plan context.
     */
    private String buildSystemPrompt(String testPlanContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an AI assistant integrated into Apache JMeter via the FeatherWand plugin. ");
        sb.append("You have access to the current JMeter test plan structure. ");
        sb.append("Help the user with performance testing tasks including:\n");
        sb.append("- Analyzing and optimizing JMeter test plans\n");
        sb.append("- Creating new test elements (Thread Groups, Samplers, Assertions, etc.)\n");
        sb.append("- Debugging test plan issues\n");
        sb.append("- Performance testing best practices\n");
        sb.append("- JMeter scripting and configuration\n\n");
        sb.append("Current Test Plan:\n\n");
        sb.append(testPlanContext);
        return sb.toString();
    }

    /**
     * Appends text to the terminal output area with the specified color.
     */
    private void appendText(String text, Color color) {
        SwingUtilities.invokeLater(() -> {
            try {
                StyledDocument doc = outputArea.getStyledDocument();
                SimpleAttributeSet attrs = new SimpleAttributeSet();
                StyleConstants.setForeground(attrs, color);
                StyleConstants.setFontFamily(attrs, Font.MONOSPACED);
                StyleConstants.setFontSize(attrs, 13);
                doc.insertString(doc.getLength(), text, attrs);
                outputArea.setCaretPosition(doc.getLength());
            } catch (BadLocationException e) {
                log.debug("Error appending text to terminal", e);
            }
        });
    }

    /**
     * Cleans up resources when the panel is removed.
     */
    public void dispose() {
        stopCurrentProcess();
    }

    /**
     * Stops Claude Code — called by ClaudeCodeMenuItem when hiding the panel.
     */
    public void stopClaudeCode() {
        stopCurrentProcess();
    }
}
