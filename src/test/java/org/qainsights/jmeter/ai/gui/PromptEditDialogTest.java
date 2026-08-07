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
}
