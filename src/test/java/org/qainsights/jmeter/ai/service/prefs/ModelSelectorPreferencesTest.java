package org.qainsights.jmeter.ai.service.prefs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ModelSelectorPreferences}: load/save round-trip,
 * pin toggling, MRU recents with dedup and cap, and resilience to missing or
 * corrupt files.
 */
class ModelSelectorPreferencesTest {

    @TempDir
    Path tempDir;

    private Path prefsFile() {
        return tempDir.resolve("preferences.json");
    }

    @Test
    void missingFileYieldsEmptyPreferences() {
        ModelSelectorPreferences prefs = ModelSelectorPreferences.load(prefsFile());
        assertTrue(prefs.pinned().isEmpty());
        assertTrue(prefs.recents().isEmpty());
        assertFalse(prefs.isPinned("openai:gpt-5.1"));
    }

    @Test
    void recordUseKeepsMostRecentFirstAndDedups() {
        ModelSelectorPreferences prefs = ModelSelectorPreferences.load(prefsFile());
        prefs.recordUse("openai:gpt-5.1");
        prefs.recordUse("claude-opus-4-8");
        prefs.recordUse("openai:gpt-5.1");
        assertEquals(List.of("openai:gpt-5.1", "claude-opus-4-8"), prefs.recents());
    }

    @Test
    void recentsAreCappedAtMax() {
        ModelSelectorPreferences prefs = ModelSelectorPreferences.load(prefsFile());
        for (int i = 0; i < ModelSelectorPreferences.MAX_RECENTS + 3; i++) {
            prefs.recordUse("openai:model-" + i);
        }
        assertEquals(ModelSelectorPreferences.MAX_RECENTS, prefs.recents().size());
        assertEquals("openai:model-" + (ModelSelectorPreferences.MAX_RECENTS + 2),
                prefs.recents().get(0));
    }

    @Test
    void recordUseIgnoresNullAndEmpty() {
        ModelSelectorPreferences prefs = ModelSelectorPreferences.load(prefsFile());
        prefs.recordUse(null);
        prefs.recordUse("");
        assertTrue(prefs.recents().isEmpty());
    }

    @Test
    void togglePinnedAddsThenRemoves() {
        ModelSelectorPreferences prefs = ModelSelectorPreferences.load(prefsFile());
        prefs.togglePinned("google:gemini-2.5-pro");
        assertTrue(prefs.isPinned("google:gemini-2.5-pro"));
        prefs.togglePinned("openai:gpt-5.1");
        assertEquals(List.of("google:gemini-2.5-pro", "openai:gpt-5.1"), prefs.pinned());
        prefs.togglePinned("google:gemini-2.5-pro");
        assertEquals(List.of("openai:gpt-5.1"), prefs.pinned());
    }

    @Test
    void togglePinnedIgnoresNullAndEmpty() {
        ModelSelectorPreferences prefs = ModelSelectorPreferences.load(prefsFile());
        prefs.togglePinned(null);
        prefs.togglePinned("");
        assertTrue(prefs.pinned().isEmpty());
    }

    @Test
    void changesSurviveReload() {
        ModelSelectorPreferences prefs = ModelSelectorPreferences.load(prefsFile());
        prefs.togglePinned("claude-opus-4-8");
        prefs.recordUse("grok:grok-4.5");
        prefs.recordUse("claude-opus-4-8");

        ModelSelectorPreferences reloaded = ModelSelectorPreferences.load(prefsFile());
        assertEquals(List.of("claude-opus-4-8"), reloaded.pinned());
        assertEquals(List.of("claude-opus-4-8", "grok:grok-4.5"), reloaded.recents());
    }

    @Test
    void corruptFileYieldsEmptyPreferences() throws Exception {
        Files.writeString(prefsFile(), "{ not json !!!");
        ModelSelectorPreferences prefs = ModelSelectorPreferences.load(prefsFile());
        assertTrue(prefs.pinned().isEmpty());
        assertTrue(prefs.recents().isEmpty());
    }

