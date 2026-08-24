package org.qainsights.jmeter.ai.record;

import javax.swing.JLabel;
import javax.swing.JToggleButton;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RecordingControlPanel}.
 */
class RecordingControlPanelTest {

    @Test
    void should_updateStatusLabel_when_controllerStateChanges() throws Exception {
        RecordingSessionController controller = new RecordingSessionController();
        RecordingArtifactStore store = mock(RecordingArtifactStore.class);

        RecordingControlPanel panel = new RecordingControlPanel(controller, store);
        JLabel statusLabel = null;
        JToggleButton recordButton = null;
        for (java.awt.Component comp : panel.getComponents()) {
            if (comp instanceof JLabel) {
                statusLabel = (JLabel) comp;
            } else if (comp instanceof JToggleButton) {
                recordButton = (JToggleButton) comp;
            }
        }

        assertNotNull(recordButton);
        assertEquals(org.qainsights.jmeter.ai.gui.theme.UiTokens.HEADER_TEXT_BUTTON_WIDTH,
                recordButton.getPreferredSize().width);
        assertEquals(org.qainsights.jmeter.ai.gui.theme.UiTokens.HEADER_CONTROL_HEIGHT,
                recordButton.getPreferredSize().height);
        assertNotNull(statusLabel);
        assertEquals("🔴 OFF", statusLabel.getText());

        // Start session to change state to ARMED
        SessionConfig config = new SessionConfig("Intent", "https://example.com", "chromium");
        controller.startSession(config, "testDir");

        // Wait brief moment for SwingUtilities.invokeLater
        Thread.sleep(100);
        assertEquals("🟢 ARMED", statusLabel.getText());

        // Stop session to transition back to OFF
        controller.transitionTo(RecordingSessionState.OFF);
        Thread.sleep(100);
        assertEquals("🔴 OFF", statusLabel.getText());
    }
}
