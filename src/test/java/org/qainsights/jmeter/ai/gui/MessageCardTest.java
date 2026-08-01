package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageCardTest {

    private MessageCard card(MessageCard.Role role) {
        return new MessageCard(role, null, new MessageProcessor());
    }

    @Test
    void userCardHasBubbleDisplayNameAndRoundedPaint() {
        MessageCard card = card(MessageCard.Role.USER);
        // Non-opaque: the rounded bubble is custom-painted in paintComponent
        assertFalse(card.isOpaque());
        assertEquals(MessageCard.Role.USER, card.getRole());
        assertEquals("You", MessageCard.Role.USER.displayName());
    }

    @Test
    void userBubblePaintsRoundedBackground() {
        MessageCard card = card(MessageCard.Role.USER);
        card.setPlainContent("hello");
        card.setSize(300, 80);

        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
            300, 80, java.awt.image.BufferedImage.TYPE_INT_ARGB
        );
        java.awt.Graphics2D g2 = img.createGraphics();
        card.paint(g2);
        g2.dispose();

        int bubble = org.qainsights.jmeter.ai.gui.theme.ThemeColors
            .userBubbleBackground().getRGB();
        // Center of the card is filled with the bubble color
        assertEquals(bubble, img.getRGB(150, 40));
        // The outer corner (inside the spacing margin) stays transparent
        assertEquals(0, img.getRGB(1, 1) >>> 24);
    }

    @Test
    void assistantCardIsFlatWithDisplayName() {
        MessageCard card = card(MessageCard.Role.ASSISTANT);
        assertFalse(card.isOpaque());
        assertEquals("Feather Wand", MessageCard.Role.ASSISTANT.displayName());
    }

    @Test
    void plainContentKeepsTextVerbatim() {
        MessageCard card = card(MessageCard.Role.USER);
        card.setPlainContent("add a thread group with *stars*");
        assertEquals("add a thread group with *stars*", card.getText());
    }

    @Test
    void markdownContentIsParsedAndSourceRetained() {
        MessageCard card = card(MessageCard.Role.ASSISTANT);
        card.setMarkdownContent("# Title\n- item one");

        // Raw markdown source is retained for Copy
        assertEquals("# Title\n- item one", card.getText());

        // Rendered body drops the markdown markers
        String rendered = bodyText(card);
        assertTrue(rendered.contains("Title"));
        assertTrue(rendered.contains("• item one"));
        assertFalse(rendered.contains("# Title"));
    }

    @Test
    void streamedTokensThenMarkdownReRender() {
        MessageCard card = card(MessageCard.Role.ASSISTANT);
        card.appendRawText("**bol");
        card.appendRawText("d** text");
        assertEquals("**bold** text", card.getText());
        assertTrue(bodyText(card).contains("**bold** text"));

        card.setMarkdownContent("**bold** text");
        assertTrue(bodyText(card).contains("bold text"));
        assertFalse(bodyText(card).contains("**"));
    }

    @Test
    void applyFontDoesNotThrowOnNull() {
        MessageCard card = card(MessageCard.Role.ASSISTANT);
        card.applyFont(null); // must be tolerated
        card.applyFont(new java.awt.Font(java.awt.Font.DIALOG, java.awt.Font.PLAIN, 14));
    }

    private static String bodyText(MessageCard card) {
        try {
            javax.swing.JTextPane body = findBody(card);
            return body.getDocument().getText(0, body.getDocument().getLength());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static javax.swing.JTextPane findBody(MessageCard card) {
        for (java.awt.Component c : card.getComponents()) {
            if (c instanceof javax.swing.JTextPane) {
                return (javax.swing.JTextPane) c;
            }
        }
        throw new AssertionError("no body JTextPane found");
    }
}
