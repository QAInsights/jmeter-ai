package org.nfe.jmeter.ai.gui;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.MainFrame;
import org.apache.jmeter.gui.util.JMeterToolBar;
import org.nfe.jmeter.ai.service.AiService;
import org.nfe.jmeter.ai.service.ClaudeService;
import org.nfe.jmeter.ai.service.OpenAiService;
import org.nfe.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Objects;

public class AiMenuItem extends JMenuItem implements ActionListener {
    private static final Logger log = LoggerFactory.getLogger(AiMenuItem.class);
    private static final Action ai = new AI();
    private AiChatPanel currentChatPanel;
    private JSplitPane splitPane;
    private final JComponent parent;
    private Icon icon;

    public AiMenuItem(JComponent parent) {
        super(ai);
        this.parent = parent;
        addActionListener(this);
        addToolbarIcon();

        // Initialize the JSR223 context menu
        try {
            // Create AI service for the context menu based on JMeter properties
            String aiServiceType = AiConfig.getProperty("jmeter.ai.service.type", "openai");
            AiService aiService = createAiService(aiServiceType);

            if (aiService != null) {
                JSR223ContextMenu.initialize(aiService);
                log.info("Initialized JSR223 context menu with {} service", aiServiceType);
            } else {
                log.warn("No AI service available for JSR223 context menu");
                // Still initialize context menu, but it will show the disabled state
                JSR223ContextMenu.initialize(null);
            }

            // Add tree selection listener to detect when components are selected in JMeter
            // tree
            addTreeSelectionListener();
        } catch (Exception e) {
            log.error("Failed to initialize JSR223 context menu", e);
        }
    }

    /**
     * Creates an appropriate AI service based on configuration
     * 
     * @param serviceType the type of AI service to create
     * @return the AI service instance, or null if configuration is invalid
     */
    private AiService createAiService(String serviceType) {
        try {
            if ("openai".equalsIgnoreCase(serviceType)) {
                // Check if OpenAI API key is configured
                String apiKey = AiConfig.getProperty("openai.api.key", "");
                String model = AiConfig.getProperty("openai.default.model", "");
                if (apiKey != null && !apiKey.isEmpty() && !apiKey.equals("YOUR_API_KEY")
                        && model != null && !model.isEmpty()) {
                    return new OpenAiService();
                }
            } else if ("anthropic".equalsIgnoreCase(serviceType)) {
                // Check if Anthropic API key is configured
                String apiKey = AiConfig.getProperty("anthropic.api.key", "");
                String model = AiConfig.getProperty("anthropic.model", "");
                if (apiKey != null && !apiKey.isEmpty() && !apiKey.equals("YOUR_API_KEY")
                        && model != null && !model.isEmpty()) {
                    return new ClaudeService();
                }
            }
        } catch (Exception e) {
            log.error("Error creating AI service", e);
        }
        return null;
    }

    public static ImageIcon getButtonIcon(int pixelSize) {
        String sizedImage = String.format("/org/qainsights/jmeter/ai/featherwand-%dx%d.png", pixelSize, pixelSize);
        return new ImageIcon(Objects.requireNonNull(AiMenuItem.class.getResource(sizedImage)));
    }

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
                            log.debug("Couldn't add button to menubar", e);
                        }
                        log.debug("Searching for toolbar ... ");
                        toolbar = finder.findComponentIn(mf);
                    }
                    int pos = getPositionForIcon(toolbar.getComponents());
                    log.debug("validate rootPos: " + String.valueOf(pos));
                    Component toolbarButton = getToolbarButton();
                    toolbarButton.setSize(toolbar.getComponent(pos).getSize());
                    toolbar.add(toolbarButton, pos);
                }
            });
        }
    }

    private JButton getToolbarButton() {
        JButton button = new JButton(getButtonIcon(22));
        button.setToolTipText("Toggle FeatherWand Panel");
        button.addActionListener(this);
        button.setActionCommand("toggle_ai_panel");
        return button;
    }

    private int getPositionForIcon(Component[] toolbarComponents) {
        int index = 0;
        for (Component item : toolbarComponents) {
            String itemClassName = item.getClass().getName();
            if (itemClassName.contains("javax.swing.JButton")) {
                String actionCommandText = ((JButton) item).getModel().getActionCommand();
                log.debug("Running for iteration: " + index + ", " + actionCommandText);
                if (actionCommandText != null && actionCommandText.equals("start")) {
                    break;
                }
            }
            index++;
        }
        return index;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        log.debug("Clicked" + e.getActionCommand());
        try {
            log.info("Calling AI panel");
            openAiChatPanel();
        } catch (Exception err) {
            log.debug("Error while AI action performed: " + err);
        }
    }

    private void openAiChatPanel() {
        GuiPackage guiPackage = GuiPackage.getInstance();
        if (guiPackage != null) {
            MainFrame mainFrame = guiPackage.getMainFrame();

            if (currentChatPanel != null && splitPane != null && splitPane.isShowing()) {
                // Panel is currently shown, remove it
                Container contentPane = mainFrame.getContentPane();
                contentPane.remove(splitPane);

                // Get the left component (main JMeter component) from the split pane
                Component mainComponent = splitPane.getLeftComponent();

                // Add it back to the content pane
                contentPane.add(mainComponent, BorderLayout.CENTER);

                // Clear references
                splitPane = null;
                log.info("AI Chat Panel hidden");
            } else {
                // Panel is not shown, add it
                if (currentChatPanel == null) {
                    // Only create a new panel if one doesn't exist
                    currentChatPanel = new AiChatPanel();
                    log.info("Created new AI Chat Panel");
                }

                // Get the current center component
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
                    // Remove the center component
                    contentPane.remove(centerComp);

                    // Create a split pane with the center component and chat panel
                    splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, centerComp, currentChatPanel);
                    splitPane.setResizeWeight(0.7); // Give more space to the main component
                    splitPane.setOneTouchExpandable(true);
                    splitPane.setContinuousLayout(true);

                    // Set divider location to give appropriate space to the chat panel
                    int preferredWidth = currentChatPanel.getPreferredSize().width;
                    int totalWidth = mainFrame.getWidth();
                    splitPane.setDividerLocation(totalWidth - preferredWidth - 10);

                    // Add the split pane to the content pane
                    contentPane.add(splitPane, BorderLayout.CENTER);
                    log.info("AI Chat Panel displayed");
                }
            }

            mainFrame.revalidate();
            mainFrame.repaint();
        }
    }

    /**
     * Adds a tree selection listener to detect when components are selected in
     * JMeter tree.
     * This allows us to add context menus to JSR223 components when they're
     * selected.
     */
    private void addTreeSelectionListener() {
        try {
            GuiPackage guiPackage = GuiPackage.getInstance();
            if (guiPackage != null && guiPackage.getTreeListener() != null) {
                guiPackage.getTreeListener().getJTree().addTreeSelectionListener(e -> {
                    // Use SwingUtilities.invokeLater to avoid blocking the UI
                    SwingUtilities.invokeLater(() -> {
                        // Add context menu to the current JSR223 editor if one exists
                        JSR223ContextMenu.addContextMenuToCurrentEditor();
                    });
                });
                log.info("Added tree selection listener for JSR223 context menu");
            } else {
                log.warn("GuiPackage or TreeListener is null, context menu may not be added automatically");
            }
        } catch (Exception e) {
            log.error("Failed to add tree selection listener", e);
        }
    }
}
