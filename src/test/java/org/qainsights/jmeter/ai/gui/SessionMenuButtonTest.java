package org.qainsights.jmeter.ai.gui;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
        SessionMenuButton button = new SessionMenuButton(null, () -> session, () -> { });

        assertEquals(4, button.menuItemCount());
        assertEquals("Connect a provider…", button.menuItemLabel(0));
        assertEquals("", button.getText());
        assertNotNull(button.getIcon());
        assertEquals("More actions", button.getToolTipText());
        assertEquals("More actions", button.getAccessibleContext().getAccessibleName());
        assertEquals(org.qainsights.jmeter.ai.gui.theme.UiTokens.HEADER_CONTROL_HEIGHT,
                button.getPreferredSize().width);
        assertEquals(org.qainsights.jmeter.ai.gui.theme.UiTokens.HEADER_CONTROL_HEIGHT,
                button.getPreferredSize().height);
    }

    @Test
    void firstMenuItemInvokesProviderSetupAction() {
        AtomicInteger invoked = new AtomicInteger();
        SessionMenuButton button = new SessionMenuButton(null, () -> null, invoked::incrementAndGet);

        button.activateMenuItem(0);

        assertEquals(1, invoked.get());
    }
}
