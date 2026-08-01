package org.qainsights.jmeter.ai.gui;

import java.awt.Font;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscriptViewTest {

    private TranscriptView view() {
        return new TranscriptView(
            new Font(Font.DIALOG, Font.PLAIN, 13)
        );
    }

    @Test
    void userAndAssistantMessagesBecomeCards() {
        TranscriptView view = view();
        view.addUserMessage("hello");
        view.addAssistantMessage("# hi there");

        assertEquals(2, view.getCardCount());
        assertEquals(MessageCard.Role.USER, view.getCard(0).getRole());
        assertEquals(MessageCard.Role.ASSISTANT, view.getCard(1).getRole());
        assertEquals("hello", view.getCard(0).getText());
        assertEquals("# hi there", view.getCard(1).getText());
    }

    @Test
    void streamingFlowAppendsThenReRendersInOneCard() {
        TranscriptView view = view();
        view.beginAssistantStream();
        view.appendStreamToken("Hello ");
        view.appendStreamToken("world");
        view.completeStream("Hello **world**");

        assertEquals(1, view.getCardCount());
        assertEquals("Hello **world**", view.getCard(0).getText());
    }

    @Test
    void appendTokenWithoutBeginStartsCardImplicitly() {
        TranscriptView view = view();
        view.appendStreamToken("token");
        assertEquals(1, view.getCardCount());
        assertEquals("token", view.getCard(0).getText());
    }

    @Test
    void completeStreamWithoutCardFallsBackToFullMessage() {
        TranscriptView view = view();
        view.completeStream("full answer");
        assertEquals(1, view.getCardCount());
        assertEquals("full answer", view.getCard(0).getText());
    }

    @Test
    void toolActivityGroupsAndFinishesOnNextMessage() {
        TranscriptView view = view();
        view.addToolActivity("tool one");
        view.addToolActivity("tool two");
        // Next assistant turn must close the running group
        view.addAssistantMessage("answer");
        assertEquals(1, view.getCardCount());

        // A second run opens a fresh group
        view.addToolActivity("tool three");
        view.addUserMessage("next");
        assertEquals(2, view.getCardCount());
    }

    @Test
    void thinkingRowShowsAndHides() {
        TranscriptView view = view();
        view.showThinking();
        view.showThinking(); // idempotent
        view.hideThinking();
        view.hideThinking(); // safe when not showing
        assertEquals(0, view.getCardCount());
    }

    @Test
    void clearRemovesEverything() {
        TranscriptView view = view();
        view.addUserMessage("hello");
        view.addAssistantMessage("hi");
        view.addToolActivity("tool");
        view.showThinking();

        view.clearTranscript();

        assertEquals(0, view.getCardCount());
        assertEquals(1, view.getComponentCount()); // only the glue remains
    }

    @Test
    void tracksViewportWidthSoCardsWrapInsteadOfOverflowing() {
        TranscriptView view = view();
        assertTrue(view.getScrollableTracksViewportWidth());
        org.junit.jupiter.api.Assertions.assertFalse(
            view.getScrollableTracksViewportHeight()
        );
        assertEquals(16, view.getScrollableUnitIncrement(
            new java.awt.Rectangle(0, 0, 300, 200),
            javax.swing.SwingConstants.VERTICAL, 1));
        assertTrue(view.getScrollableBlockIncrement(
            new java.awt.Rectangle(0, 0, 300, 200),
            javax.swing.SwingConstants.VERTICAL, 1) > 0);
    }

    @Test
    void systemMessagesAndThemeAndFontApisDoNotThrow() {
        TranscriptView view = view();
        assertDoesNotThrow(() -> {
            view.addSystemMessage("[Stream cancelled]", null);
            view.addSystemMessage("error!", java.awt.Color.RED);
            view.applyFont(new Font(Font.DIALOG, Font.PLAIN, 16));
            view.refreshTheme();
        });
    }
}
