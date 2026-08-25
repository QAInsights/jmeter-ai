package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.qainsights.jmeter.ai.service.prefs.ModelSelectorPreferences;
import org.qainsights.jmeter.ai.service.reasoning.ModelCapabilityCatalog;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ModelSelectorPanel}: default/first-row selection with
 * listener firing, button label rendering, loading state, and pin toggling.
 */
class ModelSelectorPanelTest {

    @TempDir
    Path tempDir;

    private ModelSelectorPreferences prefs() {
        return ModelSelectorPreferences.load(tempDir.resolve("preferences.json"));
    }

    private ModelSelectorPanel panel(ModelSelectorPreferences prefs) {
        return new ModelSelectorPanel(prefs, ModelCapabilityCatalog.getInstance());
    }

    private static final List<String> MODELS = List.of(
            "claude-opus-4-8", "openai:gpt-5.1", "openai:gpt-4o", "google:gemini-2.5-pro");

    @Test
    void loadingStateShowsPlaceholderAndNoSelection() {
        ModelSelectorPanel panel = panel(prefs());
        assertNull(panel.getSelectedModel());
        assertEquals("Loading available models\u2026", panel.buttonText());
    }

    private QuietButton selectorButtonOf(ModelSelectorPanel panel) {
        for (java.awt.Component component : panel.getComponents()) {
            if (component instanceof QuietButton button) {
                return button;
            }
        }
        throw new AssertionError("no selector button found");
    }

    private javax.swing.JToggleButton starButtonOf(ModelSelectorPanel panel) {
        return panel.favoriteButton();
    }

