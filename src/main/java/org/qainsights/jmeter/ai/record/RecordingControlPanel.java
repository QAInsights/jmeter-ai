package org.qainsights.jmeter.ai.record;

import java.awt.*;
import java.io.File;
import javax.swing.*;
import org.apache.jmeter.gui.GuiPackage;
import org.qainsights.jmeter.ai.utils.AiConfig;

/**
 * UI control panel containing the toggle button and status light for Record Mode.
 */
public final class RecordingControlPanel extends JPanel {

    private final RecordingSessionController controller;
    private final RecordingArtifactStore artifactStore;
    private final JToggleButton toggleBtn = new JToggleButton("Record");
    private final JLabel statusLabel = new JLabel("🔴 OFF");

    public RecordingControlPanel(RecordingSessionController controller, RecordingArtifactStore artifactStore) {
        this.controller = controller;
        this.artifactStore = artifactStore;
        
        setLayout(new FlowLayout(FlowLayout.LEFT, 5, 0));
        setOpaque(false);

        setupToggleBtn();
        add(toggleBtn);
        add(Box.createHorizontalStrut(5));
        add(statusLabel);

        controller.addListener(this::updateUiFromState);
    }

    private void setupToggleBtn() {
        toggleBtn.setMargin(new Insets(2, 6, 2, 6));
        toggleBtn.addActionListener(e -> {
            if (toggleBtn.isSelected()) {
                handleStartSession();
            } else {
                if (controller.getSnapshot().state() == RecordingSessionState.ARMED) {
                    controller.transitionTo(RecordingSessionState.OFF);
                } else {
                    controller.transitionTo(RecordingSessionState.CANCELLED);
                }
            }
        });
    }

    private void handleStartSession() {
        Frame mainFrame = GuiPackage.getInstance() != null ? GuiPackage.getInstance().getMainFrame() : null;
        RecordingConfigDialog dialog = new RecordingConfigDialog(mainFrame);
        dialog.setVisible(true);

        if (dialog.isConfirmed() && dialog.getResultConfig() != null) {
            try {
                String sessionId = java.util.UUID.randomUUID().toString();
                java.nio.file.Path sessionDir = artifactStore.getSessionDirectory(sessionId);
                controller.startSession(dialog.getResultConfig(), sessionDir.toAbsolutePath().toString());
            } catch (Exception ex) {
                toggleBtn.setSelected(false);
                JOptionPane.showMessageDialog(this, "Failed to create session directory: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        } else {
            toggleBtn.setSelected(false);
        }
    }

    private void updateUiFromState(RecordingSessionSnapshot snapshot) {
        SwingUtilities.invokeLater(() -> {
            boolean active = snapshot.state() != RecordingSessionState.OFF;
            toggleBtn.setSelected(active);
            
            if (snapshot.state() == RecordingSessionState.ARMED) {
                statusLabel.setText("🟢 ARMED");
                statusLabel.setForeground(new Color(0, 150, 0));
            } else if (active) {
                statusLabel.setText("🟡 " + snapshot.state().name());
                statusLabel.setForeground(Color.BLUE);
            } else {
                statusLabel.setText("🔴 OFF");
                statusLabel.setForeground(Color.DARK_GRAY);
            }
        });
    }
}
