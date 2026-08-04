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
        assertEquals("Loading models…", panel.buttonText());
    }

    private javax.swing.JToggleButton starButtonOf(ModelSelectorPanel panel) {
        for (java.awt.Component c : panel.getComponents()) {
            if (c instanceof javax.swing.JToggleButton) {
                return (javax.swing.JToggleButton) c;
            }
        }
        throw new AssertionError("no star button found");
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
        assertEquals("Loading models…", panel.buttonText());
    }

    @Test
    void buttonShowsDisplayNameAndProvider() {
        ModelSelectorPanel panel = panel(prefs());
        panel.setModels(MODELS, "openai:gpt-5.1");
        assertEquals("gpt-5.1  ·  OpenAI", panel.buttonText());
    }

    @Test
    void bareModelShowsAnthropicProvider() {
        ModelSelectorPanel panel = panel(prefs());
        panel.setModels(MODELS, "claude-opus-4-8");
        assertEquals("claude-opus-4-8  ·  Anthropic", panel.buttonText());
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
