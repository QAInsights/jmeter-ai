package org.qainsights.jmeter.ai.gui;

import org.qainsights.jmeter.ai.agent.JMeterAgent;
import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.gui.theme.UiTokens;
import org.qainsights.jmeter.ai.mcpserver.FeatherWandMcpServer;
import org.qainsights.jmeter.ai.mcpserver.McpClientSetup;
import org.qainsights.jmeter.ai.telemetry.Telemetry;
import org.qainsights.jmeter.ai.telemetry.TelemetryFeature;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.util.function.BiFunction;

/** Run menu entry that opens the MCP server panel: status, start/stop and client setup. */
public class McpServerMenuItem extends JMenuItem {

    public McpServerMenuItem() {
        super("Feather Wand MCP Server");
        addActionListener(e -> showDialog());
    }

    private McpServerDialog dialog;

    private void showDialog() {
        if (dialog == null || !dialog.isDisplayable()) {
            dialog = new McpServerDialog(SwingUtilities.getWindowAncestor(this));
        } else {
            dialog.refresh();
        }
        dialog.setVisible(true);
        dialog.toFront();
    }

    static final class McpServerDialog extends JDialog {

        private final JLabel status = new JLabel();
        private final JButton toggle = new QuietButton("Start server", QuietButton.Kind.PRIMARY);
        private final JTabbedPane clients = new JTabbedPane();

        McpServerDialog(Window owner) {
            super(owner, "Feather Wand MCP Server", ModalityType.MODELESS);

            JPanel root = new JPanel(new BorderLayout(UiTokens.SPACE_3, UiTokens.SPACE_3));
            root.setBackground(ThemeColors.surface());
            root.setBorder(BorderFactory.createEmptyBorder(
                    UiTokens.SPACE_4, UiTokens.SPACE_4, UiTokens.SPACE_4, UiTokens.SPACE_4));

            JPanel heading = new JPanel();
            heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
            heading.setOpaque(false);
            JLabel title = new JLabel("Feather Wand MCP Server");
            title.setFont(UiTokens.title(title.getFont()));
            JLabel subtitle = new JLabel("Let Claude Code, Cursor or Codex read, edit and run the plan open in JMeter.");
            subtitle.setFont(UiTokens.caption(subtitle.getFont()));
            subtitle.setForeground(ThemeColors.secondaryText());
            status.setFont(UiTokens.label(status.getFont()));
            for (JLabel label : new JLabel[] {title, subtitle, status}) {
                label.setAlignmentX(Component.LEFT_ALIGNMENT);
            }
            heading.add(title);
            heading.add(Box.createVerticalStrut(UiTokens.SPACE_1));
            heading.add(subtitle);
            heading.add(Box.createVerticalStrut(UiTokens.SPACE_3));
            heading.add(status);
            root.add(heading, BorderLayout.NORTH);

            root.add(clients, BorderLayout.CENTER);

            JLabel note = new JLabel("<html>Only this computer can connect, and every request needs the token. "
                    + (FeatherWandMcpServer.confirmsDestructive()
                            ? "Delete, move, open and correlation edits still ask you in JMeter first."
                            : "Delete, move, open and correlation edits run without asking, because "
                                    + JMeterAgent.CONFIRM_DESTRUCTIVE_KEY + "=false.")
                    + "</html>");
            note.setFont(UiTokens.caption(note.getFont()));
            note.setForeground(ThemeColors.secondaryText());

            JButton newToken = new QuietButton("New token");
            newToken.setToolTipText("Replace the token. Clients set up with the old one stop working.");
            newToken.addActionListener(e -> onNewToken());
            JButton close = new QuietButton("Close");
            close.addActionListener(e -> dispose());
            toggle.addActionListener(e -> onToggle());

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiTokens.SPACE_1, 0));
            buttons.setOpaque(false);
            buttons.add(newToken);
            buttons.add(close);
            buttons.add(toggle);

            JPanel south = new JPanel(new BorderLayout(0, UiTokens.SPACE_3));
            south.setOpaque(false);
            south.add(note, BorderLayout.NORTH);
            south.add(buttons, BorderLayout.SOUTH);
            root.add(south, BorderLayout.SOUTH);

