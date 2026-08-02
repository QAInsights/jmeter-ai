package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;

import java.awt.Font;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the reasoning (thinking) support in {@link TranscriptView}:
 * streamed reasoning tokens, auto-collapse when the answer starts, the
 * non-streaming reasoning block, and transcript clearing.
 */
class TranscriptViewReasoningTest {

    private TranscriptView view() {
        return new TranscriptView(new Font(Font.DIALOG, Font.PLAIN, 13));
    }

    @Test
    void reasoningTokensCreateCardAndAccumulate() {
        TranscriptView view = view();
        try {
            view.appendReasoningToken("Thinking");
            view.appendReasoningToken(" harder…");

            ThinkingCard card = view.getThinkingCard();
            assertNotNull(card);
            assertTrue(card.isRunning());
            assertEquals("Thinking harder…", card.getText());
        } finally {
            dispose(view);
        }
    }

    @Test
    void firstAnswerTokenAutoCollapsesThinkingCard() {
        TranscriptView view = view();
        try {
            view.appendReasoningToken("reasoning");
            view.appendStreamToken("answer");

            ThinkingCard card = view.getThinkingCard();
            assertNotNull(card);
            assertFalse(card.isRunning());
            assertTrue(card.isCollapsed());
        } finally {
            dispose(view);
        }
    }

    @Test
    void finishReasoningCollapsesCard() {
        TranscriptView view = view();
        try {
            view.appendReasoningToken("reasoning");
            view.finishReasoning();
            assertTrue(view.getThinkingCard().isCollapsed());
            // finishing again is a no-op
            view.finishReasoning();
        } finally {
            dispose(view);
        }
    }

    @Test
    void addReasoningBlockAddsCollapsedCard() {
        TranscriptView view = view();
        try {
            view.addReasoningBlock("pre-computed thoughts");
            ThinkingCard card = view.getThinkingCard();
            assertNotNull(card);
            assertFalse(card.isRunning());
            assertTrue(card.isCollapsed());
            assertEquals("pre-computed thoughts", card.getText());
        } finally {
            dispose(view);
        }
    }

    @Test
    void addReasoningBlockIgnoresBlank() {
        TranscriptView view = view();
        view.addReasoningBlock(null);
        view.addReasoningBlock("   ");
        assertNull(view.getThinkingCard());
    }

    @Test
    void clearTranscriptDropsThinkingCard() {
        TranscriptView view = view();
        try {
            view.appendReasoningToken("reasoning");
            assertNotNull(view.getThinkingCard());
            view.clearTranscript();
            assertNull(view.getThinkingCard());
        } finally {
            dispose(view);
        }
    }

    private static void dispose(TranscriptView view) {
        ThinkingCard card = view.getThinkingCard();
        if (card != null) {
            card.dispose();
        }
    }
}
