package org.qainsights.jmeter.ai.intellisense;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTextArea;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

import org.qainsights.jmeter.ai.service.prompts.PromptLibrary;

/**
 * Tests for the {@code @prompts} picker mode in {@link InputBoxIntellisense}:
 * trigger detection, live filtering, body-vs-name insertion, and the popup's
 * description lookup switching between command descriptions and prompt
 * previews.
 */
class PromptModeIntellisenseTest {

    @TempDir
    Path tempDir;

    private InputBoxIntellisense intellisense;
    private PromptLibrary library;

    @BeforeEach
    void setUp() {
        library = PromptLibrary.load(tempDir.resolve("prompts.json"));
        intellisense = new InputBoxIntellisense(new JTextArea());
    }

    @Test
    void promptQueryIsNullWithoutLibrary() {
        assertNull(intellisense.promptQuery("@prompts ana", 12));
    }

    @Test
    void promptQueryRequiresSpaceAfterCommand() {
        intellisense.setPromptLibrary(library);
        assertNull(intellisense.promptQuery("@prompts", 8));
        assertNull(intellisense.promptQuery("@lint", 5));
        assertEquals("", intellisense.promptQuery("@prompts ", 9));
        assertEquals("ana", intellisense.promptQuery("@prompts ana", 12));
    }

    @Test
    void promptQueryFindsTokenMidText() {
        intellisense.setPromptLibrary(library);
        String text = "please @prompts corr";
        assertEquals("corr", intellisense.promptQuery(text, text.length()));
    }

    @Test
    void promptQueryIgnoresAtPrecededByWordCharacter() {
        intellisense.setPromptLibrary(library);
        String text = "email@prompts x";
        assertNull(intellisense.promptQuery(text, text.length()));
    }

    @Test
    void promptQueryRejectsCaretAtZeroOrPastEnd() {
        intellisense.setPromptLibrary(library);
        assertNull(intellisense.promptQuery("@prompts x", 0));
        assertNull(intellisense.promptQuery("@prompts x", 99));
    }

    @Test
    void promptSuggestionsFiltersLive() {
        intellisense.setPromptLibrary(library);
        assertEquals(6, intellisense.promptSuggestions("").size());
        assertEquals(List.of("Analyze results"), intellisense.promptSuggestions("analyze"));
        assertTrue(intellisense.promptSuggestions("zzz").isEmpty());
    }

    @Test
    void insertionInPromptModeYieldsBody() {
        intellisense.setPromptLibrary(library);
        intellisense.promptSuggestions("analyze");
        String inserted = intellisense.insertionFor("Analyze results");
        assertTrue(inserted.startsWith("Analyze the attached JMeter results."));
        assertTrue(inserted.contains("[Attach a .jtl results file"));
    }

    @Test
    void insertionInPromptModeFallsBackToNameForUnknownSelection() {
        intellisense.setPromptLibrary(library);
        intellisense.promptSuggestions("analyze");
        assertEquals("Mystery", intellisense.insertionFor("Mystery"));
    }

    @Test
    void promptsCommandInsertsWithTrailingSpaceToEnterPicker() {
        intellisense.setPromptLibrary(library);
        assertEquals("@prompts ", intellisense.insertionFor("@prompts"));
        assertEquals("@lint", intellisense.insertionFor("@lint"));
        assertNull(intellisense.insertionFor(null));
    }

    @Test
    void promptsCommandIsSuggestedWithDescription() {
        CommandIntellisenseProvider provider = new CommandIntellisenseProvider();
        assertTrue(provider.getSuggestions("@p").contains("@prompts"));
        assertFalse(CommandIntellisenseProvider.getDescription("@prompts").isEmpty());
    }

    @Test
    void promptModeSwapsPopupDescriptionsToPreviews() {
        IntellisensePopup popup = new IntellisensePopup();
        IntellisensePopup.SuggestionCellRenderer renderer =
                (IntellisensePopup.SuggestionCellRenderer) popup.suggestionList.getCellRenderer();

        JLabel commandCell = (JLabel) renderer.getListCellRendererComponent(
                new JList<>(), "@lint", 0, false, false);
        assertTrue(commandCell.getText().contains("meaningful names"));

        popup.setDescriptionLookup(name -> "preview of " + name);
        JLabel promptCell = (JLabel) renderer.getListCellRendererComponent(
                new JList<>(), "Analyze results", 0, false, false);
        assertTrue(promptCell.getText().contains("preview of Analyze results"));

        popup.setDescriptionLookup(null);
        JLabel resetCell = (JLabel) renderer.getListCellRendererComponent(
                new JList<>(), "@lint", 0, false, false);
        assertTrue(resetCell.getText().contains("meaningful names"));
    }

    @Test
    void selectedPromptIsNullOutsidePromptMode() {
        intellisense.setPromptLibrary(library);
        assertNull(intellisense.selectedPrompt());
    }

    @Test
    void selectedPromptMapsSelectionToMatch() {
        intellisense.setPromptLibrary(library);
        List<String> names = intellisense.promptSuggestions("analyze");
        intellisense.popup().suggestionList.setListData(names.toArray(new String[0]));
        intellisense.popup().setSelectedIndex(0);

        PromptLibrary.Prompt selected = intellisense.selectedPrompt();
        assertNotNull(selected);
        assertEquals("Analyze results", selected.name());
        assertTrue(selected.builtin());
    }

    @Test
    void pickerReflectsLibraryChangesOnRequery() {
        intellisense.setPromptLibrary(library);
        library.save("My check", "body");
        assertEquals(List.of("My check"), intellisense.promptSuggestions("check"));

        library.delete("My check");
        assertTrue(intellisense.promptSuggestions("check").isEmpty());
    }

    @Test
    void moveSelectionWrapsAroundBothEnds() {
        intellisense.popup().suggestionList.setListData(new String[]{"a", "b", "c"});
        intellisense.popup().setSelectedIndex(0);

        intellisense.moveSelection(1);
        assertEquals(1, intellisense.popup().getSelectedIndex());
        intellisense.moveSelection(-1);
        intellisense.moveSelection(-1);
        assertEquals(2, intellisense.popup().getSelectedIndex());
        intellisense.moveSelection(1);
        assertEquals(0, intellisense.popup().getSelectedIndex());
    }

    @Test
    void moveSelectionIsNoOpWithNoSuggestions() {
        intellisense.popup().suggestionList.setListData(new String[0]);
        intellisense.moveSelection(1);
        assertEquals(-1, intellisense.popup().getSelectedIndex());
    }

    @Test
    void rendererToleratesNullDescriptionsFromLookup() {
        IntellisensePopup.SuggestionCellRenderer renderer =
                new IntellisensePopup.SuggestionCellRenderer(() -> name -> null);
        JLabel label = (JLabel) renderer.getListCellRendererComponent(
                new JList<>(), "anything", 0, false, false);
        assertEquals("anything", label.getText());
    }
}
