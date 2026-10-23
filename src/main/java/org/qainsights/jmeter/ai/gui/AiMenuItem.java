package org.qainsights.jmeter.ai.gui;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.MainFrame;
import org.apache.jmeter.gui.util.JMeterToolBar;
import com.google.genai.Client;
import org.qainsights.jmeter.ai.service.AiService;
import org.qainsights.jmeter.ai.apple.AppleFoundationHelper;
import org.qainsights.jmeter.ai.service.AppleFoundationAiService;
import org.qainsights.jmeter.ai.service.OpenAiService;
import org.qainsights.jmeter.ai.service.ClaudeService;
import org.qainsights.jmeter.ai.service.OllamaAiService;
import org.qainsights.jmeter.ai.service.DeepseekAiService;
import org.qainsights.jmeter.ai.service.GoogleAiService;
import org.qainsights.jmeter.ai.telemetry.Telemetry;
import org.qainsights.jmeter.ai.telemetry.TelemetryFeature;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.qainsights.jmeter.ai.utils.GatewayConfig;
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
                log.info("No AI provider configured; JSR223 AI refactor menu stays disabled until JMeter restarts with one set");
                // Still initialize context menu, but it will show the disabled state
                JSR223ContextMenu.initialize(null);
            }

            org.qainsights.jmeter.ai.extract.ExtractThisMenu.install();

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
    static AiService createAiService(String serviceType) {
        try {
            if ("openai".equalsIgnoreCase(serviceType)) {
                // Check if OpenAI API key is configured
                String apiKey = AiConfig.getProperty("openai.api.key", "");
                String model = AiConfig.getProperty("openai.default.model", "gpt-4o");
                boolean apiKeyOk = apiKey != null && !apiKey.isEmpty() && !apiKey.equals("YOUR_API_KEY");
                if ((apiKeyOk || GatewayConfig.hasOpenAiGatewayCredentials())
                        && model != null && !model.isEmpty()) {
                    return new OpenAiService();
                }
            } else if ("anthropic".equalsIgnoreCase(serviceType)) {
                // Check if Anthropic API key is configured
                String apiKey = AiConfig.getProperty("anthropic.api.key", "");
                String model = firstConfigured(
                        AiConfig.getProperty("anthropic.default.model", ""),
                        AiConfig.getProperty("claude.default.model", ""),
                        AiConfig.getProperty("anthropic.model", "claude-sonnet-4-6"));
                boolean apiKeyOk = apiKey != null && !apiKey.isEmpty() && !apiKey.equals("YOUR_API_KEY");
                if ((apiKeyOk || GatewayConfig.hasAnthropicGatewayCredentials())
                        && model != null && !model.isEmpty()) {
                    return new ClaudeService();
                }
            } else if ("ollama".equalsIgnoreCase(serviceType)) {
                String model = AiConfig.getProperty("ollama.default.model", "llama3.1");
                if (model != null && !model.isEmpty()) {
                    return new OllamaAiService();
                }
            } else if ("deepseek".equalsIgnoreCase(serviceType)) {
                String apiKey = AiConfig.getProperty("deepseek.api.key", "");
                String model = AiConfig.getProperty("deepseek.default.model", "deepseek-chat");
                if (apiKey != null && !apiKey.isEmpty() && !apiKey.equals("YOUR_API_KEY")
                        && model != null && !model.isEmpty()) {
                    return new DeepseekAiService();
                }
            } else if ("google".equalsIgnoreCase(serviceType)) {
                String apiKey = AiConfig.getProperty("google.api.key", "");
                String model = AiConfig.getProperty("google.default.model", "gemini-2.5-flash");
                if (apiKey != null && !apiKey.isEmpty() && !apiKey.equals("YOUR_GOOGLE_API_KEY")
                        && !apiKey.equals("YOUR_API_KEY") && model != null && !model.isEmpty()) {
                    Client googleClient = Client.builder().apiKey(apiKey).build();
                    return new GoogleAiService(googleClient);
                }
            } else if ("apple".equalsIgnoreCase(serviceType)) {
                boolean enabled = Boolean.parseBoolean(AiConfig.getProperty("apple.enabled", "true"));
                if (enabled && AppleFoundationHelper.status().isAvailable()) {
                    return new AppleFoundationAiService();
                }
            }
        } catch (Exception e) {
            log.error("Error creating AI service", e);
        }
        return null;
    }

    private static String firstConfigured(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    public static ImageIcon getButtonIcon(int pixelSize) {
        String sizedImage = String.format("/org/qainsights/jmeter/ai/featherwand-%dx%d.png", pixelSize, pixelSize);
        return new ImageIcon(Objects.requireNonNull(AiMenuItem.class.getResource(sizedImage)));
    }

    private void addToolbarIcon() {
        GuiPackage instance = GuiPackage.getInstance();
        if (instance != null) {
            final MainFrame mf = instance.getMainFrame();
            // Register the global toggle shortcut independently of the toolbar
            // search below, so the shortcut does not depend on toolbar discovery.
            SwingUtilities.invokeLater(() -> registerToggleShortcut(mf));
            SwingUtilities.invokeLater(() -> {
                log.debug("Searching for toolbar ... ");
                ComponentFinder.whenFound(mf, JMeterToolBar.class, 500, 120, toolbar -> {
                    int pos = getPositionForIcon(toolbar.getComponents());
                    log.debug("validate rootPos: " + String.valueOf(pos));
                    Component toolbarButton = getToolbarButton();
                    toolbarButton.setSize(toolbar.getComponent(pos).getSize());
                    toolbar.add(toolbarButton, pos);
                });
            });
        }
    }

    private JButton getToolbarButton() {
        JButton button = new JButton(getButtonIcon(22));
        button.setToolTipText(FeatherWandToolbar.toggleTooltip());
        button.addActionListener(this);
        button.setActionCommand(FeatherWandToolbar.TOGGLE_ACTION);
        return button;
    }

    /**
     * Registers Ctrl/⌘+Shift+A on the main frame so users can toggle the panel
     * without hunting for the toolbar icon. The root-pane binding is the only
     * mechanism used; the menu item keeps its legacy Alt+V accelerator from the
     * {@code AI} action, so both shortcuts stay live. Safe no-op when the root
     * pane is missing.
     */
    private void registerToggleShortcut(MainFrame mainFrame) {
        if (mainFrame == null) {
            return;
        }
        FeatherWandToolbar.installToggleBinding(mainFrame.getRootPane(), this::openAiChatPanel);
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
        Telemetry.record(TelemetryFeature.PANEL_OPEN);
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
                SidePanelDock.undock(splitPane);

                splitPane = null;
                log.info("AI Chat Panel hidden");
            } else {
                if (currentChatPanel == null) {
                    currentChatPanel = new AiChatPanel();
                    log.info("Created new AI Chat Panel");
                }

                Container contentPane = mainFrame.getContentPane();
                splitPane = SidePanelDock.dock(contentPane, currentChatPanel, 0.7, mainFrame.getWidth());
                if (splitPane != null) {
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
