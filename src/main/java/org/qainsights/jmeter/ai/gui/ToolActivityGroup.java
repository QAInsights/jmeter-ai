package org.qainsights.jmeter.ai.gui;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.Timer;
import org.qainsights.jmeter.ai.gui.theme.ThemeColors;

/**
 * Collapsible container for agent tool-call activity in the transcript,
 * in the style of modern AI coding tools: while the agent works, a spinner
 * animates next to an "Agent activity" header and each tool call appears as
 * a dim monospaced line; when the run finishes the spinner stops, the header
 * summarizes the call count, and the group auto-collapses to keep the chat
 * readable. Clicking the header toggles expansion at any time.
 */
class ToolActivityGroup extends JPanel {

    /** ASCII spinner frames (braille spinners have spotty font coverage). */
    private static final String[] SPINNER_FRAMES = {"|", "/", "-", "\\"};
    private static final int SPINNER_DELAY_MS = 120;

    private final JLabel headerLabel;
    private final JTextArea bodyArea;
    private final JPanel bodyWrapper;
    private final Timer spinnerTimer;

    private int spinnerFrame = 0;
    private int lineCount = 0;
    private boolean running = true;
    private boolean collapsed = false;

    ToolActivityGroup() {
        super(new BorderLayout());
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));

        headerLabel = new JLabel();
        headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD, 11f));
        headerLabel.setForeground(ThemeColors.secondaryText());
        headerLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        headerLabel.setBorder(BorderFactory.createEmptyBorder(2, 10, 2, 10));
        headerLabel.addMouseListener(
            new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    setCollapsed(!collapsed);
                }
            }
        );
        add(headerLabel, BorderLayout.NORTH);

        bodyArea = new JTextArea();
        bodyArea.setEditable(false);
        bodyArea.setOpaque(false);
        bodyArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        bodyArea.setForeground(ThemeColors.secondaryText());
        bodyArea.setBorder(BorderFactory.createEmptyBorder(0, 20, 2, 10));

        bodyWrapper = new JPanel(new BorderLayout());
        bodyWrapper.setOpaque(false);
        bodyWrapper.add(bodyArea, BorderLayout.CENTER);
        add(bodyWrapper, BorderLayout.CENTER);

        spinnerTimer = new Timer(SPINNER_DELAY_MS, e -> {
            spinnerFrame = (spinnerFrame + 1) % SPINNER_FRAMES.length;
            updateHeader();
        });
        spinnerTimer.start();
        updateHeader();
    }

    /** Appends one tool-activity line to the group body. */
    void addLine(String line) {
        lineCount++;
        if (bodyArea.getDocument().getLength() > 0) {
            bodyArea.append("\n");
        }
        bodyArea.append(line);
        updateHeader();
        revalidate();
    }

    /**
     * Marks the activity run as finished: stops the spinner, summarizes the
     * header, and auto-collapses the body to keep the transcript tidy.
     */
    void finish() {
        if (!running) {
            return;
        }
        running = false;
        spinnerTimer.stop();
        setCollapsed(true);
    }

    /** True while the activity run is in progress (spinner active). */
    boolean isRunning() {
        return running;
    }

    /** Number of activity lines recorded so far. */
    int getLineCount() {
        return lineCount;
    }

    /** Toggles body visibility and updates the header affordance. */
    void setCollapsed(boolean collapsed) {
        this.collapsed = collapsed;
        bodyWrapper.setVisible(!collapsed);
        updateHeader();
        revalidate();
    }

    boolean isCollapsed() {
        return collapsed;
    }

    /** The activity text (one call per line). */
    String getText() {
        return bodyArea.getText();
    }

    /** Stops the spinner timer; call when discarding the group. */
    void dispose() {
        spinnerTimer.stop();
    }

    private void updateHeader() {
        String chevron = collapsed ? "▸ " : "▾ ";
        if (running) {
            headerLabel.setText(
                chevron + SPINNER_FRAMES[spinnerFrame] + " Agent activity"
                    + (lineCount > 0 ? " (" + lineCount + ")" : "")
            );
        } else {
            headerLabel.setText(
                chevron + "⚙ Agent activity - " + lineCount
                    + (lineCount == 1 ? " tool call" : " tool calls")
            );
        }
    }
}
