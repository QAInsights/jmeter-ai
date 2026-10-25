package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFrame;
import javax.swing.ListCellRenderer;
import javax.swing.SwingUtilities;

import org.qainsights.jmeter.ai.service.prefs.ModelSelectorPreferences;
import org.qainsights.jmeter.ai.service.reasoning.ModelCapabilityCatalog;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ModelPickerPopup}: the filter/sort pipeline (pinned
 * first in pin order, then recents most-recent-first, then featured, then
 * alphabetical; matching on id, display name, or provider) and popup
 * construction with initial selection.
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
    void featuredModelsFollowPinnedAndRecentModelsAndPrecedeAlphabeticalRemainder() {
        List<String> models = List.of(
                "openrouter:zulu/needle-z",
                "openrouter:alpha/needle-a",
                "openrouter:beta/needle-b",
                "openrouter:charlie/other");
        List<String> featured = List.of(
                "openrouter:zulu/needle-z",
                "openrouter:alpha/needle-a",
                "openrouter:missing/not-listed");

        assertEquals(List.of(
                        "openrouter:zulu/needle-z",
                        "openrouter:alpha/needle-a",
                        "openrouter:beta/needle-b",
                        "openrouter:charlie/other"),
                ModelPickerPopup.filterAndSort(models, "", prefs(), featured));

        ModelSelectorPreferences curatedPrefs = prefs();
        curatedPrefs.togglePinned("openrouter:charlie/other");
        curatedPrefs.recordUse("openrouter:beta/needle-b");
        assertEquals(List.of(
                        "openrouter:charlie/other",
                        "openrouter:beta/needle-b",
                        "openrouter:zulu/needle-z",
                        "openrouter:alpha/needle-a"),
                ModelPickerPopup.filterAndSort(models, "", curatedPrefs, featured));
    }

    @Test
    void filteringKeepsFeaturedOrderAndIgnoresMissingFeaturedIds() {
        List<String> models = List.of(
                "openrouter:zulu/needle-z",
                "openrouter:alpha/needle-a",
                "openrouter:beta/needle-b",
                "openrouter:charlie/other");

        assertEquals(List.of(
                        "openrouter:zulu/needle-z",
                        "openrouter:alpha/needle-a",
                        "openrouter:beta/needle-b"),
                ModelPickerPopup.filterAndSort(models, "needle", prefs(), List.of(
                        "openrouter:zulu/needle-z",
                        "openrouter:missing/needle-m",
                        "openrouter:alpha/needle-a")));
    }

    @Test
    void opencodeConfiguredModelsKeepFeaturedOrderAheadOfOtherModels() {
        List<String> models = List.of(
                "opencode:opencode/beam-free",
                "opencode:opencode/big-pickle",
                "opencode:opencode/nemotron-3.5-lightning-free",
                "opencode:default");

        assertEquals(List.of(
                        "opencode:default",
                        "opencode:opencode/nemotron-3.5-lightning-free",
                        "opencode:opencode/big-pickle",
                        "opencode:opencode/beam-free"),
                ModelPickerPopup.filterAndSort(models, "", prefs(), List.of(
                        "opencode:default",
                        "opencode:opencode/nemotron-3.5-lightning-free",
                        "opencode:opencode/big-pickle")));
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
    void mouseMovementNeverDismissesThePopup() {
        javax.swing.JPanel popupRoot = new javax.swing.JPanel();
        javax.swing.JButton outside = new javax.swing.JButton();
        java.awt.event.MouseEvent movement = new java.awt.event.MouseEvent(
                outside, java.awt.event.MouseEvent.MOUSE_MOVED,
                System.currentTimeMillis(), 0, 4, 4, 0, false);
        assertFalse(ModelPickerPopup.shouldDismissForMouseEvent(movement, popupRoot));
    }

    @Test
    void onlyMousePressOutsideThePopupDismissesIt() {
        javax.swing.JPanel popupRoot = new javax.swing.JPanel();
        javax.swing.JButton inside = new javax.swing.JButton();
        javax.swing.JButton outside = new javax.swing.JButton();
        popupRoot.add(inside);

        java.awt.event.MouseEvent insidePress = new java.awt.event.MouseEvent(
                inside, java.awt.event.MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), 0, 4, 4, 1, false);
        java.awt.event.MouseEvent outsidePress = new java.awt.event.MouseEvent(
                outside, java.awt.event.MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), 0, 4, 4, 1, false);
        assertFalse(ModelPickerPopup.shouldDismissForMouseEvent(insidePress, popupRoot));
        assertTrue(ModelPickerPopup.shouldDismissForMouseEvent(outsidePress, popupRoot));
    }

    @Test
    void popupWidthIsCompactAndCappedToTheScreen() {
        assertEquals(340, ModelPickerPopup.popupWidth(120, 1920));
        assertEquals(500, ModelPickerPopup.popupWidth(500, 1920));
        assertEquals(300, ModelPickerPopup.popupWidth(120, 300));
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
            assertEquals(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
                    popup.horizontalScrollBarPolicy());
            assertTrue(popup.modelListTracksViewportWidth());
            assertTrue(popup.modelListFixedCellHeight() > 0);
        } finally {
            popup.dispose();
            owner.dispose();
        }
    }

    @Test
    @org.junit.jupiter.api.condition.DisabledIfSystemProperty(
            named = "java.awt.headless", matches = "true")
    void filteringLargeModelListRendersOnlyVisibleRows() throws Exception {
        List<String> models = new ArrayList<>();
        for (int i = 1; i <= 400; i++) {
            String suffix = String.format("%04d", i);
            models.add("opencode:prov/model-" + suffix);
            models.add("openrouter:prov/router-" + suffix);
        }

        AtomicReference<JFrame> ownerReference = new AtomicReference<>();
        AtomicReference<ModelPickerPopup> popupReference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JFrame owner = new JFrame();
            owner.setSize(420, 360);
            owner.setVisible(true);
            ModelPickerPopup popup = new ModelPickerPopup(owner, models, models.get(0),
                    prefs(), ModelCapabilityCatalog.getInstance());
            popup.setBounds(100, 100, 420, 360);
            popup.setVisible(true);
            ownerReference.set(owner);
            popupReference.set(popup);
        });

        JFrame owner = ownerReference.get();
        ModelPickerPopup popup = popupReference.get();
        AtomicInteger rendererCalls = new AtomicInteger();
        try {
            SwingUtilities.invokeAndWait(() -> {
                ListCellRenderer<? super String> delegate = popup.modelList().getCellRenderer();
                popup.modelList().setCellRenderer((list, value, index, selected, cellHasFocus) -> {
                    rendererCalls.incrementAndGet();
                    return delegate.getListCellRendererComponent(
                            list, value, index, selected, cellHasFocus);
                });
                popup.validate();
                paintPopup(popup);
                rendererCalls.set(0);
            });

            String[] filters = { "m", "mo", "mod", "" };
            int[] expectedCounts = { 400, 400, 400, 800 };
            for (int i = 0; i < filters.length; i++) {
                String filter = filters[i];
                int expectedCount = expectedCounts[i];
                SwingUtilities.invokeAndWait(() -> {
                    popup.setFilterTextForTest(filter);
                    popup.validate();
                    paintPopup(popup);
                    assertEquals(expectedCount, popup.visibleModelCount());
                    assertNotNull(popup.selectedVisibleModel());
                });
            }

            assertTrue(rendererCalls.get() > 0);
            assertTrue(rendererCalls.get() < 200,
                    "expected only visible rows to be rendered, got " + rendererCalls.get());
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                popup.dispose();
                owner.dispose();
            });
        }
    }

    private static void paintPopup(ModelPickerPopup popup) {
        BufferedImage image = new BufferedImage(
                popup.getWidth(), popup.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            popup.getRootPane().printAll(graphics);
        } finally {
            graphics.dispose();
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
    void customModelIsRememberedAndSelectedImmediately() {
        // the CLI providers publish no model list, so a typed id has to work
        // without a properties edit and a JMeter restart
        ModelSelectorPreferences prefs = prefs();
        javax.swing.JFrame owner = new javax.swing.JFrame();
        owner.setSize(200, 40);
        owner.setVisible(true);
        ModelPickerPopup popup = new ModelPickerPopup(owner, MODELS,
                "claude-opus-4-8", prefs, ModelCapabilityCatalog.getInstance());
        java.util.List<String> selected = new java.util.ArrayList<>();
        try {
            popup.showFor(owner, selected::add);
            popup.useCustomModel("codex:gpt-5.6-sol");

            assertEquals(java.util.List.of("codex:gpt-5.6-sol"), selected);
            assertEquals("codex:gpt-5.6-sol", popup.lastCustomModel());
            assertEquals(java.util.List.of("codex:gpt-5.6-sol"), prefs.customModels());
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
