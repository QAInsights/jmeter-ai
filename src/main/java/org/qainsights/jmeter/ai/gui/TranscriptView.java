package org.qainsights.jmeter.ai.gui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.Timer;
import org.qainsights.jmeter.ai.gui.theme.ThemeColors;

/**
 * The chat transcript as a vertical list of message cards, replacing the
 * single-JTextPane document model. Each message is a {@link MessageCard}
 * (bubble for the user, flat full-width for the assistant), agent tool calls
 * collect inside a collapsible {@link ToolActivityGroup}, and system lines
 * (errors, cancellations) render as small colored notes.
 * <p>
 * Streaming works per-card: tokens append raw text to the current assistant
 * card, and {@link #completeStream(String)} re-renders that card with full
 * markdown - no fragile document-offset bookkeeping. All methods must be
 * called on the EDT (callers already route through runOnEdt/invokeLater).
 */
class TranscriptView extends JPanel implements javax.swing.Scrollable {

    private final MessageProcessor messageProcessor = new MessageProcessor();
    private final List<MessageCard> cards = new ArrayList<>();
    private final Component glue = Box.createVerticalGlue();

    private Font baseFont;
    private MessageCard streamingCard;
    private ToolActivityGroup activityGroup;
    private ThinkingRow thinkingRow;
    private ThinkingCard thinkingCard;

    TranscriptView(Font baseFont) {
        this.baseFont = baseFont;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        add(glue);
    }

    // --- Messages -----------------------------------------------------------

    /** Adds a user message bubble (plain text, no markdown parsing). */
    void addUserMessage(String text) {
        finishActivityIfRunning();
        MessageCard card = new MessageCard(
            MessageCard.Role.USER, baseFont, messageProcessor
        );
        card.setPlainContent(text);
        addCard(card);
    }

    /** Adds a complete assistant message (markdown-rendered). */
    void addAssistantMessage(String markdown) {
        finishActivityIfRunning();
        MessageCard card = new MessageCard(
            MessageCard.Role.ASSISTANT, baseFont, messageProcessor
        );
        card.setMarkdownContent(markdown);
        addCard(card);
    }

    /** Adds a small colored system note (errors, cancellations, status). */
    void addSystemMessage(String text, Color color) {
        JTextArea note = new JTextArea(text);
        note.setEditable(false);
        note.setOpaque(false);
        note.setLineWrap(true);
        note.setWrapStyleWord(true);
        note.setForeground(color != null ? color : ThemeColors.secondaryText());
        note.setBorder(BorderFactory.createEmptyBorder(2, 16, 2, 16));
        note.setAlignmentX(Component.LEFT_ALIGNMENT);
        insertBeforeGlue(note);
        relayout(note);
    }

    // --- Streaming ----------------------------------------------------------

    /** Starts a new assistant card that will receive streamed tokens. */
    void beginAssistantStream() {
        finishActivityIfRunning();
        streamingCard = new MessageCard(
            MessageCard.Role.ASSISTANT, baseFont, messageProcessor
        );
        addCard(streamingCard);
    }

    /** Appends a raw token to the streaming card (starts one if needed). */
    void appendStreamToken(String token) {
        finishReasoningIfRunning();
        if (streamingCard == null) {
            beginAssistantStream();
        }
        streamingCard.appendRawText(token);
        relayout(streamingCard);
    }

    /** Re-renders the streaming card with fully processed markdown. */
    void completeStream(String fullMarkdown) {
        if (streamingCard != null) {
            streamingCard.setMarkdownContent(fullMarkdown);
            relayout(streamingCard);
            streamingCard = null;
        } else if (fullMarkdown != null && !fullMarkdown.isEmpty()) {
            addAssistantMessage(fullMarkdown);
        }
    }

    // --- Agent tool activity -------------------------------------------------

    /** Routes a tool-activity line into the current (or a new) group. */
    void addToolActivity(String line) {
        if (activityGroup == null || !activityGroup.isRunning()) {
            activityGroup = new ToolActivityGroup();
            activityGroup.setAlignmentX(Component.LEFT_ALIGNMENT);
            insertBeforeGlue(activityGroup);
        }
        activityGroup.addLine(line);
        relayout(activityGroup);
    }

    private void finishActivityIfRunning() {
        if (activityGroup != null && activityGroup.isRunning()) {
            activityGroup.finish();
        }
    }

    // --- Reasoning (thinking) card -------------------------------------------

    /** Appends a streamed reasoning token to the current (or a new) thinking card. */
    void appendReasoningToken(String token) {
        if (thinkingCard == null || !thinkingCard.isRunning()) {
            finishReasoningIfRunning();
            thinkingCard = new ThinkingCard();
            thinkingCard.setAlignmentX(Component.LEFT_ALIGNMENT);
            insertBeforeGlue(thinkingCard);
        }
        thinkingCard.appendText(token);
        relayout(thinkingCard);
    }

