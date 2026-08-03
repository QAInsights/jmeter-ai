package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.qainsights.jmeter.ai.service.attach.Attachment;
import org.qainsights.jmeter.ai.service.attach.FileContentPreparer;
import org.qainsights.jmeter.ai.utils.AiConfig;

import java.awt.Font;
import javax.swing.JLabel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for the attachment-chip rendering in {@link MessageCard}:
 * markers are stripped from the displayed text and rendered as chips
 * (rich label from the lookup, id fallback otherwise).
 */
class MessageCardAttachmentTest {

    private MockedStatic<AiConfig> aiConfigMockedStatic;

    @BeforeEach
    void setUp() {
        aiConfigMockedStatic = mockStatic(AiConfig.class);
        aiConfigMockedStatic.when(() -> AiConfig.getProperty(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    @AfterEach
    void tearDown() {
        aiConfigMockedStatic.close();
    }

    private MessageCard userCard() {
        return new MessageCard(MessageCard.Role.USER,
                new Font(Font.DIALOG, Font.PLAIN, 13), new MessageProcessor());
    }

    @Test
    void markersRenderAsChipsWithRichLabel() {
        MessageCard card = userCard();
        Attachment attachment = new Attachment("f1", "results.jtl", "x".repeat(1024),
                FileContentPreparer.Mode.SMART);
        card.setAttachmentLookup(id -> "f1".equals(id) ? attachment : null);

        card.setPlainContent("why did this fail? [file:f1]");

        assertEquals(1, card.getChipCount());
        // the full original text is preserved for Copy
        assertTrue(card.getText().contains("[file:f1]"));
    }

    @Test
    void unknownIdRendersFallbackChip() {
        MessageCard card = userCard();
        card.setPlainContent("check [file:f9] this");
        assertEquals(1, card.getChipCount());
    }

    @Test
    void noMarkersNoChips() {
        MessageCard card = userCard();
        card.setPlainContent("plain question");
        assertEquals(0, card.getChipCount());
    }

    @Test
    void multipleMarkersRenderMultipleChips() {
        MessageCard card = userCard();
        card.setPlainContent("compare [file:f1] and [file:f2] please");
        assertEquals(2, card.getChipCount());
    }

    @Test
    void htmlPrefixedFileNameRendersAsPlainText() {
        String payload = "<html><img src=https://tracker.example/x>";
        Attachment attachment = new Attachment("f1", payload, "x",
                FileContentPreparer.Mode.SMART);
        MessageCard card = userCard();
        card.setAttachmentLookup(id -> "f1".equals(id) ? attachment : null);

        card.setPlainContent("check [file:f1]");

        JLabel chip = findChipLabel(card);
        assertNotNull(chip, "a chip must be rendered");
        assertTrue(chip.getText().contains(payload), "the file name must appear literally");
        assertEquals(Boolean.TRUE, chip.getClientProperty("html.disable"));
    }

    private static JLabel findChipLabel(MessageCard card) {
        // walk the component tree; chip labels are the JLabels WITH an icon
        // (the header's sender label has none)
        for (java.awt.Component area : card.getComponents()) {
            if (area instanceof javax.swing.JPanel) {
                for (java.awt.Component inner : ((javax.swing.JPanel) area).getComponents()) {
                    if (inner instanceof javax.swing.JPanel) {
                        for (java.awt.Component chip : ((javax.swing.JPanel) inner).getComponents()) {
                            if (chip instanceof JLabel && ((JLabel) chip).getIcon() != null) {
                                return (JLabel) chip;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    @Test
    void resetContentClearsChips() {
        MessageCard card = userCard();
        card.setPlainContent("with [file:f1] attachment");
        assertEquals(1, card.getChipCount());
        card.setPlainContent("no attachments now");
        assertEquals(0, card.getChipCount());
    }
}
