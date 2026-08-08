package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

import org.qainsights.jmeter.ai.service.prompts.PromptLibrary;

/**
 * Tests for {@link PromptEditDialog}'s validation and name-derivation rules.
 * The dialog itself is modal and not shown in tests; the rules live in static
 * package-private methods for exactly this reason.
 */
class PromptEditDialogTest {

    @TempDir
    Path tempDir;

    private PromptLibrary library() {
        return PromptLibrary.load(tempDir.resolve("prompts.json"));
    }

    @Test
    void validationRejectsBlankNameAndBody() {
        assertEquals("Name is required.", PromptEditDialog.validationError("  ", "body"));
        assertEquals("Name is required.", PromptEditDialog.validationError(null, "body"));
        assertEquals("Prompt text is required.", PromptEditDialog.validationError("Mine", " "));
        assertEquals("Prompt text is required.", PromptEditDialog.validationError("Mine", null));
        assertNull(PromptEditDialog.validationError("Mine", "body"));
    }

    @Test
    void validationRejectsBuiltinNames() {
        String error = PromptEditDialog.validationError("Analyze results", "body");
        assertNotNull(error);
        assertTrue(error.contains("built-in"));
    }

    @Test
    void wouldOverwriteOnlyForOtherExistingUserPrompts() {
        PromptLibrary library = library();
        library.save("Mine", "v1");

        assertTrue(PromptEditDialog.wouldOverwrite(library, "Mine", null));
        // editing "Mine" itself is not an overwrite
        assertFalse(PromptEditDialog.wouldOverwrite(library, "Mine", "Mine"));
        // unknown names never confirm
        assertFalse(PromptEditDialog.wouldOverwrite(library, "Fresh", null));
        // built-ins are rejected by validation, never reached as overwrite
        assertFalse(PromptEditDialog.wouldOverwrite(library, "Analyze results", null));
        assertFalse(PromptEditDialog.wouldOverwrite(library, null, null));
    }

    @Test
    void suggestedCopyNameFindsFirstFreeVariant() {
        PromptLibrary library = library();
        assertEquals("Analyze results (copy)",
                PromptEditDialog.suggestedCopyName(library, "Analyze results"));

        library.save("Analyze results (copy)", "v1");
        assertEquals("Analyze results (copy 2)",
                PromptEditDialog.suggestedCopyName(library, "Analyze results"));

        library.save("Analyze results (copy 2)", "v2");
        assertEquals("Analyze results (copy 3)",
                PromptEditDialog.suggestedCopyName(library, "Analyze results"));
    }

    @Test
    void persistEditSavesNewPromptWithoutTouchingOriginalWhenNotRenaming() {
        PromptLibrary library = library();
        library.save("Mine", "v1");

        assertTrue(PromptEditDialog.persistEdit(library, "Mine", "v2", "Mine"));
        assertEquals("v2", library.find("Mine").orElseThrow().body());
        assertEquals(7, library.all().size());
    }

    @Test
    void persistEditRenameDeletesOriginalOnlyAfterSaveSucceeds() {
        PromptLibrary library = library();
        library.save("Old", "v1");

        assertTrue(PromptEditDialog.persistEdit(library, "New", "v2", "Old"));
        assertTrue(library.find("Old").isEmpty());
        assertEquals("v2", library.find("New").orElseThrow().body());
    }

    @Test
    void persistEditKeepsOriginalWhenSaveFails() throws Exception {
        PromptLibrary library = library();
        library.save("Old", "v1");
        // block persistence by occupying the temp-file path with a directory
        java.nio.file.Files.createDirectory(tempDir.resolve("prompts.json.tmp"));

        assertFalse(PromptEditDialog.persistEdit(library, "New", "v2", "Old"));
        assertTrue(library.find("Old").isPresent());
        assertTrue(library.find("New").isEmpty());
        // on disk, still only the original
        PromptLibrary reloaded = PromptLibrary.load(tempDir.resolve("prompts.json"));
        assertTrue(reloaded.find("Old").isPresent());
        assertTrue(reloaded.find("New").isEmpty());
    }
}