    /** Finishes the current thinking card (auto-collapses it). No-op when none. */
    void finishReasoning() {
        finishReasoningIfRunning();
    }

    /** Adds an already-collapsed thinking card (non-streaming responses). */
    void addReasoningBlock(String reasoning) {
        if (reasoning == null || reasoning.isBlank()) {
            return;
        }
        finishReasoningIfRunning();
        ThinkingCard card = new ThinkingCard();
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        insertBeforeGlue(card);
        card.appendText(reasoning);
        card.finish();
        thinkingCard = card;
        relayout(card);
    }

    private void finishReasoningIfRunning() {
        if (thinkingCard != null && thinkingCard.isRunning()) {
            thinkingCard.finish();
        }
    }

    // --- Thinking indicator ---------------------------------------------------

    /** Shows the animated "thinking" row at the bottom of the transcript. */
    void showThinking() {
        if (thinkingRow != null) {
            return;
        }
        thinkingRow = new ThinkingRow();
        insertBeforeGlue(thinkingRow);
        relayout(thinkingRow);
    }

    /** Removes the thinking row (no-op when not showing). */
    void hideThinking() {
        if (thinkingRow != null) {
            thinkingRow.dispose();
            remove(thinkingRow);
            thinkingRow = null;
            revalidate();
            repaint();
        }
    }

    // --- Lifecycle -------------------------------------------------------------

    /** Clears the whole transcript (used by "new conversation"). */
    void clearTranscript() {
        hideThinking();
        if (activityGroup != null) {
            activityGroup.dispose();
            activityGroup = null;
        }
        if (thinkingCard != null) {
            thinkingCard.dispose();
            thinkingCard = null;
        }
        streamingCard = null;
        cards.clear();
        removeAll();
        add(glue);
        revalidate();
        repaint();
    }

    /** Propagates a new base font to all message cards (zoom support). */
    void applyFont(Font font) {
        this.baseFont = font;
        for (MessageCard card : cards) {
            card.applyFont(font);
        }
    }

    /** Re-applies theme-derived colors on look-and-feel changes. */
    void refreshTheme() {
        for (MessageCard card : cards) {
            card.applyTheme();
        }
    }

    /**
     * Track the viewport width so cards wrap text instead of overflowing -
     * without this the scroll pane shows a horizontal scrollbar whenever a
     * message is wider than the visible area.
     */
    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    /** Height stays content-driven so the vertical scrollbar appears. */
    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(
        java.awt.Rectangle visibleRect,
        int orientation,
        int direction
    ) {
        return 16;
    }

    @Override
    public int getScrollableBlockIncrement(
        java.awt.Rectangle visibleRect,
        int orientation,
        int direction
    ) {
        return Math.max(visibleRect.height - 16, 16);
    }

    /** Number of message cards currently shown (for tests). */
    int getCardCount() {
        return cards.size();
    }

    /** The card at the given index (for tests). */
    MessageCard getCard(int index) {
        return cards.get(index);
    }

    /** The current thinking card, or null when no reasoning was shown (for tests). */
    ThinkingCard getThinkingCard() {
        return thinkingCard;
    }

    // --- Internals ---------------------------------------------------------------

    private void addCard(MessageCard card) {
        cards.add(card);
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        insertBeforeGlue(card);
        relayout(card);
    }

    private void insertBeforeGlue(Component c) {
        remove(glue);
        add(c);
        add(glue);
        revalidate();
        repaint();
    }

    /**
     * BoxLayout only stretches a component up to its maximum size; re-pin the
     * maximum to the current preferred size so cards fill the width but keep
     * their natural (content-driven) height.
     */
    private static void relayout(Component c) {
        if (c instanceof javax.swing.JComponent) {
            javax.swing.JComponent jc = (javax.swing.JComponent) c;
            jc.setMaximumSize(
                new Dimension(Integer.MAX_VALUE, jc.getPreferredSize().height)
            );
        }
        c.revalidate();
    }

    /** Small animated "thinking" row shown while the AI works. */
    private static final class ThinkingRow extends JPanel {
        private final JLabel label;
        private final Timer timer;
        private int dots = 0;

        ThinkingRow() {
            super(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
            setOpaque(false);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            label = new JLabel("Feather Wand is thinking");
            label.setFont(label.getFont().deriveFont(Font.ITALIC));
            label.setForeground(ThemeColors.secondaryText());
            label.setBorder(BorderFactory.createEmptyBorder(2, 16, 2, 16));
            add(label);
            timer = new Timer(400, e -> {
                dots = (dots + 1) % 4;
                label.setText(
                    "Feather Wand is thinking" + ".".repeat(dots)
                );
            });
            timer.start();
        }

        void dispose() {
            timer.stop();
        }
    }
}