            setContentPane(root);
            refresh();
            setMinimumSize(new Dimension(620, 420));
            pack();
            setLocationRelativeTo(owner);
        }

        private void onToggle() {
            if (FeatherWandMcpServer.isRunning()) {
                try {
                    FeatherWandMcpServer.stop();
                } catch (RuntimeException e) {
                    JOptionPane.showMessageDialog(this, "The MCP server could not stop cleanly.\n" + e.getMessage(),
                            "Feather Wand MCP Server", JOptionPane.ERROR_MESSAGE);
                }
            } else {
                try {
                    FeatherWandMcpServer.start();
                } catch (IOException | RuntimeException e) {
                    JOptionPane.showMessageDialog(this, "The MCP server could not start on "
                            + FeatherWandMcpServer.url() + ".\n" + e.getMessage()
                            + "\n\nIf another program uses the port, set " + FeatherWandMcpServer.PORT_KEY
                            + " in user.properties and click Start server again.",
                            "Feather Wand MCP Server", JOptionPane.ERROR_MESSAGE);
                }
            }
            refresh();
        }

        private void onNewToken() {
            int answer = JOptionPane.showConfirmDialog(this,
                    "Replace the token? Clients set up with the current one will need the new setup.",
                    "Feather Wand MCP Server", JOptionPane.OK_CANCEL_OPTION);
            if (answer != JOptionPane.OK_OPTION) {
                return;
            }
            try {
                FeatherWandMcpServer.regenerateToken();
            } catch (IOException | RuntimeException e) {
                JOptionPane.showMessageDialog(this, "Could not write a new token: " + e.getMessage(),
                        "Feather Wand MCP Server", JOptionPane.ERROR_MESSAGE);
            }
            refresh();
        }

        void refresh() {
            boolean running = FeatherWandMcpServer.isRunning();
            String url = FeatherWandMcpServer.url();
            status.setText(running ? "\u25CF  Running at " + url : "\u25CF  Stopped");
            status.setForeground(running ? ThemeColors.success() : ThemeColors.secondaryText());
            toggle.setText(running ? "Stop server" : "Start server");

            String token;
            String tokenError = null;
            try {
                token = FeatherWandMcpServer.token();
            } catch (IOException e) {
                token = null;
                tokenError = "The token in ~/.jmeter-ai/mcp-token could not be read or written: " + e.getMessage();
            }
            int selected = Math.max(0, clients.getSelectedIndex());
            clients.removeAll();
            addClient("Claude Code", "Run in a terminal:", McpClientSetup::claudeCode, url, token, tokenError);
            addClient("Cursor", "Add to ~/.cursor/mcp.json:", McpClientSetup::cursor, url, token, tokenError);
            addClient("Codex", "Add to ~/.codex/config.toml:", McpClientSetup::codex, url, token, tokenError);
            clients.setSelectedIndex(Math.min(selected, clients.getTabCount() - 1));
        }

        private void addClient(String name, String hint, BiFunction<String, String, String> setup,
                String url, String token, String tokenError) {
            String full = token == null ? null : setup.apply(url, token);
            String masked = token == null ? tokenError : setup.apply(url, mask(token));

            JPanel panel = new JPanel(new BorderLayout(0, UiTokens.SPACE_2));
            panel.setOpaque(false);
            panel.setBorder(BorderFactory.createEmptyBorder(
                    UiTokens.SPACE_3, UiTokens.SPACE_3, UiTokens.SPACE_3, UiTokens.SPACE_3));
            JLabel hintLabel = new JLabel(hint);
            hintLabel.setFont(UiTokens.caption(hintLabel.getFont()));
            hintLabel.setForeground(ThemeColors.secondaryText());
            panel.add(hintLabel, BorderLayout.NORTH);

            JTextArea code = new JTextArea(masked);
            code.setEditable(false);
            code.setLineWrap(true);
            code.setFont(new Font(Font.MONOSPACED, Font.PLAIN, code.getFont().getSize()));
            code.setBackground(ThemeColors.codeBackground());
            code.setBorder(BorderFactory.createEmptyBorder(
                    UiTokens.SPACE_2, UiTokens.SPACE_2, UiTokens.SPACE_2, UiTokens.SPACE_2));
            JScrollPane scroll = new JScrollPane(code);
            scroll.setBorder(BorderFactory.createLineBorder(ThemeColors.separator(), 1, true));
            panel.add(scroll, BorderLayout.CENTER);

            JButton copy = new QuietButton("Copy setup", QuietButton.Kind.OUTLINED);
            copy.setToolTipText("Copies the setup with the full token");
            copy.setEnabled(full != null);
            copy.addActionListener(e -> {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(full), null);
                Telemetry.record(TelemetryFeature.MCP_SETUP_COPY);
                copy.setText("Copied");
            });
            JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            row.setOpaque(false);
            row.add(copy);
            panel.add(row, BorderLayout.SOUTH);
            clients.addTab(name, panel);
        }

        static String mask(String token) {
            return token.length() <= 6 ? "******" : token.substring(0, 6) + "******";
        }
    }
}
