package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;

import java.awt.Font;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TranscriptViewStopTest {

    @Test
    void abandonedStreamKeepsItsTextAndTheNextReplyGetsANewCard() {
        TranscriptView view = new TranscriptView(new Font(Font.DIALOG, Font.PLAIN, 13));
        view.appendStreamToken("Partial ");
        view.appendStreamToken("answer");

        assertEquals("Partial answer", view.abandonStream());

        view.appendStreamToken("Next reply");
        view.completeStream("Next reply");

        assertEquals("Partial answer", view.getCard(0).getText());
        assertEquals("Next reply", view.getCard(1).getText());
    }

    @Test
    void abandonWithoutAStreamReturnsEmptyText() {
        TranscriptView view = new TranscriptView(new Font(Font.DIALOG, Font.PLAIN, 13));
        assertEquals("", view.abandonStream());
    }
}
