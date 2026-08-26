package org.qainsights.jmeter.ai.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.ImageIcon;
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
import org.qainsights.jmeter.ai.gui.theme.UiTokens;
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
    static final int BUBBLE_ARC = UiTokens.RADIUS_LARGE * 2;

    private final Role role;
    private final JTextPane body;
    private JLabel senderLabel;
    private final StringBuilder rawText = new StringBuilder();
    private final MessageProcessor messageProcessor;
    private final JPanel chipsPanel;
    private java.util.function.Function<String, org.qainsights.jmeter.ai.service.attach.Attachment> attachmentLookup;
    private java.util.function.Consumer<String> savePromptHandler;
    private JButton savePromptButton;

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
                BorderFactory.createEmptyBorder(
                        UiTokens.SPACE_1, UiTokens.SPACE_2,
                        UiTokens.SPACE_1, UiTokens.SPACE_2),
                BorderFactory.createEmptyBorder(
                        UiTokens.SPACE_2, UiTokens.SPACE_3,
                        UiTokens.SPACE_3, UiTokens.SPACE_3)
            )
        );

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setOpaque(false);
        top.add(createHeader());
        chipsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        chipsPanel.setOpaque(false);
        chipsPanel.setVisible(false);
        top.add(chipsPanel);
        add(top, BorderLayout.NORTH);

        body = new JTextPane();
        body.setEditable(false);
        body.setOpaque(false);
        if (font != null) {
            body.setFont(font);
        }
        add(body, BorderLayout.CENTER);
    }

    /** Registers the attachment lookup used to render file chips for {@code [file:<id>]} markers. */
    void setAttachmentLookup(
            java.util.function.Function<String, org.qainsights.jmeter.ai.service.attach.Attachment> lookup) {
        this.attachmentLookup = lookup;
    }

    /**
     * Registers the handler behind the user card's "Save prompt" button,
     * called with the message text (attachment markers replaced by file
     * names). The button appears once a handler is set.
     */
    void setSavePromptHandler(java.util.function.Consumer<String> handler) {
        this.savePromptHandler = handler;
        if (savePromptButton != null) {
            savePromptButton.setVisible(handler != null);
        }
    }

    /**
     * The message text with attachment markers replaced by their file names
     * (unknown ids stripped) - what the "Save prompt" action hands to the
     * prompt library, since session-scoped marker ids are meaningless later.
     */
    String textForPrompt() {
        String text = getText();
        java.util.regex.Matcher matcher =
                org.qainsights.jmeter.ai.service.attach.AttachmentMarkerParser.MARKER_PATTERN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            org.qainsights.jmeter.ai.service.attach.Attachment attachment =
                    attachmentLookup == null ? null : attachmentLookup.apply(matcher.group(1));
            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(
                    attachment != null ? "[" + attachment.getFileName() + "]" : ""));
        }
        matcher.appendTail(out);
        return out.toString().replaceAll("[ \\t]+", " ").replaceAll(" ?\\n ?", "\n").trim();
    }

    /** Header row: bold sender name on the left, Copy button on the right. */
    private JPanel createHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);

        senderLabel = new JLabel(role.displayName());
        senderLabel.setFont(UiTokens.label(senderLabel.getFont()));
        if (role == Role.ASSISTANT) {
            java.net.URL mark = MessageCard.class.getResource(
                    "/org/qainsights/jmeter/ai/featherwand-16x16.png");
            if (mark != null) {
                senderLabel.setIcon(new ImageIcon(mark));
                senderLabel.setIconTextGap(UiTokens.SPACE_1);
            }
        }
        applySenderTheme();
        header.add(senderLabel, BorderLayout.WEST);

        JButton copy = new QuietButton("Copy");
        copy.setIcon(ActionIcons.copy(12));
        copy.setIconTextGap(UiTokens.SPACE_1);
        copy.setToolTipText("Copy this message");
        copy.setFont(UiTokens.caption(copy.getFont()));
        copy.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        copy.addActionListener(e -> {
            java.awt.Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(
                    new java.awt.datatransfer.StringSelection(getText()),
                    null
                );
            copy.setText("Copied \u2713");
            Timer timer = new Timer(2000, ev -> copy.setText("Copy"));
            timer.setRepeats(false);
            timer.start();
        });

        JPanel copyWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        copyWrap.setOpaque(false);
        if (role == Role.USER) {
            savePromptButton = new QuietButton("Save prompt");
            savePromptButton.setToolTipText("Save this message as a reusable prompt");
            savePromptButton.setFont(UiTokens.caption(savePromptButton.getFont()));
            savePromptButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            savePromptButton.setVisible(savePromptHandler != null);
            savePromptButton.addActionListener(e -> {
                if (savePromptHandler != null) {
                    savePromptHandler.accept(textForPrompt());
                }
            });
            copyWrap.add(savePromptButton);
        }
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
        java.util.List<String> markerIds =
                org.qainsights.jmeter.ai.service.attach.AttachmentMarkerParser.findMarkerIds(text);
        String displayText = markerIds.isEmpty()
                ? text
                : org.qainsights.jmeter.ai.service.attach.AttachmentMarkerParser.stripMarkers(text);
        renderChips(markerIds);
        try {
            body.setText("");
            StyledDocument doc = body.getStyledDocument();
            doc.insertString(0, displayText, new SimpleAttributeSet());
        } catch (BadLocationException e) {
            log.error("Error rendering plain message", e);
        }
        revalidate();
        repaint();
    }

    /** Renders one chip per attachment marker (rich label from the registry, id fallback). */
    private void renderChips(java.util.List<String> markerIds) {
        chipsPanel.removeAll();
        chipsPanel.setVisible(false);
        if (markerIds.isEmpty()) {
            return;
        }
        for (String id : markerIds) {
            String label = "[file:" + id + "]";
            if (attachmentLookup != null) {
                org.qainsights.jmeter.ai.service.attach.Attachment attachment = attachmentLookup.apply(id);
                if (attachment != null) {
                    label = attachment.chipLabel();
                }
            }
            // File names come from disk - never render them as HTML.
            JLabel chip = LabelUtils.plain(label, AttachIcons.document(11), JLabel.LEFT);
            chip.setFont(chip.getFont().deriveFont(Font.PLAIN, 10f));
            chip.setForeground(ThemeColors.secondaryText());
            chipsPanel.add(chip);
        }
        chipsPanel.setVisible(true);
    }

    /** Number of rendered chips (for tests). */
    int getChipCount() {
        return chipsPanel.getComponentCount();
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
        applySenderTheme();
        if (body != null) {
            body.setForeground(ThemeColors.foreground());
        }
        repaint();
    }

    private void applySenderTheme() {
        if (senderLabel != null) {
            senderLabel.setForeground(
                    role == Role.USER ? ThemeColors.secondaryText() : ThemeColors.accent());
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
                Insets outer = new Insets(
                        UiTokens.SPACE_1, UiTokens.SPACE_2,
                        UiTokens.SPACE_1, UiTokens.SPACE_2);
                int x = outer.left;
                int y = outer.top;
                int w = getWidth() - outer.left - outer.right - 1;
                int h = getHeight() - outer.top - outer.bottom - 1;
                g2.setColor(getBackground());
                g2.fillRoundRect(x, y, w, h, BUBBLE_ARC, BUBBLE_ARC);
                g2.setColor(ThemeColors.blend(
                        ThemeColors.accent(), ThemeColors.separator(), 0.22f));
                g2.drawRoundRect(x, y, w, h, BUBBLE_ARC, BUBBLE_ARC);
            } finally {
                g2.dispose();
            }
        }
        super.paintComponent(g);
    }
}
