package org.qainsights.jmeter.ai.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Component;
import java.nio.file.Path;
import javax.swing.JLabel;
import javax.swing.JList;

import org.qainsights.jmeter.ai.service.prefs.ModelSelectorPreferences;
import org.qainsights.jmeter.ai.service.reasoning.ModelCapabilityCatalog;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ModelPickerRenderer}: metadata line construction
 * (context window, pricing, capability tags), compact number formatting, and
 * the two-line cell rendering incl. the pinned star. Uses the real vendored
 * models.dev catalog from the test classpath.
 */
class ModelPickerRendererTest {

    @TempDir
    Path tempDir;

    private ModelSelectorPreferences prefs() {
        return ModelSelectorPreferences.load(tempDir.resolve("preferences.json"));
    }

    @Test
    void metadataForFullEntry() {
        String metadata = ModelPickerRenderer.metadataFor(
                "openai:gpt-5.1", ModelCapabilityCatalog.getInstance());
        assertEquals("400k ctx · $1.25/$10 per Mtok · vision · thinking", metadata);
    }

    @Test
    void metadataForMetadataOnlyEntry() {
        // No reasoning/vision flags - only context + cost survive the trim
        String metadata = ModelPickerRenderer.metadataFor(
                "google:gemini-2.5-flash-preview-tts", ModelCapabilityCatalog.getInstance());
        assertEquals("8k ctx · $0.5/$10 per Mtok", metadata);
    }

    @Test
    void metadataForLocalAndUnknownModels() {
        ModelCapabilityCatalog catalog = ModelCapabilityCatalog.getInstance();
        assertEquals("local model", ModelPickerRenderer.metadataFor("ollama:qwen3:8b", catalog));
        assertEquals("", ModelPickerRenderer.metadataFor("openai:gpt-9-turbo", catalog));
        assertEquals("", ModelPickerRenderer.metadataFor(null, catalog));
        assertEquals("", ModelPickerRenderer.metadataFor("", catalog));
    }

    @Test
    void formatContextCompacts() {
        assertEquals("1M", ModelPickerRenderer.formatContext(1_048_576));
        assertEquals("400k", ModelPickerRenderer.formatContext(400_000));
        assertEquals("131k", ModelPickerRenderer.formatContext(131_072));
        assertEquals("16k", ModelPickerRenderer.formatContext(16_385));
        assertEquals("8k", ModelPickerRenderer.formatContext(8_192));
        assertEquals("500", ModelPickerRenderer.formatContext(500));
    }

    @Test
    void formatCostStripsTrailingZeros() {
        assertEquals("10", ModelPickerRenderer.formatCost(10.0));
        assertEquals("1.25", ModelPickerRenderer.formatCost(1.25));
        assertEquals("0.075", ModelPickerRenderer.formatCost(0.075));
        assertEquals("0.3", ModelPickerRenderer.formatCost(0.3));
    }

    @Test
    void escapeHtmlNeutralizesSpecials() {
        assertEquals("a&lt;b&gt;&amp;c", ModelPickerRenderer.escapeHtml("a<b>&c"));
    }

    private static String rgb(java.awt.Color c) {
        return "rgb(" + c.getRed() + "," + c.getGreen() + "," + c.getBlue() + ")";
    }

    @Test
    void everyRowShowsAStarColoredByPinState() {
        ModelSelectorPreferences prefs = prefs();
        prefs.togglePinned("openai:gpt-5.1");
        ModelPickerRenderer renderer =
                new ModelPickerRenderer(prefs, ModelCapabilityCatalog.getInstance());

        Component pinned = renderer.getListCellRendererComponent(
                new JList<>(), "openai:gpt-5.1", 0, false, false);
        String pinnedHtml = ((JLabel) pinned).getText();
        assertTrue(pinnedHtml.contains("★"));
        assertTrue(pinnedHtml.contains(rgb(org.qainsights.jmeter.ai.gui.theme.ThemeColors.accent())));

        Component unpinned = renderer.getListCellRendererComponent(
                new JList<>(), "google:gemini-2.5-pro", 1, false, false);
        String unpinnedHtml = ((JLabel) unpinned).getText();
        assertTrue(unpinnedHtml.contains("★"), "unpinned rows must still show the pin affordance");
        assertTrue(unpinnedHtml.contains(
                rgb(org.qainsights.jmeter.ai.gui.theme.ThemeColors.secondaryText())));
    }

    @Test
    void selectedCellUsesExplicitReadableForeground() {
        ModelPickerRenderer renderer =
                new ModelPickerRenderer(prefs(), ModelCapabilityCatalog.getInstance());
        JLabel label = (JLabel) renderer.getListCellRendererComponent(
                new JList<>(), "openai:gpt-5.1", 0, true, false);
        String foreground = rgb(
                org.qainsights.jmeter.ai.gui.theme.ThemeColors.foreground());

        assertTrue(label.getText().contains(foreground));
        assertTrue(label.getText().contains("<b>gpt-5.1</b>"));
        assertEquals(org.qainsights.jmeter.ai.gui.theme.ThemeColors.selectedBackground(),
                label.getBackground());
    }

    @Test
    void cellRendersTwoLinesWithDisplayNameAndMetadata() {
        ModelPickerRenderer renderer =
                new ModelPickerRenderer(prefs(), ModelCapabilityCatalog.getInstance());
        Component c = renderer.getListCellRendererComponent(
                new JList<>(), "openai:gpt-5.1", 0, false, false);
        String html = ((JLabel) c).getText();
        assertTrue(html.contains("<b>gpt-5.1</b> · OpenAI"));
        assertTrue(html.contains("400k ctx"));
        assertTrue(html.contains("<br>"));
    }

    @Test
    void cellWithoutMetadataRendersSingleLine() {
        ModelPickerRenderer renderer =
                new ModelPickerRenderer(prefs(), ModelCapabilityCatalog.getInstance());
        Component c = renderer.getListCellRendererComponent(
                new JList<>(), "openai:gpt-9-turbo", 0, false, false);
        String html = ((JLabel) c).getText();
        assertTrue(html.contains("<b>gpt-9-turbo</b> · OpenAI"));
        assertFalse(html.contains("<br>"));
    }
}
