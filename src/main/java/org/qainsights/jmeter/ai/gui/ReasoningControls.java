package org.qainsights.jmeter.ai.gui;

import java.awt.FlowLayout;
import java.awt.Font;
import java.util.List;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JPanel;

import org.qainsights.jmeter.ai.gui.theme.ThemeColors;
import org.qainsights.jmeter.ai.service.reasoning.ReasoningCapabilities;
import org.qainsights.jmeter.ai.service.reasoning.ReasoningSettings;

/**
 * The thinking/effort controls that sit in the toolbar next to the model
 * selector: a "Thinking" toggle plus an effort dropdown. Which controls are
 * visible is driven by the selected model's capabilities
 * ({@link ReasoningCapabilities}) - a model with no reasoning support shows
 * neither, an always-reasoning model (o-series, gpt-5) shows only the effort
 * dropdown, and a toggleable model shows both. User choices are written
 * straight into the shared {@link ReasoningSettings}.
 */
class ReasoningControls extends JPanel {

    private final ReasoningSettings settings;
    private final JCheckBox thinkingToggle;
    private final JComboBox<String> effortCombo;

    /** Guards against listener feedback while repopulating the combo. */
    private boolean updating;

    ReasoningControls(ReasoningSettings settings) {
        super(new FlowLayout(FlowLayout.LEFT, 4, 0));
        setOpaque(false);
        this.settings = settings;

        thinkingToggle = new JCheckBox("Thinking");
        thinkingToggle.setOpaque(false);
        thinkingToggle.setFont(thinkingToggle.getFont().deriveFont(Font.PLAIN, 11f));
        thinkingToggle.setForeground(ThemeColors.foreground());
        thinkingToggle.setToolTipText("Let the model think step by step before answering");
        thinkingToggle.setSelected(settings.isThinkingEnabled());
        thinkingToggle.addActionListener(e -> {
            if (!updating) {
                settings.userSetThinkingEnabled(thinkingToggle.isSelected());
            }
        });
        add(thinkingToggle);

        effortCombo = new JComboBox<>();
        effortCombo.setFont(effortCombo.getFont().deriveFont(Font.PLAIN, 11f));
        effortCombo.setToolTipText("Reasoning effort");
        effortCombo.addActionListener(e -> {
            if (!updating) {
                Object selected = effortCombo.getSelectedItem();
                if (selected != null) {
                    settings.userSetEffort((String) selected);
                }
            }
        });
        add(effortCombo);

        setVisible(false);
    }

    /**
     * Re-evaluates visibility and items for the given model. Also syncs the
     * toggle state from the settings (e.g. after a settings reset).
     *
     * @param prefixedModel the model id from the selector (may be null)
     */
    void updateForModel(String prefixedModel) {
        boolean canToggle = ReasoningCapabilities.supportsThinkingToggle(prefixedModel);
        List<String> levels = ReasoningCapabilities.effortLevels(prefixedModel);

        updating = true;
        try {
            thinkingToggle.setVisible(canToggle);
            thinkingToggle.setSelected(effectiveToggleState(prefixedModel));

            effortCombo.removeAllItems();
            for (String level : levels) {
                effortCombo.addItem(level);
            }
            effortCombo.setVisible(!levels.isEmpty());
            if (!levels.isEmpty()) {
                effortCombo.setSelectedItem(pickDefaultLevel(levels, prefixedModel));
                Object selected = effortCombo.getSelectedItem();
                if (selected != null) {
                    settings.setEffort((String) selected);
                }
            }
            setVisible(canToggle || !levels.isEmpty());
        } finally {
            updating = false;
        }
        revalidate();
    }

    /**
     * The toggle state to display: the user's choice, or the provider-specific
     * property default when the user hasn't touched the toggle yet (Ollama's
     * legacy {@code ollama.thinking.mode} acts as its default).
     */
    private boolean effectiveToggleState(String prefixedModel) {
        if (!settings.isThinkingToggled()
                && prefixedModel != null && prefixedModel.startsWith("ollama:")) {
            return "enabled".equalsIgnoreCase(
                    org.qainsights.jmeter.ai.utils.AiConfig.getProperty(
                            "ollama.thinking.mode", "DISABLED").trim());
        }
        return settings.isThinkingEnabled();
    }

    /** Keeps the user's last effort when valid for this model, else a sensible default. */
    private String pickDefaultLevel(List<String> levels, String prefixedModel) {
        String current = effectiveEffort(prefixedModel);
        if (levels.contains(current) && !"none".equals(current)) {
            return current;
        }
        if (levels.contains(ReasoningSettings.DEFAULT_EFFORT)) {
            return ReasoningSettings.DEFAULT_EFFORT;
        }
        return levels.get(levels.size() - 1);
    }

    /**
     * The effort to preselect: the user's choice, or the provider-specific
     * property default when untouched (legacy {@code ollama.thinking.level}).
     */
    private String effectiveEffort(String prefixedModel) {
        if (!settings.isEffortTouched()
                && prefixedModel != null && prefixedModel.startsWith("ollama:")) {
            String level = org.qainsights.jmeter.ai.utils.AiConfig.getProperty(
                    "ollama.thinking.level", "medium");
            return level == null ? ReasoningSettings.DEFAULT_EFFORT
                    : level.trim().toLowerCase(java.util.Locale.ROOT);
        }
        return settings.getEffort();
    }

    /** The thinking checkbox (for tests). */
    JCheckBox getThinkingToggle() {
        return thinkingToggle;
    }

    /** The effort combo (for tests). */
    JComboBox<String> getEffortCombo() {
        return effortCombo;
    }
}
