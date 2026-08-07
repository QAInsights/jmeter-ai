package org.qainsights.jmeter.ai.service.prompts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PromptLibrary}: built-ins always present, save/delete
 * round-trip, built-in immutability, filtering, and resilience to missing or
 * corrupt files.
 */
class PromptLibraryTest {

    @TempDir
    Path tempDir;

    private Path libraryFile() {
        return tempDir.resolve("prompts.json");
    }

    @Test
    void missingFileYieldsBuiltinsOnly() {
        PromptLibrary library = PromptLibrary.load(libraryFile());
        assertEquals(6, library.all().size());
        assertTrue(library.all().stream().allMatch(PromptLibrary.Prompt::builtin));
    }

    @Test
    void builtinsCoverTheApprovedStarterSet() {
        PromptLibrary library = PromptLibrary.load(libraryFile());
        List<String> names = library.all().stream().map(PromptLibrary.Prompt::name).toList();
        assertEquals(List.of(
                "Analyze results",
                "Review plan vs best practices",
                "Explain errors in jmeter.log",
                "Suggest assertions & timers",
                "Find correlation candidates",
                "Recording brief"), names);
    }

    @Test
    void saveAddsUserPromptAfterBuiltins() {
        PromptLibrary library = PromptLibrary.load(libraryFile());
        assertTrue(library.save("My smoke test", "body text"));
        assertEquals(7, library.all().size());
        PromptLibrary.Prompt last = library.all().get(6);
        assertEquals("My smoke test", last.name());
        assertFalse(last.builtin());
    }

    @Test
    void saveWithExistingNameReplacesUserPrompt() {
        PromptLibrary library = PromptLibrary.load(libraryFile());
        library.save("Mine", "v1");
        library.save("Mine", "v2");
        assertEquals(7, library.all().size());
        assertEquals("v2", library.find("Mine").orElseThrow().body());
    }

    @Test
    void saveRejectsBuiltinNamesBlankNamesAndBlankBodies() {
        PromptLibrary library = PromptLibrary.load(libraryFile());
        assertFalse(library.save("Analyze results", "hijack"));
        assertFalse(library.save("  ", "body"));
        assertFalse(library.save("Valid", " "));
        assertFalse(library.save(null, "body"));
        assertFalse(library.save("Valid", null));
        assertEquals(6, library.all().size());
    }

    @Test
    void deleteRemovesUserPromptButNotBuiltins() {
        PromptLibrary library = PromptLibrary.load(libraryFile());
        library.save("Mine", "body");
        assertTrue(library.delete("Mine"));
        assertTrue(library.find("Mine").isEmpty());
        assertFalse(library.delete("Analyze results"));
        assertFalse(library.delete("nonexistent"));
        assertFalse(library.delete(null));
        assertEquals(6, library.all().size());
    }

    @Test
    void changesSurviveReload() {
        PromptLibrary library = PromptLibrary.load(libraryFile());
        library.save("Alpha", "first");
        library.save("Beta", "second");
        library.delete("Alpha");

        PromptLibrary reloaded = PromptLibrary.load(libraryFile());
        assertEquals(7, reloaded.all().size());
        assertEquals("second", reloaded.find("Beta").orElseThrow().body());
    }

    @Test
    void corruptFileYieldsBuiltinsOnly() throws Exception {
        Files.writeString(libraryFile(), "{ not json !!!");
        PromptLibrary library = PromptLibrary.load(libraryFile());
        assertEquals(6, library.all().size());
    }

    @Test
    void entriesWithBlankFieldsOrBuiltinNamesAreSkippedOnLoad() throws Exception {
        Files.writeString(libraryFile(), "{\"prompts\":["
                + "{\"name\":\"\",\"body\":\"x\"},"
                + "{\"name\":\"NoBody\"},"
                + "{\"name\":\"Analyze results\",\"body\":\"hijack\"},"
                + "{\"name\":\"Good\",\"body\":\"kept\"}]}");
        PromptLibrary library = PromptLibrary.load(libraryFile());
        assertEquals(7, library.all().size());
        assertEquals("kept", library.find("Good").orElseThrow().body());
        assertTrue(library.find("Analyze results").orElseThrow().builtin());
    }

    @Test
    void duplicateNamesInFileAreCollapsed() throws Exception {
        Files.writeString(libraryFile(), "{\"prompts\":["
                + "{\"name\":\"Dup\",\"body\":\"first\"},"
                + "{\"name\":\"Dup\",\"body\":\"second\"}]}");
        PromptLibrary library = PromptLibrary.load(libraryFile());
        assertEquals(7, library.all().size());
        assertEquals("first", library.find("Dup").orElseThrow().body());
    }

    @Test
    void filterMatchesNameCaseInsensitively() {
        PromptLibrary library = PromptLibrary.load(libraryFile());
        library.save("My SQL check", "body");
        assertEquals(1, library.filter("analyze").size());
        assertEquals("Analyze results", library.filter("ANALYZE").get(0).name());
        assertEquals(1, library.filter("sql").size());
        assertTrue(library.filter("zzz-no-match").isEmpty());
    }

    @Test
    void filterWithBlankQueryReturnsAll() {
        PromptLibrary library = PromptLibrary.load(libraryFile());
        assertEquals(6, library.filter("").size());
        assertEquals(6, library.filter("   ").size());
        assertEquals(6, library.filter(null).size());
    }

    @Test
    void previewIsFirstLineOfBody() {
        PromptLibrary.Prompt multi = new PromptLibrary.Prompt("P", "first line\nsecond line", false);
        assertEquals("first line", multi.preview());
        PromptLibrary.Prompt single = new PromptLibrary.Prompt("P", "only line", false);
        assertEquals("only line", single.preview());
    }

    @Test
    void loadHonoursPathPropertyOverride() {
        Path custom = tempDir.resolve("custom-prompts.json");
        System.setProperty(PromptLibrary.PATH_PROPERTY, custom.toString());
        try {
            PromptLibrary library = PromptLibrary.load();
            library.save("Custom", "body");
            assertTrue(Files.exists(custom));
            assertEquals("body", PromptLibrary.load(custom).find("Custom").orElseThrow().body());
        } finally {
            System.clearProperty(PromptLibrary.PATH_PROPERTY);
        }
    }

    @Test
    void changeListenersAreNotifiedOnEveryPersistedMutation() {
        PromptLibrary library = PromptLibrary.load(libraryFile());
        java.util.concurrent.atomic.AtomicInteger notifications = new java.util.concurrent.atomic.AtomicInteger();
        library.addChangeListener(notifications::incrementAndGet);

        library.save("One", "body");
        library.save("One", "body v2");
        library.delete("One");
        library.save("Analyze results", "refused"); // no persist, no notification

        assertEquals(3, notifications.get());
    }

    @Test
    void saveWritesIntoMissingParentDirectory() {
        Path nested = tempDir.resolve("no/such/dir/prompts.json");
        PromptLibrary library = PromptLibrary.load(nested);
        library.save("Nested", "body");
        assertTrue(Files.exists(nested));
        assertEquals("body", PromptLibrary.load(nested).find("Nested").orElseThrow().body());
    }
}
