package org.qainsights.jmeter.ai.gui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

import org.qainsights.jmeter.ai.cli.SubscriptionCliProvider;
import org.qainsights.jmeter.ai.gui.theme.UiTokens;
import org.qainsights.jmeter.ai.service.prefs.ModelSelectorPreferences;
import org.qainsights.jmeter.ai.service.reasoning.ModelCapabilityCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The toolbar's model selector: a button showing the current model plus a pin
 * toggle. Clicking the button opens the {@link ModelPickerPopup} docked
 * underneath - a searchable dropdown listing pinned models first, then
 * recently used, then everything else (see {@link ModelSelectorPreferences}).
 * The star button pins/unpins the current model.
 * <p>
 * Selections are exposed through {@link #setSelectionListener} and
 * {@link #getSelectedModel()} using the same prefixed-id strings as before -
 * routing in {@code AiResponseRouter}/{@code CommandDispatcher} is untouched.
 */
class ModelSelectorPanel extends JPanel {

    private static final Logger log = LoggerFactory.getLogger(ModelSelectorPanel.class);

    private final JButton selectorButton;
    private final JToggleButton starButton;
    private final ModelSelectorPreferences prefs;
    private final ModelCapabilityCatalog catalog;
    private List<String> allModels = List.of();
    private List<SubscriptionCliProvider> cliProviders = List.of();
    private Consumer<String> selectionListener = model -> { };
    private String currentModel;

    ModelSelectorPanel(ModelSelectorPreferences prefs, ModelCapabilityCatalog catalog) {
        super(new BorderLayout(UiTokens.SPACE_1, 0));
        this.prefs = prefs;
        this.catalog = catalog;
        setOpaque(false);

        selectorButton = new QuietButton(
                "Loading available models\u2026", QuietButton.Kind.OUTLINED).compact();
        selectorButton.setIcon(ChevronIcons.down(10));
        selectorButton.setHorizontalTextPosition(SwingConstants.LEFT);
        selectorButton.setHorizontalAlignment(SwingConstants.LEFT);
        selectorButton.setIconTextGap(UiTokens.MODEL_SELECTOR_ICON_GAP);
        selectorButton.setToolTipText("Change model - type to search");
        selectorButton.getAccessibleContext().setAccessibleName("Selected AI model");
        selectorButton.setEnabled(false); // until the model list arrives
        selectorButton.addActionListener(e -> openPopup());

        starButton = new JToggleButton(StarIcons.outline(14));
        starButton.setSelectedIcon(StarIcons.filled(14));
        starButton.setToolTipText("Pin this model to keep it at the top of the list");
        starButton.getAccessibleContext().setAccessibleName("Pin selected model");
        starButton.setContentAreaFilled(false);
        starButton.setBorderPainted(false);
        starButton.setFocusPainted(true);
        starButton.setPreferredSize(new Dimension(
                UiTokens.FAVORITE_WIDTH, UiTokens.FAVORITE_HEIGHT));
        starButton.setEnabled(false);
        starButton.addActionListener(e -> onStarToggled());
        // keeps the toolbar star in sync when the picker's star zones change pins
        prefs.addChangeListener(this::syncStar);

        add(selectorButton, BorderLayout.CENTER);
    }

    /**
     * Registers the subscription CLI providers (Codex, Claude Code) whose sign-in
     * state and actions are shown in the picker's footer.
     */
    void setCliProviders(List<SubscriptionCliProvider> providers) {
        this.cliProviders = providers == null ? List.of() : List.copyOf(providers);
    }

    /** Registers the callback fired with the prefixed id whenever the effective model changes. */
    void setSelectionListener(Consumer<String> listener) {
        this.selectionListener = listener != null ? listener : model -> { };
    }

    /** The selected model id, or null while models are still loading. */
    String getSelectedModel() {
        return currentModel;
    }

    /** The selector button's label (visible for tests). */
    String buttonText() {
        return selectorButton.getText();
    }

    JToggleButton favoriteButton() {
        return starButton;
    }

    /**
     * Installs the freshly loaded model list and applies the default model
     * (or the first entry when the default is unavailable). Applying is an
     * install, not a user choice: it routes the services but does not touch
     * the recents history.
     */
    void setModels(List<String> models, String defaultModel) {
        allModels = List.copyOf(models);
        selectorButton.setEnabled(!allModels.isEmpty());
        String toSelect = defaultModel != null && allModels.contains(defaultModel)
                ? defaultModel
                : allModels.isEmpty() ? null : allModels.get(0);
        if (toSelect != null) {
            applyModel(toSelect);
        }
    }

    /**
     * Applies a model as the current selection: updates the button, syncs the
     * star, and fires the routing listener. No history side effects.
     */
    private void applyModel(String model) {
        currentModel = model;
        selectorButton.setText(ModelDisplay.parse(model)[0]);
        selectorButton.setToolTipText("Selected model: " + ModelDisplay.formatLabel(model)
                + " - click to change");
        starButton.setEnabled(true);
        syncStar();
        selectionListener.accept(model);
    }

    /**
     * Applies a model only when it is present in the loaded list - used to
     * reselect the model carried by a restored session. Like
     * {@link #setModels}, this is an install, not a user choice: no recents.
     */
    void applyIfAvailable(String model) {
        if (model != null && allModels.contains(model)) {
            applyModel(model);
        }
    }

    /**
     * A deliberate user choice (picker confirm): applies the model and
     * records it in the recently-used history. Package-private for tests.
     */
    void select(String model) {
        log.info("Selected model: {}", model);
        applyModel(model);
        prefs.recordUse(model);
    }

    private void syncStar() {
        starButton.setSelected(currentModel != null && prefs.isPinned(currentModel));
    }

    private void openPopup() {
        if (allModels.isEmpty() && cliProviders.isEmpty()) {
            return;
        }
        java.awt.Window owner = SwingUtilities.getWindowAncestor(this);
        ModelPickerPopup popup = new ModelPickerPopup(owner, allModels, currentModel, prefs, catalog,
                cliProviders);
        popup.showFor(selectorButton, this::select);
    }

    private void onStarToggled() {
        if (currentModel == null) {
            return;
        }
        prefs.togglePinned(currentModel); // change listener syncs the star
    }
}