    /** Minimal provider: only its selector prefix matters to the panel. */
    private record FakeCliProvider(String prefix)
            implements org.qainsights.jmeter.ai.cli.SubscriptionCliProvider {

        @Override
        public String displayName() {
            return "Fake CLI";
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public boolean isInstalled() {
            return true;
        }

        @Override
        public org.qainsights.jmeter.ai.cli.CliAuthState getAuthStatus() {
            throw new UnsupportedOperationException();
        }

        @Override
        public org.qainsights.jmeter.ai.cli.CliAuthState login() {
            throw new UnsupportedOperationException();
        }

        @Override
        public org.qainsights.jmeter.ai.cli.CliAuthState logout() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String execute(String prompt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String installHint() {
            return "install it";
        }

        @Override
        public String signInActionLabel() {
            return "Sign in";
        }

        @Override
        public String modelPrefix() {
            return prefix;
        }

        @Override
        public void refresh() {
        }

        @Override
        public List<String> listModels() {
            return List.of("default");
        }

        @Override
        public String getModel() {
            return "";
        }

        @Override
        public void setModel(String model) {
        }
    }

    @Test
    void setModelsAppliesDefaultAndFiresListenerWithoutDirtyingRecents() {
        ModelSelectorPreferences prefs = prefs();
        ModelSelectorPanel panel = panel(prefs);
        List<String> selections = new ArrayList<>();
        panel.setSelectionListener(selections::add);

        panel.setModels(MODELS, "openai:gpt-5.1");

        // startup install routes the services but must not masquerade as a user choice
        assertEquals("openai:gpt-5.1", panel.getSelectedModel());
        assertEquals(List.of("openai:gpt-5.1"), selections);
        assertTrue(prefs.recents().isEmpty());
    }

    @Test
    void applyIfAvailableAppliesListedModelWithoutDirtyingRecents() {
        ModelSelectorPreferences prefs = prefs();
        ModelSelectorPanel panel = panel(prefs);
        List<String> selections = new ArrayList<>();
        panel.setSelectionListener(selections::add);
        panel.setModels(MODELS, "claude-opus-4-8");

        panel.applyIfAvailable("google:gemini-2.5-pro");

        assertEquals("google:gemini-2.5-pro", panel.getSelectedModel());
        assertEquals(List.of("claude-opus-4-8", "google:gemini-2.5-pro"), selections);
        assertTrue(prefs.recents().isEmpty());
    }

    @Test
    void applyIfAvailableIgnoresUnknownOrNullModel() {
        ModelSelectorPreferences prefs = prefs();
        ModelSelectorPanel panel = panel(prefs);
        panel.setModels(MODELS, "claude-opus-4-8");

        panel.applyIfAvailable("openai:no-such-model");
        panel.applyIfAvailable(null);

        assertEquals("claude-opus-4-8", panel.getSelectedModel());
    }

    @Test
    void explicitSelectRecordsUse() {
        ModelSelectorPreferences prefs = prefs();
        ModelSelectorPanel panel = panel(prefs);
        List<String> selections = new ArrayList<>();
        panel.setSelectionListener(selections::add);
        panel.setModels(MODELS, "claude-opus-4-8");

        panel.select("openai:gpt-4o");

        assertEquals("openai:gpt-4o", panel.getSelectedModel());
        assertEquals(List.of("openai:gpt-4o"), prefs.recents());
        assertEquals(List.of("claude-opus-4-8", "openai:gpt-4o"), selections);
    }

    @Test
    void toolbarStarSyncsWhenPinChangesExternally() {
        // e.g. a pin toggled through the picker's star zone while it was open
        ModelSelectorPreferences prefs = prefs();
        ModelSelectorPanel panel = panel(prefs);
        panel.setModels(MODELS, "claude-opus-4-8");
        javax.swing.JToggleButton star = starButtonOf(panel);
        assertFalse(star.isSelected());

        prefs.togglePinned("claude-opus-4-8");

        assertTrue(star.isSelected(), "toolbar star must follow prefs changes from other surfaces");
    }

    @Test
    void unknownDefaultFallsBackToFirstModel() {
        ModelSelectorPanel panel = panel(prefs());
        panel.setModels(MODELS, "openai:gpt-9-turbo");
        assertEquals("claude-opus-4-8", panel.getSelectedModel());
    }

    @Test
    void emptyModelListKeepsSelectorDisabled() {
        ModelSelectorPanel panel = panel(prefs());
        panel.setModels(List.of(), "claude-opus-4-8");
        assertNull(panel.getSelectedModel());
        assertEquals("Loading available models\u2026", panel.buttonText());
    }

    @Test
    void enabledCliProviderKeepsSelectorAvailableWithoutLoadedModels() {
        ModelSelectorPanel panel = panel(prefs());
        panel.setCliProviders(List.of(new FakeCliProvider("codex:")));
        panel.setModels(List.of(), null);

        assertTrue(selectorButtonOf(panel).isEnabled());
    }

    @Test
    void buttonPrioritizesTheModelName() {
        ModelSelectorPanel panel = panel(prefs());
        panel.setModels(MODELS, "openai:gpt-5.1");
        assertEquals("gpt-5.1", panel.buttonText());
    }

    @Test
    void bareModelKeepsItsFullName() {
        ModelSelectorPanel panel = panel(prefs());
        panel.setModels(MODELS, "claude-opus-4-8");
        assertEquals("claude-opus-4-8", panel.buttonText());
    }

    @Test
    void selectorUsesAllAvailableWidth() {
        ModelSelectorPanel panel = panel(prefs());
        panel.setModels(MODELS, "google:gemini-2.5-pro");
        panel.setSize(280, 32);
        panel.doLayout();

        QuietButton selector = selectorButtonOf(panel);
        assertEquals(280, selector.getWidth());
        assertEquals("gemini-2.5-pro", selector.getText());
        assertEquals(QuietButton.Kind.OUTLINED, selector.kind());
    }

    @Test
    void remembersCustomCliModelsForRegisteredProvidersOnly() {
        ModelSelectorPreferences prefs = prefs();
        prefs.addCustomModel("codex:gpt-5.6-sol");
        prefs.addCustomModel("claude-code:opus-4.6"); // provider not registered
        ModelSelectorPanel panel = panel(prefs);
        panel.setCliProviders(List.of(new FakeCliProvider("codex:")));

        List<String> selections = new ArrayList<>();
        panel.setSelectionListener(selections::add);
        panel.setModels(MODELS, "claude-opus-4-8");

        panel.applyIfAvailable("codex:gpt-5.6-sol");
        assertEquals("codex:gpt-5.6-sol", panel.getSelectedModel());

        panel.applyIfAvailable("claude-code:opus-4.6");
        assertEquals("codex:gpt-5.6-sol", panel.getSelectedModel());
    }

    @Test
    void selectingAFreshlyTypedModelKeepsItAvailable() {
        ModelSelectorPanel panel = panel(prefs());
        panel.setModels(MODELS, "claude-opus-4-8");

        panel.select("codex:gpt-5.6-terra");
        assertEquals("codex:gpt-5.6-terra", panel.getSelectedModel());

        panel.applyIfAvailable("claude-opus-4-8");
        panel.applyIfAvailable("codex:gpt-5.6-terra");
        assertEquals("codex:gpt-5.6-terra", panel.getSelectedModel());
    }

    @Test
    void starTogglesPinForCurrentSelection() {
        ModelSelectorPreferences prefs = prefs();
        ModelSelectorPanel panel = panel(prefs);
        panel.setModels(MODELS, "claude-opus-4-8");
        assertFalse(prefs.isPinned("claude-opus-4-8"));

        // simulate the user clicking the star toggle twice (pin, then unpin)
        javax.swing.JToggleButton star = starButtonOf(panel);
        star.doClick();
        assertTrue(prefs.isPinned("claude-opus-4-8"));
        assertTrue(star.isSelected());
        star.doClick();
        assertFalse(prefs.isPinned("claude-opus-4-8"));
        assertFalse(star.isSelected());
        assertEquals("claude-opus-4-8", panel.getSelectedModel());
    }
}
