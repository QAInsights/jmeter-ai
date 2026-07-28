package org.qainsights.jmeter.ai.record;

import java.awt.*;
import java.net.URI;
import javax.swing.*;
import org.apache.jmeter.gui.GuiPackage;

/**
 * Modal dialog to configure a new recording session.
 */
public final class RecordingConfigDialog extends JDialog {

    private final JTextArea promptArea = new JTextArea(4, 30);
    private final JTextField baseUriField = new JTextField(30);
    private final JComboBox<String> browserCombo = new JComboBox<>(new String[]{"chromium", "firefox"});
    private final JCheckBox discardChangesCheck = new JCheckBox("Unsaved changes detected. Discard and replace current test plan?", true);
    
    private SessionConfig resultConfig;
    private boolean confirmed = false;

    public RecordingConfigDialog(Frame owner) {
        super(owner, "Configure Feather Wand Record Mode", true);
        initComponents();
    }

    private void initComponents() {
        setLayout(new BorderLayout(10, 10));
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);

        setupFields(panel, gbc);

        add(panel, BorderLayout.CENTER);
        add(createButtonsPanel(), BorderLayout.SOUTH);
        pack();
        setLocationRelativeTo(getParent());
    }

    private void setupFields(JPanel panel, GridBagConstraints gbc) {
        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("User Intent / Prompt:"), gbc);
        gbc.gridx = 1;
        promptArea.setLineWrap(true);
        promptArea.setWrapStyleWord(true);
        panel.add(new JScrollPane(promptArea), gbc);

        gbc.gridx = 0; gbc.gridy = 1;
        panel.add(new JLabel("Base URI:"), gbc);
        gbc.gridx = 1;
        baseUriField.setText("https://");
        panel.add(baseUriField, gbc);

        gbc.gridx = 0; gbc.gridy = 2;
        panel.add(new JLabel("Browser:"), gbc);
        gbc.gridx = 1;
        panel.add(browserCombo, gbc);

        if (GuiPackage.getInstance() != null && GuiPackage.getInstance().isDirty()) {
            gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2;
            panel.add(discardChangesCheck, gbc);
        }
    }

    private JPanel createButtonsPanel() {
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 10));
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JButton start = new JButton("Start Session");
        start.addActionListener(e -> validateAndConfirm());
        btnPanel.add(cancel);
        btnPanel.add(start);
        return btnPanel;
    }

    private void validateAndConfirm() {
        String prompt = promptArea.getText().trim();
        String baseUri = baseUriField.getText().trim();
        if (prompt.isEmpty() || baseUri.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Prompt and Base URI are required.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {
            URI uri = new URI(baseUri);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new Exception("Invalid host or scheme");
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Base URI must be a valid absolute HTTP/HTTPS URL.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (GuiPackage.getInstance() != null && GuiPackage.getInstance().isDirty() && !discardChangesCheck.isSelected()) {
            JOptionPane.showMessageDialog(this, "The current test plan has unsaved changes. Please save them or check the box to discard and continue.", "Unsaved Changes", JOptionPane.WARNING_MESSAGE);
            return;
        }

        resultConfig = new SessionConfig(prompt, baseUri, (String) browserCombo.getSelectedItem());
        confirmed = true;
        dispose();
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public SessionConfig getResultConfig() {
        return resultConfig;
    }
}
