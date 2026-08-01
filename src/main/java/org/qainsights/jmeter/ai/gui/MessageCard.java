package org.qainsights.jmeter.ai.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextPane;
import javax.swing.Timer;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A single chat message rendered as a card ("bubble") in the transcript:
 * a slim header (sender name + per-message Copy button) above a body that
 * shows either markdown-rendered content or raw streamed text.
 * <p>
 * User cards get a softly tinted bubble background; assistant cards stay
 * flat/full-width in the style of modern AI chat tools. The card keeps the
 * raw markdown source so Copy reproduces exactly what the AI returned.
 */
class MessageCard extends JPanel {

    private static final Logger log = LoggerFactory.getLogger(
        MessageCard.class
    );

    /** Who sent the message. */
    enum Role {
        USER("You"),
        ASSISTANT("Feather Wand");

        private final String displayName;

        Role(String displayName) {
            this.displayName = displayName;
        }

        String displayName() {
            return displayName;
        }
    }

    /** Corner radius (px) for the user bubble's rounded background. */
    static final int BUBBLE_ARC = 14;

    private final Role role;
    private final JTextPane body;
    private final StringBuilder rawText = new StringBuilder();
    private final MessageProcessor messageProcessor;

    MessageCard(Role role, Font font, MessageProcessor messageProcessor) {
        super(new BorderLayout());
        this.role = role;
        this.messageProcessor = messageProcessor;

        // Always non-opaque: the user bubble's rounded background is painted
        // manually in paintComponent (a JPanel background fill is rectangular).
        setOpaque(false);
        applyTheme();
        setBorder(
            BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(4, 6, 4, 6),
                BorderFactory.createEmptyBorder(6, 10, 8, 10)
            )
        );

        add(createHeader(), BorderLayout.NORTH);

        body = new JTextPane();
        body.setEditable(false);
        body.setOpaque(false);
        if (font != null) {
            body.setFont(font);
        }
        add(body, BorderLayout.CENTER);
    }

    /** Header row: bold sender name on the left, Copy button on the right. */
    private JPanel createHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);

        JLabel sender = new JLabel(role.displayName());
        sender.setFont(sender.getFont().deriveFont(Font.BOLD));
        sender.setForeground(
            role == Role.USER
                ? ThemeColors.secondaryText()
                : ThemeColors.accent()
        );
        header.add(sender, BorderLayout.WEST);

        JButton copy = new JButton("Copy");
        copy.setToolTipText("Copy this message");
        copy.setFont(copy.getFont().deriveFont(10f));
        copy.setMargin(new Insets(1, 6, 1, 6));
        copy.setFocusPainted(false);
        copy.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        copy.addActionListener(e -> {
            java.awt.Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(
                    new java.awt.datatransfer.StringSelection(getText()),
                    null
                );
            copy.setText("Copied!");
            Timer timer = new Timer(1500, ev -> copy.setText("Copy"));
            timer.setRepeats(false);
            timer.start();
        });

        JPanel copyWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        copyWrap.setOpaque(false);
        copyWrap.add(copy);
        header.add(copyWrap, BorderLayout.EAST);
        return header;
    }

    /** The raw (markdown-source) text of this message. */
    String getText() {
        return rawText.toString();
    }

    Role getRole() {
        return role;
    }

    /**
     * Renders markdown content into the card body, replacing anything shown
     * so far (including previously streamed raw text).
     */
    void setMarkdownContent(String markdown) {
        rawText.setLength(0);
        rawText.append(markdown);
        try {
            body.setText("");
            StyledDocument doc = body.getStyledDocument();
            Style defaultStyle = doc.getStyle("default");
            if (defaultStyle != null) {
                StyleConstants.setForeground(
                    defaultStyle,
                    ThemeColors.themeColor("TextPane.foreground", Color.BLACK)
                );
            }
            messageProcessor.appendMessage(doc, markdown, null, true);
        } catch (BadLocationException e) {
            log.error("Error rendering message markdown", e);
        }
        revalidate();
        repaint();
    }

    /** Appends a raw (unparsed) text token, used while streaming. */
    void appendRawText(String token) {
        rawText.append(token);
        try {
            StyledDocument doc = body.getStyledDocument();
            doc.insertString(doc.getLength(), token, new SimpleAttributeSet());
        } catch (BadLocationException e) {
            log.error("Error appending stream token to card", e);
        }
    }

    /** Shows plain text without markdown parsing (e.g. user messages). */
    void setPlainContent(String text) {
        rawText.setLength(0);
        rawText.append(text);
        try {
            body.setText("");
            StyledDocument doc = body.getStyledDocument();
            doc.insertString(0, text, new SimpleAttributeSet());
        } catch (BadLocationException e) {
            log.error("Error rendering plain message", e);
        }
        revalidate();
        repaint();
    }

    /** Applies a new body font (used by the zoom/font-scale feature). */
    void applyFont(Font font) {
        if (font != null) {
            body.setFont(font);
        }
    }

    /** Re-applies theme-derived colors (called on look-and-feel changes). */
    final void applyTheme() {
        if (role == Role.USER) {
            setBackground(ThemeColors.userBubbleBackground());
        }
    }

    /**
     * Paints the user bubble as a rounded card (fill + subtle 1px border)
     * inside the outer spacing margin. Assistant cards paint nothing here
     * and stay flat.
     */
    @Override
    protected void paintComponent(java.awt.Graphics g) {
        if (role == Role.USER) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            try {
                g2.setRenderingHint(
                    java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON
                );
                Insets outer = new Insets(4, 6, 4, 6);
                int x = outer.left;
                int y = outer.top;
                int w = getWidth() - outer.left - outer.right - 1;
                int h = getHeight() - outer.top - outer.bottom - 1;
                g2.setColor(getBackground());
                g2.fillRoundRect(x, y, w, h, BUBBLE_ARC, BUBBLE_ARC);
                g2.setColor(ThemeColors.border());
                g2.drawRoundRect(x, y, w, h, BUBBLE_ARC, BUBBLE_ARC);
            } finally {
                g2.dispose();
            }
        }
        super.paintComponent(g);
    }
}