    @Test
    void duplicateIdsInFileAreCollapsed() throws Exception {
        Files.writeString(prefsFile(),
                "{\"pinned\":[\"a\",\"a\",\"b\"],\"recents\":[\"x\",\"x\",\"y\"]}");
        ModelSelectorPreferences prefs = ModelSelectorPreferences.load(prefsFile());
        assertEquals(List.of("a", "b"), prefs.pinned());
        assertEquals(List.of("x", "y"), prefs.recents());
    }

    @Test
    void oversizedRecentsInFileAreTrimmed() throws Exception {
        StringBuilder json = new StringBuilder("{\"recents\":[");
        for (int i = 0; i < ModelSelectorPreferences.MAX_RECENTS + 5; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("\"m").append(i).append('"');
        }
        json.append("]}");
        Files.writeString(prefsFile(), json.toString());
        ModelSelectorPreferences prefs = ModelSelectorPreferences.load(prefsFile());
        assertEquals(ModelSelectorPreferences.MAX_RECENTS, prefs.recents().size());
    }

    @Test
    void loadHonoursPathPropertyOverride() {
        Path custom = tempDir.resolve("custom-prefs.json");
        System.setProperty(ModelSelectorPreferences.PATH_PROPERTY, custom.toString());
        try {
            ModelSelectorPreferences prefs = ModelSelectorPreferences.load();
            prefs.recordUse("openai:gpt-5.1");
            assertTrue(Files.exists(custom));
            assertEquals(List.of("openai:gpt-5.1"),
                    ModelSelectorPreferences.load(custom).recents());
        } finally {
            System.clearProperty(ModelSelectorPreferences.PATH_PROPERTY);
        }
    }

    @Test
    void changeListenersAreNotifiedOnEveryMutation() {
        ModelSelectorPreferences prefs = ModelSelectorPreferences.load(prefsFile());
        java.util.concurrent.atomic.AtomicInteger notifications = new java.util.concurrent.atomic.AtomicInteger();
        prefs.addChangeListener(notifications::incrementAndGet);

        prefs.togglePinned("openai:gpt-5.1");
        prefs.recordUse("openai:gpt-5.1");

        assertEquals(2, notifications.get());
    }

    @Test
    void customModelsRoundTripAndDedup() {
        ModelSelectorPreferences prefs = ModelSelectorPreferences.load(prefsFile());
        prefs.addCustomModel("codex:gpt-5.6-sol");
        prefs.addCustomModel("codex:gpt-5.6-sol");
        prefs.addCustomModel("claude-code:opus-4.6");
        prefs.addCustomModel("  ");
        prefs.addCustomModel(null);

        assertEquals(List.of("codex:gpt-5.6-sol", "claude-code:opus-4.6"), prefs.customModels());
        assertEquals(List.of("codex:gpt-5.6-sol", "claude-code:opus-4.6"),
                ModelSelectorPreferences.load(prefsFile()).customModels());
    }

    @Test
    void removeCustomModelForgetsTheId() {
        ModelSelectorPreferences prefs = ModelSelectorPreferences.load(prefsFile());
        prefs.addCustomModel("codex:gpt-5.6-sol");
        prefs.removeCustomModel("codex:gpt-5.6-sol");
        assertTrue(prefs.customModels().isEmpty());
        assertTrue(ModelSelectorPreferences.load(prefsFile()).customModels().isEmpty());
    }

    @Test
    void saveWritesIntoMissingParentDirectory() {
        Path nested = tempDir.resolve("no/such/dir/preferences.json");
        ModelSelectorPreferences prefs = ModelSelectorPreferences.load(nested);
        prefs.recordUse("ollama:qwen3:8b");
        assertTrue(Files.exists(nested));
        assertEquals(List.of("ollama:qwen3:8b"),
                ModelSelectorPreferences.load(nested).recents());
    }
}
