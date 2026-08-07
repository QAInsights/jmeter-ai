package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;

import javax.swing.JButton;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

import org.qainsights.jmeter.ai.service.attach.Attachment;
import org.qainsights.jmeter.ai.service.attach.FileContentPreparer;

/**
 * Tests for the user-card "Save prompt" action: button visibility per role and
 * handler state, and marker-to-file-name substitution in the saved body.
 */
class MessageCardSavePromptTest {

    private MessageCard card(MessageCard.Role role, String text) {
        MessageCard card = new MessageCard(role, null, new MessageProcessor());
        card.setPlainContent(text);
        return card;
    }

    private JButton findButton(java.awt.Container root, String label) {
        for (java.awt.Component c : root.getComponents()) {
            if (c instanceof JButton button && label.equals(button.getText())) {
                return button;
            }
            if (c instanceof java.awt.Container child) {
                JButton found = findButton(child, label);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    void savePromptButtonOnlyOnUserCards() {
        assertNotNull(findButton(card(MessageCard.Role.USER, "hi"), "Save prompt"));
        assertNull(findButton(card(MessageCard.Role.ASSISTANT, "hi"), "Save prompt"));
    }

    @Test
    void buttonHiddenUntilHandlerSet() {
        MessageCard card = card(MessageCard.Role.USER, "hi");
        JButton button = findButton(card, "Save prompt");
        assertFalse(button.isVisible());

        card.setSavePromptHandler(body -> { });
        assertTrue(button.isVisible());

        card.setSavePromptHandler(null);
        assertFalse(button.isVisible());
    }

    @Test
    void clickHandsTextToHandler() {
        MessageCard card = card(MessageCard.Role.USER, "remember this flow");
        AtomicReference<String> received = new AtomicReference<>();
        card.setSavePromptHandler(received::set);

        findButton(card, "Save prompt").doClick();
        assertEquals("remember this flow", received.get());
    }

    @Test
    void textForPromptSubstitutesKnownMarkersWithFileNames() {
        MessageCard card = card(MessageCard.Role.USER, "analyze [file:ab12] please");
        card.setAttachmentLookup(id -> new Attachment("ab12", "results.jtl",
                "timeStamp,elapsed\n1,2", FileContentPreparer.Mode.SMART));
        assertEquals("analyze [results.jtl] please", card.textForPrompt());
    }

    @Test
    void textForPromptStripsUnknownMarkersAndCollapsesWhitespace() {
        MessageCard card = card(MessageCard.Role.USER, "analyze [file:gone] please");
        assertEquals("analyze please", card.textForPrompt());
    }

    @Test
    void textForPromptWithoutLookupStripsMarkers() {
        MessageCard card = card(MessageCard.Role.USER, "analyze [file:ab12] please");
        assertEquals("analyze please", card.textForPrompt());
    }
}
