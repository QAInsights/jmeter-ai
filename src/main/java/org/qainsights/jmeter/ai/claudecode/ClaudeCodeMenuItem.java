package org.qainsights.jmeter.ai.claudecode;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.MainFrame;
import org.apache.jmeter.gui.util.JMeterToolBar;
import org.qainsights.jmeter.ai.gui.ComponentFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;

/**
 * Menu item and toolbar button for launching Claude Code terminal panel.
 * <p>
 * Adds a terminal icon (">_") to the JMeter toolbar, next to the
 * Feather Wand button. Clicking toggles a split pane with the
 * {@link ClaudeCodePanel} on the right side.
 */
public class ClaudeCodeMenuItem extends JMenuItem implements ActionListener {
    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeMenuItem.class);

    private ClaudeCodePanel currentPanel;
    private JSplitPane splitPane;
    private final JComponent parent;

    public ClaudeCodeMenuItem(JComponent parent) {
        super("Claude Code");
        this.parent = parent;
        addActionListener(this);
        addToolbarIcon();
    }

    /**
     * Generates a terminal-style icon (">_") programmatically.
     *
     * @param size the pixel size of the icon (square)
     * @return the generated icon
     */
    public static ImageIcon getClaudeCodeIcon(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Background: rounded rectangle with dark color
        g2d.setColor(new Color(45, 45, 45));
        g2d.fillRoundRect(0, 0, size, size, size / 4, size / 4);

        // Border
        g2d.setColor(new Color(86, 182, 194));
        g2d.setStroke(new BasicStroke(Math.max(1, size / 12f)));
        g2d.drawRoundRect(1, 1, size - 2, size - 2, size / 4, size / 4);

        // Draw ">_" terminal prompt
        g2d.setColor(new Color(86, 182, 194));
        float fontSize = size * 0.45f;
        g2d.setFont(new Font(Font.MONOSPACED, Font.BOLD, (int) fontSize));

        FontMetrics fm = g2d.getFontMetrics();
        String text = ">_";
        int textWidth = fm.stringWidth(text);
        int textHeight = fm.getAscent();
        int x = (size - textWidth) / 2;
        int y = (size + textHeight) / 2 - fm.getDescent() / 2;

        g2d.drawString(text, x, y);

        g2d.dispose();
        return new ImageIcon(image);
    }

    /**
     * Adds the Claude Code toolbar icon to JMeter's toolbar.
     */
    private void addToolbarIcon() {
        GuiPackage instance = GuiPackage.getInstance();
        if (instance != null) {
            final MainFrame mf = instance.getMainFrame();
            final ComponentFinder<JMeterToolBar> finder = new ComponentFinder<>(JMeterToolBar.class);
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    JMeterToolBar toolbar = null;
                    while (toolbar == null) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            log.debug("Couldn't add Claude Code button to toolbar", e);
                        }
                        log.debug("Searching for toolbar for Claude Code icon...");
                        toolbar = finder.findComponentIn(mf);
                    }
                    // Insert right after the last button (which should include the Feather Wand
                    // button)
                    int pos = getPositionAfterFeatherWand(toolbar.getComponents());
                    log.debug("Claude Code icon position: {}", pos);
                    Component toolbarButton = getToolbarButton();
                    toolbarButton
                            .setSize(toolbar.getComponent(Math.min(pos, toolbar.getComponentCount() - 1)).getSize());
                    toolbar.add(toolbarButton, pos);
                }
            });
        }
    }

    /**
     * Creates the toolbar button with the terminal icon.
     */
    private JButton getToolbarButton() {
        JButton button = new JButton(getClaudeCodeIcon(22));
        button.setToolTipText("Toggle Claude Code Terminal");
        button.addActionListener(this);
        button.setActionCommand("toggle_claude_code_panel");
        return button;
    }

    /**
     * Finds the position right after the Feather Wand button (or before the
     * "start" button as fallback).
     */
    private int getPositionAfterFeatherWand(Component[] toolbarComponents) {
        int featherWandPos = -1;
        int startPos = 0;

        for (int i = 0; i < toolbarComponents.length; i++) {
            Component item = toolbarComponents[i];
            if (item instanceof JButton) {
                JButton btn = (JButton) item;
                String actionCommand = btn.getModel().getActionCommand();
                String tooltip = btn.getToolTipText();

                // Find the Feather Wand button
                if ("toggle_ai_panel".equals(actionCommand) ||
                        (tooltip != null && tooltip.contains("FeatherWand"))) {
                    featherWandPos = i;
                }

                // Find the start button as a fallback reference
                if ("start".equals(actionCommand)) {
                    startPos = i;
                }
            }
        }

        // Place right after Feather Wand if found, otherwise before start
        return featherWandPos >= 0 ? featherWandPos + 1 : startPos;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        log.debug("Claude Code action: {}", e.getActionCommand());
        try {
            toggleClaudeCodePanel();
        } catch (Exception err) {
            log.error("Error toggling Claude Code panel", err);
        }
    }

    /**
     * Toggles the Claude Code terminal panel on/off.
     * Uses the same split-pane approach as AiMenuItem.openAiChatPanel().
     */
    private void toggleClaudeCodePanel() {
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage == null)
            return;

        MainFrame mainFrame = guiPackage.getMainFrame();

        if (currentPanel != null && splitPane != null && splitPane.isShowing()) {
            // Panel is currently shown — remove it
            Container contentPane = mainFrame.getContentPane();
            contentPane.remove(splitPane);

            Component mainComponent = splitPane.getLeftComponent();
            contentPane.add(mainComponent, BorderLayout.CENTER);

            // Stop the Claude process when hiding
            currentPanel.stopClaudeCode();
            splitPane = null;
            currentPanel = null;
            log.info("Claude Code panel hidden");
        } else {
            // Panel is not shown — create and show it
            if (currentPanel == null) {
                currentPanel = new ClaudeCodePanel();
                log.info("Created new Claude Code panel");
            }

            Container contentPane = mainFrame.getContentPane();
            Component centerComp = null;
            for (Component comp : contentPane.getComponents()) {
                if (contentPane.getLayout() instanceof BorderLayout &&
                        ((BorderLayout) contentPane.getLayout()).getConstraints(comp) == BorderLayout.CENTER) {
                    centerComp = comp;
                    break;
                }
            }

            if (centerComp != null) {
                contentPane.remove(centerComp);

                splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, centerComp, currentPanel);
                splitPane.setResizeWeight(0.65);
                splitPane.setOneTouchExpandable(true);
                splitPane.setContinuousLayout(true);

                int preferredWidth = currentPanel.getPreferredSize().width;
                int totalWidth = mainFrame.getWidth();
                splitPane.setDividerLocation(totalWidth - preferredWidth - 10);

                contentPane.add(splitPane, BorderLayout.CENTER);
                log.info("Claude Code panel displayed");
            }
        }

        mainFrame.revalidate();
        mainFrame.repaint();
    }
}
