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
 * Collapsible card in the transcript that shows the model's reasoning. While
 * thinking streams in, a spinner animates next to a "Thinking" header and the
 * text accumulates live; when the first answer token arrives (or the stream
 * completes) the card auto-collapses to a "Thoughts" summary line, clickable
 * to re-expand. Modelled on {@link ToolActivityGroup}.
 */
class ThinkingCard extends JPanel {

    /** ASCII spinner frames (braille spinners have spotty font coverage). */
    private static final String[] SPINNER_FRAMES = {"|", "/", "-", "\\"};
    private static final int SPINNER_DELAY_MS = 120;
    private static final int MAX_HEADER_CHARS = 60;

    private final JLabel headerLabel;
    private final JTextArea bodyArea;
    private final JPanel bodyWrapper;
    private final Timer spinnerTimer;

    private int spinnerFrame = 0;
    private boolean running = true;
    private boolean collapsed = false;

    ThinkingCard() {
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
        bodyArea.setLineWrap(true);
        bodyArea.setWrapStyleWord(true);
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

    /** Appends streamed reasoning text to the card body. */
    void appendText(String text) {
        bodyArea.append(text);
        updateHeader();
        revalidate();
    }

    /**
     * Marks thinking as finished: stops the spinner and auto-collapses the
     * body, leaving a clickable "Thoughts" summary. Idempotent.
     */
    void finish() {
        if (!running) {
            return;
        }
        running = false;
        spinnerTimer.stop();
        setCollapsed(true);
    }

    /** True while reasoning is still streaming (spinner active). */
    boolean isRunning() {
        return running;
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

    /** The accumulated reasoning text. */
    String getText() {
        return bodyArea.getText();
    }

    /** Stops the spinner timer; call when discarding the card. */
    void dispose() {
        spinnerTimer.stop();
    }

    private void updateHeader() {
        String chevron = collapsed ? "▸ " : "▾ ";
        if (running) {
            headerLabel.setText(chevron + SPINNER_FRAMES[spinnerFrame] + " Thinking…");
        } else {
            headerLabel.setText(chevron + "✦ Thoughts" + previewSuffix());
        }
    }

    /** A short single-line preview of the reasoning for the collapsed header. */
    private String previewSuffix() {
        String text = bodyArea.getText().replace('\n', ' ').trim();
        if (text.isEmpty()) {
            return "";
        }
        String preview = text.length() > MAX_HEADER_CHARS
                ? text.substring(0, MAX_HEADER_CHARS) + "…"
                : text;
        return " — " + preview;
    }
}
