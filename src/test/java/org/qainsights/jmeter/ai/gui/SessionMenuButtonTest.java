package org.qainsights.jmeter.ai.gui;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.qainsights.jmeter.ai.service.session.ConversationSession;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SessionMenuButton}: popup contents. The file-save
 * dialog flow is manual-smoke territory; export rendering itself is covered
 * by {@code ConversationExporterTest}.
 */
class SessionMenuButtonTest {

    @Test
    void popupOffersBothExportFormats() {
        ConversationSession session = ConversationSession.fromHistory(
                "s1", 0L, "", List.of("hello"), List.of(1L), List.of());
        SessionMenuButton button = new SessionMenuButton(null, () -> session);

        assertEquals(2, button.menuItemCount());
        assertEquals("", button.getText());
        assertNotNull(button.getIcon());
        assertTrue(button.getToolTipText().contains("export"));
        assertEquals(org.qainsights.jmeter.ai.gui.theme.UiTokens.HEADER_CONTROL_HEIGHT,
                button.getPreferredSize().width);
        assertEquals(org.qainsights.jmeter.ai.gui.theme.UiTokens.HEADER_CONTROL_HEIGHT,
                button.getPreferredSize().height);
    }
}
