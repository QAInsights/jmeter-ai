package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import org.qainsights.jmeter.ai.service.prefs.ModelSelectorPreferences;
import org.qainsights.jmeter.ai.service.reasoning.ModelCapabilityCatalog;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ModelPickerPopup}: the filter/sort pipeline (pinned
 * first in pin order, then recents most-recent-first, then alphabetical;
 * matching on id, display name, or provider) and popup construction with
 * initial selection.
 */
class ModelPickerPopupTest {

    @TempDir
    Path tempDir;

    private ModelSelectorPreferences prefs() {
        return ModelSelectorPreferences.load(tempDir.resolve("preferences.json"));
    }

    private static final List<String> MODELS = List.of(
            "openai:gpt-5.1",
            "claude-opus-4-8",
            "google:gemini-2.5-pro",
            "openai:gpt-4o");

    @Test
    void noCurationSortsAlphabeticallyByDisplayName() {
        List<String> sorted = ModelPickerPopup.filterAndSort(MODELS, "", prefs());
        assertEquals(List.of(
                "claude-opus-4-8",
                "google:gemini-2.5-pro",
                "openai:gpt-4o",
                "openai:gpt-5.1"), sorted);
    }

    @Test
    void pinnedComeFirstInPinOrder() {
        ModelSelectorPreferences prefs = prefs();
        prefs.togglePinned("openai:gpt-4o");
        prefs.togglePinned("claude-opus-4-8");
        List<String> sorted = ModelPickerPopup.filterAndSort(MODELS, "", prefs);
        assertEquals(List.of(
                "openai:gpt-4o",
                "claude-opus-4-8",
                "google:gemini-2.5-pro",
                "openai:gpt-5.1"), sorted);
    }

    @Test
    void recentsSortAfterPinsMostRecentFirst() {
        ModelSelectorPreferences prefs = prefs();
        prefs.togglePinned("google:gemini-2.5-pro");
        prefs.recordUse("openai:gpt-4o");
        prefs.recordUse("openai:gpt-5.1");
        List<String> sorted = ModelPickerPopup.filterAndSort(MODELS, "", prefs);
        assertEquals(List.of(
                "google:gemini-2.5-pro",
                "openai:gpt-5.1",
                "openai:gpt-4o",
                "claude-opus-4-8"), sorted);
    }

    @Test
    void filterMatchesDisplayNameCaseInsensitively() {
        assertEquals(List.of("openai:gpt-4o", "openai:gpt-5.1"),
                ModelPickerPopup.filterAndSort(MODELS, "GPT", prefs()));
        assertEquals(List.of("google:gemini-2.5-pro"),
                ModelPickerPopup.filterAndSort(MODELS, "gemini", prefs()));
    }

    @Test
    void filterMatchesProviderAndRawId() {
        assertEquals(List.of("openai:gpt-4o", "openai:gpt-5.1"),
                ModelPickerPopup.filterAndSort(MODELS, "openai", prefs()));
        assertEquals(List.of("claude-opus-4-8"),
                ModelPickerPopup.filterAndSort(MODELS, "anthropic", prefs()));
        assertEquals(List.of("google:gemini-2.5-pro"),
                ModelPickerPopup.filterAndSort(MODELS, "google:", prefs()));
    }

    @Test
    void emptyAndNullFiltersMatchEverything() {
        assertEquals(4, ModelPickerPopup.filterAndSort(MODELS, null, prefs()).size());
        assertEquals(4, ModelPickerPopup.filterAndSort(MODELS, "  ", prefs()).size());
        assertTrue(ModelPickerPopup.filterAndSort(MODELS, "nonexistent", prefs()).isEmpty());
    }

    @Test
    void opensAboveWhenMoreRoomAbove() {
        // button at the bottom of a 1080p screen: 1040px above, 8px below
        ModelPickerPopup.Placement p = ModelPickerPopup.verticalPlacement(
                1040, 32, ModelPickerPopup.HEIGHT, 0, 1080);
        assertTrue(p.above());
        assertEquals(1040 - p.height(), p.y());
    }

    @Test
    void opensBelowWhenMoreRoomBelow() {
        // button near the top: 40px above, 1008px below
        ModelPickerPopup.Placement p = ModelPickerPopup.verticalPlacement(
                40, 32, ModelPickerPopup.HEIGHT, 0, 1080);
        assertFalse(p.above());
        assertEquals(72, p.y());
    }

    @Test
    void heightIsCappedToAvailableSpace() {
        // 200px above, 60px below -> opens above but only 200px tall
        ModelPickerPopup.Placement p = ModelPickerPopup.verticalPlacement(
                200, 32, ModelPickerPopup.HEIGHT, 0, 292);
        assertTrue(p.above());
        assertEquals(200, p.height());
        assertEquals(0, p.y());
    }

    @Test
    void tieBreaksUpward() {
        // exactly equal space on both sides -> prefer the chat side (above)
        ModelPickerPopup.Placement p = ModelPickerPopup.verticalPlacement(
                500, 32, ModelPickerPopup.HEIGHT, 0, 1032);
        assertTrue(p.above());
    }

    @Test
    @org.junit.jupiter.api.condition.DisabledIfSystemProperty(
            named = "java.awt.headless", matches = "true")
    void popupSelectsCurrentModelInitially() {
        javax.swing.JFrame owner = new javax.swing.JFrame();
        ModelPickerPopup popup = new ModelPickerPopup(owner, MODELS,
                "google:gemini-2.5-pro", prefs(), ModelCapabilityCatalog.getInstance());
        try {
            assertEquals(4, popup.visibleModelCount());
            assertEquals("google:gemini-2.5-pro", popup.selectedVisibleModel());
            assertEquals("", popup.filterText());
        } finally {
            popup.dispose();
            owner.dispose();
        }
    }

    @Test
    @org.junit.jupiter.api.condition.DisabledIfSystemProperty(
            named = "java.awt.headless", matches = "true")
    void popupFallsBackToFirstRowWhenCurrentModelUnknown() {
        javax.swing.JFrame owner = new javax.swing.JFrame();
        ModelPickerPopup popup = new ModelPickerPopup(owner, MODELS,
                "openai:gpt-9-turbo", prefs(), ModelCapabilityCatalog.getInstance());
        try {
            assertEquals("claude-opus-4-8", popup.selectedVisibleModel());
        } finally {
            popup.dispose();
            owner.dispose();
        }
    }

    @Test
    @org.junit.jupiter.api.condition.DisabledIfSystemProperty(
            named = "java.awt.headless", matches = "true")
    void popupWindowIsOwnedAndFocusable() {
        // regression: an ownerless JWindow cannot take keyboard focus on
        // Windows, leaving the search field untypable. The JDK only considers
        // a non-Frame/Dialog window focusable while its owner is showing -
        // in production that is JMeter's main frame.
        javax.swing.JFrame owner = new javax.swing.JFrame();
        owner.setVisible(true);
        ModelPickerPopup popup = new ModelPickerPopup(owner, MODELS,
                null, prefs(), ModelCapabilityCatalog.getInstance());
        try {
            assertSame(owner, popup.getOwner());
            assertTrue(popup.isFocusableWindow(),
                    "search field needs a focusable window to receive typing");
        } finally {
            popup.dispose();
            owner.dispose();
        }
    }
}
