package org.qainsights.jmeter.ai.record;

import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JLabel;
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
        for (java.awt.Component comp : panel.getComponents()) {
            if (comp instanceof JLabel) {
                statusLabel = (JLabel) comp;
            }
        }

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
